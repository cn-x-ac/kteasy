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

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.core.schema.StepKind
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import cn.x.ac.kteasy.server.md.MdTestClient
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 步骤卡 M1-03 Block F · GWT#5（人为改坏某步 doStep → 重试 3 次挂起 + 告警 → 修好后手工续跑至 DONE）。
 *
 * 用执行器的测试故障钩子确定性令 BACKFILL 步抛错，验作业账本挂起且余下步不推进；清除故障后经 **Block D 的
 * `POST /api/md/schema-jobs/{api}/retry`** 手工续跑，账本从 checkpoint 恢复到全部 DONE，且物理化结果不丢数。
 * 双库真连（KTEASY_IT_DB 门控）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class StepFailureResumeIT {
    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var executor: SchemaJobExecutor

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val apiName = "m03f5$suffix"

    private val client: MdTestClient by lazy {
        MdTestClient(requireNotNull(environment.getProperty("local.server.port")).toInt())
    }

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun await(
        timeoutMs: Long = 40_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(150)
        }
        return poll()
    }

    private fun rowOf(kind: StepKind): SchemaJob? = jobRepo.listByObject(objectId()).firstOrNull { it.stepKind == kind }

    private lateinit var cachedObjId: String

    private fun objectId(): String {
        if (!::cachedObjId.isInitialized) {
            cachedObjId =
                meta
                    .loadSnapshot()
                    .objects
                    .first { it.apiName == apiName }
                    .id
        }
        return cachedObjId
    }

    @AfterEach
    fun cleanup() {
        executor.faultForTest = null
        val jt = JdbcTemplate(dataSource)
        runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.ENTITY, apiName)} CASCADE") }
        val like = "%$suffix%"
        val objQual = qual(LogicalArea.METADATA, "md_object")
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "DELETE FROM ${qual(LogicalArea.METADATA, "md_field")} WHERE object_id IN (SELECT id FROM $objQual WHERE api_name LIKE ?)",
                ).use { ps ->
                    ps.setString(1, like)
                    ps.executeUpdate()
                }
            conn.prepareStatement("DELETE FROM ${qual(LogicalArea.METADATA, "md_schema_change_job")} WHERE object_id IN (SELECT id FROM $objQual WHERE api_name LIKE ?)").use { ps ->
                ps.setString(1, like)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM $objQual WHERE api_name LIKE ?").use { ps ->
                ps.setString(1, like)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `改坏回填步挂起后 清除故障手工续跑至DONE且不丢数`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = apiName,
                label = "结算",
                kind = "PLAIN",
                displayName = "{amount}",
                fields = listOf(MetadataService.FieldCmd(apiName = "amount", label = "金额", logicalType = "DECIMAL")),
            ),
        )
        val te = provider.introspection.tableExists(LogicalArea.ENTITY, apiName)
        assertThat(await { (jdbc().queryForObject(te.sql, MapSqlParameterSource(te.params), Long::class.java) ?: 0L) > 0 })
            .`as`("对象表应先物化")
            .isTrue()
        val amountField = meta.loadSnapshot().fields.first { it.objectId == objectId() && it.apiName == "amount" }

        val ids = (1..12).map { Ulid.next() }
        ids.forEachIndexed { i, id ->
            jdbc().update(
                "INSERT INTO ${qual(LogicalArea.ENTITY, apiName)} (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
                mapOf("id" to id, "ext" to "{\"amount\":${(i + 1) * 7}}"),
            )
        }

        // 人为改坏 BACKFILL 步：抛异常。提交物理化，应重试 3 次后挂起、余下步不推进。
        executor.faultForTest = { kind -> if (kind == StepKind.BACKFILL_BATCH) IllegalStateException("测试注入：回填步改坏") else null }
        val plan = SchemaDiff.planPhysicalize(objectId(), apiName, amountField.id, "amount", PhysicalColumn("amount", ColumnType.BIGINT), ValueCast.LONG, makeIndex = false)
        executor.submitPhysicalization(objectId(), plan)

        assertThat(await { rowOf(StepKind.BACKFILL_BATCH)?.let { it.state == "FAILED" && it.attempts >= 3 } == true })
            .`as`("回填步应重试至多 3 次后挂起 FAILED")
            .isTrue()
        assertThat(rowOf(StepKind.SWITCH_READ)?.state).`as`("挂起后读切换步不得推进").isEqualTo("PENDING")
        assertThat(rowOf(StepKind.CLEAN_EXT_KEY)?.state).`as`("挂起后清 key 步不得推进").isEqualTo("PENDING")

        // 修好（清除故障）→ 经 Block D 手工续跑端点触发。
        executor.faultForTest = null
        val retry = client.post("/api/md/schema-jobs/$apiName/retry", "{}")
        assertThat(retry.status).isEqualTo(200)
        assertThat(retry.body).contains("\"queued\":true")

        assertThat(await { !jobRepo.hasUnfinished(objectId()) })
            .`as`("手工续跑应把所有未到终态步恢复到 DONE")
            .isTrue()

        // 不丢数 + ext 已清 + 读判据已翻列。
        val binOk = ids.map { jdbc().queryForList("SELECT amount FROM ${qual(LogicalArea.ENTITY, apiName)} WHERE id = :id", mapOf("id" to it), Long::class.java).firstOrNull() }
        assertThat(binOk).containsExactlyElementsOf((1..12).map { (it) * 7L })
        val exists = provider.json.predicateExists("ext", JsonPath.of("amount")).sql
        val remaining = jdbc().queryForObject("SELECT COUNT(*) FROM ${qual(LogicalArea.ENTITY, apiName)} WHERE $exists", emptyMap<String, Any>(), Long::class.java) ?: 0L
        assertThat(remaining).`as`("续跑完成后 ext 旧键应清零").isEqualTo(0L)
        assertThat(
            meta
                .loadSnapshot()
                .fields
                .first { it.id == amountField.id }
                .storageKind,
        ).isEqualTo(StorageKind.COLUMN)
    }
}
