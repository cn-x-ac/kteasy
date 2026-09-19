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

import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
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
 * 物化引擎端到端集成（步骤卡 M1-03 验收，双库真连）。A+B+C 一起验：
 * 建对象（含 REF+标量+N2N）经 AFTER_COMMIT 事件→异步执行器把物理表/外键/r_ 关联表落库；
 * 加标量字段→**零新步**（【规格】§5.2 硬验收）；加引用字段→ADD_FK_COLUMN 落地。
 *
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`；测试区 SQL 字面量豁免红线⑤。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class MaterializationIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val created = mutableListOf<String>()

    private fun name(base: String) = "${base}_$suffix".also { created += it }

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun exists(
        area: LogicalArea,
        logical: String,
    ): Boolean {
        val frag = provider.introspection.tableExists(area, logical)
        val c = jdbc().queryForObject(frag.sql, MapSqlParameterSource(frag.params), Long::class.java) ?: 0L
        return c > 0
    }

    private fun columnNames(
        area: LogicalArea,
        logical: String,
    ): Set<String> {
        val frag = provider.introspection.listColumns(area, logical)
        return jdbc().queryForList(frag.sql, MapSqlParameterSource(frag.params), String::class.java).filterNotNull().toSet()
    }

    private val pg get() = context.dialect.profile == "pg"

    /** 某列当前的排序规则名（GWT 第 22 行：验证 id/引用列钉了 binary）。测试区直查信息模式，豁免红线⑤。 */
    private fun collationOf(
        area: LogicalArea,
        logical: String,
        column: String,
    ): String? =
        if (pg) {
            jdbc()
                .queryForList(
                    "SELECT collation_name FROM information_schema.columns WHERE table_schema = :s AND table_name = :t AND column_name = :c",
                    mapOf("s" to area.pgSchema, "t" to area.pgPrefix + logical, "c" to column),
                    String::class.java,
                ).firstOrNull()
        } else {
            jdbc()
                .queryForList(
                    "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = :t AND column_name = :c",
                    mapOf("t" to area.mysqlPrefix + logical, "c" to column),
                    String::class.java,
                ).firstOrNull()
        }

    /** 物理限定名（PG 表名带 schema 前缀 `app.x`，MySQL 平铺 `e_x`），供测试区手拼 JOIN。 */
    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    /** 轮询直到条件成立或超时（执行器异步，不能同步断言）。 */
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

    @AfterEach
    fun cleanup() {
        // 逆序删测试表（关联/子先删，解除外键），直连测试区允许字面量。
        val jt = JdbcTemplate(dataSource)
        created.reversed().forEach { logical ->
            runCatching {
                val table = provider.namespace.qualified(LogicalArea.ENTITY, logical)
                jt.execute("DROP TABLE IF EXISTS $table CASCADE")
            }
        }
        runCatching { jt.execute("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, "cust_$suffix")}") }
    }

    private fun fieldCmd(
        api: String,
        type: String,
        ref: String? = null,
    ): MetadataService.FieldCmd = MetadataService.FieldCmd(apiName = api, label = api, logicalType = type, refObjectApi = ref)

    @Test
    fun `建对象把物理表与N2N关联表落地 加标量零步 加引用补列`() {
        // 先建被引用对象 contract。
        meta.createObject(MetadataService.ObjectCreateCmd(apiName = name("contract"), label = "合同", kind = "PLAIN", displayName = "{code}", fields = listOf(fieldCmd("code", "TEXT"))))
        val contractApi = created.last()

        // 建 customer：标量 name(EXT) + 引用 owner_ref(REF→contract) + 多引用 tags(N2N→contract)。
        val custApi = name("cust")
        val obj =
            meta.createObject(
                MetadataService.ObjectCreateCmd(
                    apiName = custApi,
                    label = "客户",
                    kind = "PLAIN",
                    displayName = "{name}",
                    fields = listOf(fieldCmd("name", "TEXT"), fieldCmd("owner_ref", "REF", contractApi), fieldCmd("tags", "N2N", contractApi)),
                ),
            )

        // 异步执行器应落 customer 表 + r_ 关联表。
        assertThat(await { exists(LogicalArea.ENTITY, custApi) })
            .`as`("customer 物理表应物化；诊断 contractExists=%s rows=%s", exists(LogicalArea.ENTITY, contractApi), jobRepo.listByObject(obj.id))
            .isTrue()
        assertThat(await { exists(LogicalArea.RELATION, "${custApi}_tags") }).`as`("N2N 关联表应物化").isTrue()

        val cols = columnNames(LogicalArea.ENTITY, custApi)
        assertThat(cols).contains("id", "owner_user", "ext", "owner_ref") // 引用列真列化
        assertThat(cols).doesNotContain("name") // 标量走 ext，零列（DDL 证明）
        created += "${custApi}_tags"

        // 等对象的所有步到终态（无未完成）。
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("初始物化步应全部终态").isTrue()
        val stepCountAfterCreate = jobRepo.listByObject(obj.id).size

        // GWT① 加标量字段 → 零新步。
        meta.createField(custApi, fieldCmd("nickname", "TEXT"))
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).isTrue()
        TimeUnit.MILLISECONDS.sleep(500)
        assertThat(jobRepo.listByObject(obj.id).size).`as`("加标量字段不得新增任何步").isEqualTo(stepCountAfterCreate)

        // GWT② 加引用字段 → ADD_FK_COLUMN 落地。
        meta.createField(custApi, fieldCmd("owner2_ref", "REF", contractApi))
        assertThat(await { "owner2_ref" in columnNames(LogicalArea.ENTITY, custApi) })
            .`as`("新引用字段应补真列；诊断 rows=%s", jobRepo.listByObject(obj.id))
            .isTrue()
        assertThat(jobRepo.listByObject(obj.id).any { it.stepKind.name == "ADD_FK_COLUMN" }).isTrue()

        // GWT 第 22 行：id 及全部引用列钉 binary（实体表 + r_ 双方 + 增量加的引用列）。
        val bin = if (pg) "C" else "utf8mb4_bin"
        assertThat(collationOf(LogicalArea.ENTITY, custApi, "id")).`as`("实体表 id 钉 binary").isEqualTo(bin)
        assertThat(collationOf(LogicalArea.ENTITY, custApi, "owner_ref")).isEqualTo(bin)
        assertThat(collationOf(LogicalArea.ENTITY, custApi, "owner2_ref")).`as`("增量加引用列也钉 binary").isEqualTo(bin)
        assertThat(collationOf(LogicalArea.RELATION, "${custApi}_tags", "src_${custApi}_id")).`as`("r_ 双方列钉 binary").isEqualTo(bin)
        if (!pg) {
            // 反证非 id 列不吃默认之外的规则（MySQL 默认 ai_ci，PG 默认随实例、不可反证故跳过）。
            assertThat(collationOf(LogicalArea.ENTITY, custApi, "approval_state")).`as`("非标识符列保持表默认").isEqualTo("utf8mb4_0900_ai_ci")
        }

        // JOIN/FK 两端 collation 一致 → 不出现 Illegal mix of collations（MySQL 会直接抛）。
        // 对象表 ⋈ 其 r_ 关联表；以及 md_object ⋈ md_field（验证 V6 对 md 区的回补）。
        jdbc().queryForList(
            "SELECT count(*) AS c FROM ${qual(LogicalArea.ENTITY, custApi)} o JOIN ${qual(LogicalArea.RELATION, "${custApi}_tags")} r ON o.id = r.src_${custApi}_id",
            emptyMap<String, Any>(),
        )
        jdbc().queryForList(
            "SELECT count(*) AS c FROM ${qual(LogicalArea.METADATA, "md_object")} o JOIN ${qual(LogicalArea.METADATA, "md_field")} f ON o.id = f.object_id",
            emptyMap<String, Any>(),
        )
        // V7 补全：作业账本 object_id（存 md_object.id）亦钉 binary，与 md_object.id 联查不混。
        assertThat(collationOf(LogicalArea.METADATA, "md_schema_change_job", "object_id")).`as`("账本 object_id 钉 binary").isEqualTo(bin)
        jdbc().queryForList(
            "SELECT count(*) AS c FROM ${qual(LogicalArea.METADATA, "md_schema_change_job")} j JOIN ${qual(LogicalArea.METADATA, "md_object")} o ON j.object_id = o.id",
            emptyMap<String, Any>(),
        )
    }
}
