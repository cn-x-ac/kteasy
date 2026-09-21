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

import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataRepository
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 步骤卡 M1-04 块 5 · 字段物理化（`type-convert`）双库集成。
 *
 * 证三件事：① 合法边——EXT 标量（NUMBER）经 `PhysicalizeService.physicalize` 入队五步，执行器最终把 `amount` 落成可写真列且 `storage_kind` 翻 COLUMN；
 * ② 非法边——不可物理化型（FILE）抛 `KnownKteasyException`，不入队；③ 返回体含后果告知（"不丢数据"）。
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`；执行器异步，断言轮询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class TypeConvertIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var repo: MetadataRepository

    @Autowired
    lateinit var physicalize: PhysicalizeService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val created = mutableListOf<String>()

    private fun name(base: String): String = "${base}_$suffix".also { created += it }

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun columnExists(
        logical: String,
        column: String,
    ): Boolean {
        val f = provider.introspection.columnExists(LogicalArea.ENTITY, logical, column)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(
        timeoutMs: Long = 45_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    @AfterEach
    fun cleanup() {
        val jt = JdbcTemplate(dataSource)
        created.reversed().forEach { logical ->
            runCatching { jt.execute("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, logical)} CASCADE") }
        }
    }

    private fun fieldCmd(
        api: String,
        type: String,
    ) = MetadataService.FieldCmd(apiName = api, label = api, logicalType = type)

    @Test
    fun `合法边物理化 NUMBER 落真列并翻 storage_kind 非法边 MULTISELECT 拒绝`() {
        val custApi = name("tcust")
        val obj =
            meta.createObject(
                MetadataService.ObjectCreateCmd(
                    apiName = custApi,
                    label = "客户",
                    kind = "PLAIN",
                    displayName = "{name}",
                    fields = listOf(fieldCmd("name", "TEXT"), fieldCmd("amount", "NUMBER"), fieldCmd("attachment", "FILE")),
                ),
            )
        assertThat(await { columnExists(custApi, "ext") }).`as`("客户表应物化（ext 列在）").isTrue()
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("初始建表步应终态").isTrue()

        // 合法边：NUMBER 标量物理化。
        val amount = repo.findFieldByApi(obj.id, "amount")!!
        assertThat(amount.storageKind).`as`("物理化前应为 EXT").isEqualTo(StorageKind.EXT)
        val r = physicalize.physicalize(amount.id)
        assertThat(r.targetStorageKind).isEqualTo(StorageKind.COLUMN.name)
        assertThat(r.jobState).isEqualTo("PENDING")
        assertThat(r.consequence).contains("不丢数据")

        // 异步执行器：加列→回填→建表达式索引→读切换→清 key，最终 amount 成真列且 storage_kind=COLUMN。
        assertThat(await { columnExists(custApi, "amount") }).`as`("物理化应落 amount 真列；诊断=%s", jobRepo.listByObject(obj.id)).isTrue()
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("物理化五步应全终态").isTrue()
        assertThat(repo.findFieldById(amount.id)?.storageKind).`as`("读切换应翻 storage_kind→COLUMN").isEqualTo(StorageKind.COLUMN)

        // 非法边：FILE 不可物理化 → 抛错、不入队。
        val attachment = repo.findFieldByApi(obj.id, "attachment")!!
        val stepsBefore = jobRepo.listByObject(obj.id).size
        assertThatThrownBy { physicalize.physicalize(attachment.id) }
            .isInstanceOf(KnownKteasyException::class.java)
        assertThat(jobRepo.listByObject(obj.id).size).`as`("非法边不得入队").isEqualTo(stepsBefore)
    }
}
