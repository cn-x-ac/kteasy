/*
 * Copyright 2026 阿杰很厉害 <master@x-ac.cn>. SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.x.ac.kteasy.server.schema

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MdTestClient
import cn.x.ac.kteasy.server.md.MdTestSupport
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 步骤卡 M1-03 Block D（物化作业账本观测读 API + 手工续跑，双库真连）：
 * 建对象经异步执行器落 CREATE_TABLE 步后，治理面三端点如实投影账本——
 * ① `GET /api/md/schema-jobs` 全量列表（三键 + total + object_api）；
 * ② `GET /api/md/schema-jobs/{api}` 按对象列其步（api→object_id 解析）；
 * ③ 未知对象 → 404 契约体（拒绝静默忽略）；
 * ④ `POST /api/md/schema-jobs/{api}/retry` 手工续跑入队（幂等，无待办也不炸）。
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`；执行器异步，读侧用轮询 await（40s 宽）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class SchemaJobApiIT {
    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: DataSource

    private val dialect: String get() = if (context.dialect == Dialect.POSTGRESQL) "pg" else "mysql"

    private val client: MdTestClient by lazy {
        val port =
            requireNotNull(environment.getProperty("local.server.port")) { "RANDOM_PORT 未注入" }
                .toInt()
        MdTestClient(port)
    }

    private val suffix: String = Ulid.next().lowercase().takeLast(8)
    private val custApi = "m03job$suffix"

    @AfterEach
    fun cleanup() {
        val jt = JdbcTemplate(dataSource)
        runCatching { jt.execute("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, custApi)} CASCADE") }
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "DELETE FROM ${MdTestSupport.table(dialect, "md_field")} WHERE object_id IN (SELECT id FROM ${MdTestSupport.table(dialect, "md_object")} WHERE api_name LIKE ?)",
                ).use { ps ->
                    ps.setString(1, "%$suffix%")
                    ps.executeUpdate()
                }
            conn.prepareStatement("DELETE FROM ${MdTestSupport.table(dialect, "md_object")} WHERE api_name LIKE ?").use { ps ->
                ps.setString(1, "%$suffix%")
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `治理读API列作业按对象过滤未知返404并可手工续跑`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = custApi,
                label = "作业观测",
                kind = "PLAIN",
                displayName = "{name}",
                fields = listOf(MetadataService.FieldCmd(apiName = "name", label = "名称", logicalType = "TEXT")),
            ),
        )

        // 异步执行器把 CREATE_TABLE 步跑到 DONE：轮询读 API 直到投影里出现该步终态。
        val (done, lastBody) = awaitApi { it.contains("\"step_kind\":\"CREATE_TABLE\"") && it.contains("\"state\":\"DONE\"") }
        assertThat(done).`as`("应经读 API 看到 CREATE_TABLE 步 DONE；最后响应=%s", lastBody).isTrue()

        // ① 全量列表端点契约（三键 + total，且能看到本对象的 api）
        val all = client.get("/api/md/schema-jobs")
        assertThat(all.status).isEqualTo(200)
        assertThat(all.body).contains("\"error_code\":0")
        assertThat(all.body).contains("\"total\"")
        assertThat(all.body).contains("\"object_api\":\"$custApi\"")

        // ② 按对象列其全部步（api→object_id 解析后查账本）
        val byObj = client.get("/api/md/schema-jobs/$custApi")
        assertThat(byObj.status).isEqualTo(200)
        assertThat(byObj.body).contains("\"object_api\":\"$custApi\"")
        assertThat(byObj.body).contains("\"step_kind\":\"CREATE_TABLE\"")
        assertThat(byObj.body).contains("\"state\":\"DONE\"")

        // ③ 未知对象 → 404 契约体（不静默返回空列表）
        val missing = client.get("/api/md/schema-jobs/nope_$suffix")
        assertThat(missing.status).isEqualTo(404)
        assertThat(missing.body).contains("\"error_code\":404")

        // ④ 手工续跑：对已 DONE 的对象重试安全无副作用，仍返回入队受理
        val retry = client.post("/api/md/schema-jobs/$custApi/retry", "{}")
        assertThat(retry.status).isEqualTo(200)
        assertThat(retry.body).contains("\"error_code\":0")
        assertThat(retry.body).contains("\"queued\":true")
    }

    /** 轮询读侧端点直到谓词命中或超时（执行器 AFTER_COMMIT + 串行队列，给宽 40s，§E 已知 await 须宽）。 */
    private fun awaitApi(
        timeoutMs: Long = 40_000,
        pred: (String) -> Boolean,
    ): Pair<Boolean, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val resp = client.get("/api/md/schema-jobs/$custApi")
            last = resp.body
            if (resp.status == 200 && pred(resp.body)) return true to last
            TimeUnit.MILLISECONDS.sleep(250)
        }
        return pred(last) to last
    }
}
