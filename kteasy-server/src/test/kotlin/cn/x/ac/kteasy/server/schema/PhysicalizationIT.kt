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
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
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
 * 步骤卡 M1-03 Block E：物化引擎**物理化四步**与**对象删除**的双库端到端（真连 PG/MySQL）。
 *
 * 覆盖能力层落地后的可观测结果：把一个已存 ext 的标量字段提为独立可写真列——加列→回填→清 ext key→翻
 * storage_kind，全程**不丢数**、幂等重跑收敛到同一终态；`disabled` 对象触发 DROP_TABLE 删表。
 * 50w/kill -9/INSTANT 等重 GWT 归 Block F，本类只证机制正确（KTEASY_IT_DB 门控真连，禁 H2/mock）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class PhysicalizationIT {
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
    private val apiName = "m03e$suffix"

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun tableExists(logical: String): Boolean {
        val f = provider.introspection.tableExists(LogicalArea.ENTITY, logical)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun columnHas(
        logical: String,
        column: String,
    ): Boolean {
        val f = provider.introspection.columnExists(LogicalArea.ENTITY, logical, column)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(
        timeoutMs: Long = 40_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    /** 用 SchemaProvider 的 JSON 写入占位符插一行（ext 存 {"amount":<v>}），两库各自成型。 */
    private fun insertExtRow(
        id: String,
        amount: Long,
    ) {
        val t = qual(LogicalArea.ENTITY, apiName)
        jdbc().update(
            "INSERT INTO $t (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            mapOf("id" to id, "ext" to "{\"amount\":$amount}"),
        )
    }

    private fun amountColumn(id: String): Long? = jdbc().queryForList("SELECT amount FROM ${qual(LogicalArea.ENTITY, apiName)} WHERE id = :id", mapOf("id" to id), Long::class.java).firstOrNull()

    private fun extHasAmount(id: String): Boolean {
        val frag = provider.json.predicateExists("ext", JsonPath.of("amount"))
        val n =
            jdbc().queryForObject(
                "SELECT COUNT(*) FROM ${qual(LogicalArea.ENTITY, apiName)} WHERE id = :id AND ${frag.sql}",
                mapOf("id" to id),
                Long::class.java,
            ) ?: 0L
        return n > 0
    }

    @AfterEach
    fun cleanup() {
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
    fun `把 ext 标量提为真列 加列回填清key翻storage 不丢数且幂等重跑收敛`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = apiName,
                label = "订单",
                kind = "PLAIN",
                displayName = "{amount}",
                fields = listOf(MetadataService.FieldCmd(apiName = "amount", label = "金额", logicalType = "DECIMAL")),
            ),
        )
        assertThat(await { tableExists(apiName) }).`as`("先物化出对象表").isTrue()
        val obj = meta.buildGraph(meta.loadSnapshot(), apiName).objectMeta
        val amountField = meta.loadSnapshot().fields.first { it.objectId == obj.id && it.apiName == "amount" }
        assertThat(amountField.storageKind).`as`("标量初始存 ext").isEqualTo(StorageKind.EXT)

        // 塞历史数据到 ext（模拟既有值）。
        val ids = (1..50).map { Ulid.next() }
        ids.forEachIndexed { i, id -> insertExtRow(id, (i + 1) * 100L) }
        assertThat(columnHas(apiName, "amount")).`as`("物理化前 amount 只是 ext 键、无真列").isFalse()

        // 提交物理化作业：amount 由 ext 提为可写 BIGINT 真列（档二无损迁移四步；表达式索引属档一，MySQL 函数索引为降级能力，另测）。
        val plan =
            SchemaDiff.planPhysicalize(
                objectId = obj.id,
                hostTable = apiName,
                fieldId = amountField.id,
                fieldApi = "amount",
                column = PhysicalColumn("amount", ColumnType.BIGINT),
                cast = ValueCast.LONG,
                makeIndex = false,
            )
        executor.submitPhysicalization(obj.id, plan)

        val diag = { jobRepo.listByObject(obj.id).joinToString(",") { "${it.stepKind}:${it.state}" } }
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("物理化四步应全部到终态；ledger=%s", diag()).isTrue()

        assertThat(columnHas(apiName, "amount")).isTrue()
        ids.forEachIndexed { i, id ->
            assertThat(amountColumn(id)).`as`("第${i}行不丢数：列值=原 ext amount").isEqualTo((i + 1) * 100L)
        }
        assertThat(extHasAmount(ids.first())).`as`("清 ext key 后旧键不再在 ext").isFalse()
        assertThat(
            meta
                .loadSnapshot()
                .fields
                .first { it.id == amountField.id }
                .storageKind,
        ).`as`("SWITCH_READ 翻转读判据")
            .isEqualTo(StorageKind.COLUMN)

        // 幂等重跑（模拟崩溃后从 checkpoint 续）：再提交同一计划，值不变、终态仍全 DONE。
        executor.submitPhysicalization(obj.id, plan)
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("重跑应收敛到 DONE").isTrue()
        assertThat(amountColumn(ids.first())).isEqualTo(100L)
    }

    @Test
    fun `对象标记删除后 DROP_TABLE 删物理表`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = apiName,
                label = "待删",
                kind = "PLAIN",
                displayName = "{name}",
                fields = listOf(MetadataService.FieldCmd(apiName = "name", label = "名", logicalType = "TEXT")),
            ),
        )
        assertThat(await { tableExists(apiName) }).isTrue()
        val obj = meta.buildGraph(meta.loadSnapshot(), apiName).objectMeta

        meta.disableObject(apiName)
        executor.submit(obj.id) // 显式入队，触发重算 diff → disabled 且表存在 → DROP_TABLE。
        assertThat(await { !tableExists(apiName) }).`as`("disabled 对象应 DROP_TABLE").isTrue()
    }
}
