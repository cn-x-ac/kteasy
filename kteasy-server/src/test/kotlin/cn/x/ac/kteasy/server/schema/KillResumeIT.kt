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
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.core.schema.StepKind
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import cn.x.ac.kteasy.dev.seed.SeedRunner
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
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
 * 步骤卡 M1-03 Block F · GWT#3（BACKFILL 进行到一半「进程死亡」→ 重启从 checkpoint 续、终态行数=全量、无重复计）。
 *
 * 用执行器的 `haltBackfillAfterForTest` 钩子在回填若干批后抛错（`last_id` 已落 checkpoint），模拟进程在途死亡
 * ——这是 `Runtime.halt` 子进程 kill -9 的**确定性等价**：走的是同一「账本 RUNNING/FAILED 行 + checkpoint last_id +
 * 重启重放入队续跑」机制，但不引入子进程 flakiness、不打满共享库。随后清钩子并 `submit`（对应 @PostConstruct 孤儿扫描
 * 重新入队）→ 从 last_id 续跑到终态。断言全量行迁移、ext 键清零、无重复、值正确。双库真连。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_PERF", matches = "true")
class KillResumeIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var executor: SchemaJobExecutor

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val apiName = "m03kill$suffix"
    private val dialect get() = if (context.dialect == Dialect.POSTGRESQL) "pg" else "mysql"

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun await(
        timeoutMs: Long = 60_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(150)
        }
        return poll()
    }

    private fun migratedCount(): Long = jdbc().queryForObject("SELECT COUNT(*) FROM ${qual(LogicalArea.ENTITY, apiName)} WHERE amount IS NOT NULL", emptyMap<String, Any>(), Long::class.java) ?: 0L

    private fun extKeyRemaining(): Long {
        val f = provider.json.predicateExists("ext", JsonPath.of("amount"))
        return jdbc().queryForObject("SELECT COUNT(*) FROM ${qual(LogicalArea.ENTITY, apiName)} WHERE ${f.sql}", emptyMap<String, Any>(), Long::class.java) ?: 0L
    }

    @AfterEach
    fun cleanup() {
        executor.haltBackfillAfterForTest = Int.MAX_VALUE
        executor.faultForTest = null
        val jt = JdbcTemplate(dataSource)
        runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.ENTITY, apiName)} CASCADE") }
        val like = "%$suffix%"
        val objQual = qual(LogicalArea.METADATA, "md_object")
        dataSource.connection.use { conn ->
            conn
                .prepareStatement("DELETE FROM ${qual(LogicalArea.METADATA, "md_field")} WHERE object_id IN (SELECT id FROM $objQual WHERE api_name LIKE ?)")
                .use { ps ->
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
    fun `回填中途进程死亡 从 checkpoint last_id 续跑至终态不丢不重`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = apiName,
                label = "结算流水",
                kind = "PLAIN",
                displayName = "{amount}",
                fields = listOf(MetadataService.FieldCmd(apiName = "amount", label = "金额", logicalType = "DECIMAL")),
            ),
        )
        val te = provider.introspection.tableExists(LogicalArea.ENTITY, apiName)
        assertThat(await { (jdbc().queryForObject(te.sql, MapSqlParameterSource(te.params), Long::class.java) ?: 0L) > 0 }).isTrue()
        val obj = meta.buildGraph(meta.loadSnapshot(), apiName).objectMeta
        val fieldId =
            meta
                .loadSnapshot()
                .fields
                .first { it.objectId == obj.id && it.apiName == "amount" }
                .id

        SeedRunner.seed(jdbc(), qual(LogicalArea.ENTITY, apiName), dialect, 20_000, "amount")

        // 模拟进程在回填途中死亡：每跑 2 批抛一次（last_id 已落 checkpoint）。BACKFILL 步重试 3 次 → 挂起。
        executor.haltBackfillAfterForTest = 2
        val plan = SchemaDiff.planPhysicalize(obj.id, apiName, fieldId, "amount", PhysicalColumn("amount", ColumnType.BIGINT), ValueCast.LONG, makeIndex = false)
        executor.submitPhysicalization(obj.id, plan)

        val backfill = { jobRepo.listByObject(obj.id).firstOrNull { it.stepKind == StepKind.BACKFILL_BATCH } }
        assertThat(await { backfill()?.state == "FAILED" }).`as`("回填应在多次续跑后仍因钩子抛错而挂起 FAILED").isTrue()
        val ck = backfill()?.checkpointJson ?: ""
        assertThat(ck).`as`("checkpoint 应已记录推进的 last_id").contains("lastId")
        val partial = migratedCount()
        assertThat(partial).`as`("挂起时应为部分迁移（0 < migrated < 20000）").isGreaterThan(0L).isLessThan(20_000L)
        assertThat(jobRepo.listByObject(obj.id).first { it.stepKind == StepKind.CLEAN_EXT_KEY }.state).isEqualTo("PENDING")

        // 「重启」：清钩子后重新入队（对应 @PostConstruct 孤儿扫描）→ 从 last_id 续跑到终态。
        executor.haltBackfillAfterForTest = Int.MAX_VALUE
        executor.submit(obj.id)
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("续跑应把所有步到 DONE").isTrue()

        assertThat(migratedCount()).`as`("终态全量迁移、不丢数").isEqualTo(20_000L)
        assertThat(extKeyRemaining()).`as`("ext 旧键全清").isEqualTo(0L)
        assertThat(
            meta
                .loadSnapshot()
                .fields
                .first { it.id == fieldId }
                .storageKind.name,
        ).isEqualTo("COLUMN")
    }
}
