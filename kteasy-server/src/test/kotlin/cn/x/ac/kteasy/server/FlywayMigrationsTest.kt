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
package cn.x.ac.kteasy.server

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.sql.DatabaseMetaData
import java.sql.Types
import javax.sql.DataSource

/**
 * 验收①：两 Profile 各自对空库起服，Flyway 迁移出 V1/V2，且 schema_change_job 的结构
 * 在两库间「列名一致 + 每列落在预期 JDBC 类型族内」——pg 与 mysql 两 job 各按本方言断言，二者同绿即证等价。
 *
 * 用标准化的 `java.sql.Types` 判定族而非物理类型名（`int8`/`bigint`/`json`/`jsonb` 拼写跨驱动不稳，
 * 上一轮 CI 就是栽在字面量上）；JSON 列允许 OTHER/LONGVARCHAR 一族，时间列允许带/不带时区。
 * 本机无库，整体由 `KTEASY_IT_DB=true` 门控（CI service-matrix 才跑），起服即真迁移。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class FlywayMigrationsTest {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var flyway: Flyway

    @Autowired
    lateinit var context: KteasyContext

    @Test
    fun `空库起服后 V1 与 V2 均迁移成功`() {
        val applied =
            flyway.info().applied().associate { (it.version?.version ?: "<n/a>") to it.state.name }
        assertThat(applied["1"]).isEqualTo("SUCCESS")
        assertThat(applied["2"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `schema_change_job 列名一致且每列落在预期类型族`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        // 压扁后 V2 直接建 md_schema_change_job（PG 落 md schema、MySQL 平铺），与 md_object 同命名约定。
        val table = "md_schema_change_job"
        val schema = if (pg) "md" else null
        // 每列允许的 java.sql.Types 值集合（方言相关，但用族而非字面量，容错驱动差异）。
        val intFam = setOf(Types.INTEGER, Types.BIGINT, Types.SMALLINT)
        val textFam = setOf(Types.VARCHAR, Types.NVARCHAR, Types.CHAR)
        val jsonFam = setOf(Types.OTHER, Types.LONGVARCHAR, Types.VARCHAR, Types.SQLXML)
        val tsFam = setOf(Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE)
        val allowed: Map<String, Set<Int>> =
            linkedMapOf(
                "id" to intFam,
                "object_id" to textFam,
                "step_kind" to textFam,
                "seq" to intFam,
                "state" to textFam,
                "attempts" to intFam,
                "checkpoint_json" to jsonFam,
                "updated_at" to tsFam,
            )
        val actual = columns(table, schema)

        assertThat(actual.keys).containsExactlyInAnyOrderElementsOf(allowed.keys)
        allowed.forEach { (col, fam) ->
            assertThat(actual[col])
                .`as`("列 %s 的 DATA_TYPE", col)
                .isIn(fam)
        }
    }

    @Test
    fun `kteasy_meta 三列存在（引擎元信息）`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        val schema = if (pg) "kteasy" else null
        assertThat(columns("kteasy_meta", schema).keys)
            .containsExactlyInAnyOrder("id", "engine_version", "initialized_at")
    }

    /** 压扁收尾验收：框架表命名带 md_、id 与引用列均钉 binary、非标识符列不吃（MySQL 反证）。 */
    @Test
    fun `压扁后框架表命名带 md_ 且 id 引用列钉 binary 非id列不吃`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        val bin = if (pg) "C" else "utf8mb4_bin"
        listOf("md_object", "md_field", "md_dict", "md_dict_item", "md_option_set", "md_option", "md_schema_change_job").forEach {
            assertThat(existsTable(it)).`as`("框架表 %s 应存在（md_ 命名）", it).isTrue()
        }
        listOf(
            "md_object" to "id",
            "md_object" to "parent_object_id",
            "md_object" to "created_by",
            "md_object" to "updated_by",
            "md_field" to "id",
            "md_field" to "object_id",
            "md_field" to "ref_object_id",
            "md_field" to "dict_id",
            "md_field" to "option_set_id",
            "md_dict" to "id",
            "md_dict_item" to "dict_id",
            "md_dict_item" to "parent_id",
            "md_option_set" to "id",
            "md_option" to "set_id",
            "md_schema_change_job" to "object_id",
        ).forEach { (t, c) ->
            assertThat(collationOf(t, c)).`as`("%s.%s 应为 binary", t, c).isEqualTo(bin)
        }
        // 非标识符列：MySQL 反证仍吃表默认 ai_ci；PG 默认随实例（可能也是 C）不可反证，故跳过。
        if (!pg) {
            assertThat(collationOf("md_object", "api_name")).`as`("api_name 非 id 列保持表默认").isEqualTo("utf8mb4_0900_ai_ci")
            assertThat(collationOf("md_field", "label")).isEqualTo("utf8mb4_0900_ai_ci")
        }
    }

    /** M1-07 编号表验收：V4 迁移成功；md_autonum_rule 落 md 区、kteasy_autonum_seq 落引擎 kteasy 区，id/引用列钉 binary。 */
    @Test
    fun `M1-07 编号表迁移成功且命名 binary 落区正确`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        val bin = if (pg) "C" else "utf8mb4_bin"
        val applied = flyway.info().applied().associate { (it.version?.version ?: "<n/a>") to it.state.name }
        assertThat(applied["4"]).`as`("V4 迁移成功").isEqualTo("SUCCESS")

        // md_autonum_rule：md 区、id/object_id/field_id 钉 binary、segments_json 属 JSON 族
        assertThat(existsIn("md", "md_autonum_rule")).`as`("md.md_autonum_rule 应存在").isTrue()
        listOf("id", "object_id", "field_id").forEach { c ->
            assertThat(collationIn("md", "md_autonum_rule", c)).`as`("md_autonum_rule.$c 应为 binary").isEqualTo(bin)
        }
        val ruleCols = columns("md_autonum_rule", if (pg) "md" else null)
        assertThat(ruleCols.keys).containsExactlyInAnyOrder("id", "object_id", "field_id", "segments_json", "created_at", "updated_at")
        assertThat(ruleCols["segments_json"]).`as`("segments_json 属 JSON 族").isIn(setOf(Types.OTHER, Types.LONGVARCHAR, Types.VARCHAR, Types.SQLXML))

        // kteasy_autonum_seq：引擎 kteasy 区（同 kteasy_meta）、rule_id 钉 binary、period_key 非标识符列不吃（MySQL 反证）
        assertThat(existsIn("kteasy", "kteasy_autonum_seq")).`as`("kteasy.kteasy_autonum_seq 应存在（引擎区）").isTrue()
        assertThat(collationIn("kteasy", "kteasy_autonum_seq", "rule_id")).`as`("rule_id 应为 binary").isEqualTo(bin)
        val seqCols = columns("kteasy_autonum_seq", if (pg) "kteasy" else null)
        assertThat(seqCols.keys).containsExactlyInAnyOrder("rule_id", "period_key", "seq_value", "updated_at")
        assertThat(seqCols["seq_value"]).`as`("seq_value 为 BIGINT").isEqualTo(Types.BIGINT)
        if (!pg) {
            assertThat(collationIn("kteasy", "kteasy_autonum_seq", "period_key")).`as`("period_key 非 id 列保持表默认").isEqualTo("utf8mb4_0900_ai_ci")
        }
    }

    /** 块4 md_dep：V5 迁移成功；md 区、四标识符列钉 binary、filter_json 属 JSON 族、列集冻结。 */
    @Test
    fun `M1-07 块4 md_dep 建表 binary 落区正确`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        val bin = if (pg) "C" else "utf8mb4_bin"
        val applied = flyway.info().applied().associate { (it.version?.version ?: "?") to it.state.name }
        assertThat(applied["5"]).`as`("V5 迁移成功").isEqualTo("SUCCESS")
        assertThat(existsIn("md", "md_dep")).`as`("md.md_dep 应存在").isTrue()
        listOf("id", "target_field_id", "source_object_id", "source_field_id").forEach { c ->
            assertThat(collationIn("md", "md_dep", c)).`as`("md_dep.$c binary").isEqualTo(bin)
        }
        val cols = columns("md_dep", if (pg) "md" else null)
        assertThat(cols.keys).containsExactlyInAnyOrder("id", "target_field_id", "source_object_id", "source_field_id", "op", "filter_json", "created_at", "updated_at")
        assertThat(cols["filter_json"]).`as`("filter_json 属 JSON 族").isIn(setOf(Types.OTHER, Types.LONGVARCHAR, Types.VARCHAR, Types.SQLXML))
    }

    /** 压扁后终态 schema dump（供评审；仅当设了 KTEASY_SCHEMA_DUMP 环境变量才落盘，走 fork 继承的 env 而非 -D）。 */
    @Test
    fun `压扁后终态 schema dump`() {
        val path = System.getenv("KTEASY_SCHEMA_DUMP") ?: return
        val pg = context.dialect == Dialect.POSTGRESQL
        val sb = StringBuilder()
        sb.appendLine("# Kteasy 框架表终态 schema（migrate 后由 information_schema 生成，dialect=${context.dialect.profile}）")
        sb.appendLine()
        val targets =
            listOf(
                "kteasy_meta" to if (pg) "kteasy" else null,
                "md_schema_change_job" to if (pg) "md" else null,
                "md_object" to if (pg) "md" else null,
                "md_field" to if (pg) "md" else null,
                "md_dict" to if (pg) "md" else null,
                "md_dict_item" to if (pg) "md" else null,
                "md_option_set" to if (pg) "md" else null,
                "md_option" to if (pg) "md" else null,
            )
        dataSource.connection.use { conn ->
            targets.forEach { (table, schema) ->
                sb.appendLine("## $table")
                val sql =
                    if (pg) {
                        "SELECT column_name, data_type, character_maximum_length, is_nullable, collation_name " +
                            "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position"
                    } else {
                        "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLLATION_NAME " +
                            "FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ORDINAL_POSITION"
                    }
                conn.prepareStatement(sql).use { ps ->
                    var i = 1
                    if (pg) ps.setString(i++, schema!!)
                    ps.setString(i, table)
                    ps.executeQuery().use { rs ->
                        sb.appendLine("column | type(len) | null | collation")
                        while (rs.next()) {
                            val len = rs.getObject("character_maximum_length") as? Int
                            val type = rs.getString("data_type") + (len?.let { "($it)" } ?: "")
                            val coll = rs.getString("collation_name") ?: rs.getString(5) ?: "-"
                            sb.appendLine("- ${rs.getString(1)} | $type | ${rs.getString(4)} | $coll")
                        }
                    }
                }
                sb.appendLine()
            }
        }
        java.io.File(path).writeText(sb.toString())
        println("[FlywayMigrationsTest] 终态 schema dump 写入 $path")
    }

    private fun existsTable(name: String): Boolean {
        val pg = context.dialect == Dialect.POSTGRESQL
        val sql =
            if (pg) {
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'md' AND table_name = ?"
            } else {
                "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?"
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun collationOf(
        table: String,
        column: String,
    ): String? {
        val pg = context.dialect == Dialect.POSTGRESQL
        val sql =
            if (pg) {
                "SELECT collation_name FROM information_schema.columns WHERE table_schema = 'md' AND table_name = ? AND column_name = ?"
            } else {
                "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?"
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, table)
                ps.setString(2, column)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    /** schema 参数化存在性判定（M1-07 编号表跨 md 区与引擎 kteasy 区，故不能沿用硬编码 md 的 existsTable）。 */
    private fun existsIn(
        pgSchema: String,
        name: String,
    ): Boolean {
        val pg = context.dialect == Dialect.POSTGRESQL
        val sql =
            if (pg) {
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?"
            } else {
                "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?"
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                if (pg) ps.setString(i++, pgSchema)
                ps.setString(i, name)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    /** schema 参数化列 collation 判定（同 [existsIn]，覆盖非 md 区）。 */
    private fun collationIn(
        pgSchema: String,
        table: String,
        column: String,
    ): String? {
        val pg = context.dialect == Dialect.POSTGRESQL
        val sql =
            if (pg) {
                "SELECT collation_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?"
            } else {
                "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?"
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                if (pg) ps.setString(i++, pgSchema)
                ps.setString(i++, table)
                ps.setString(i, column)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    /** 读表的 列名 -> java.sql.Types 的 DATA_TYPE（DatabaseMetaData），避免手写裸 SQL 与依赖物理类型名拼写。 */
    private fun columns(
        table: String,
        schema: String?,
    ): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        dataSource.connection.use { conn ->
            val meta: DatabaseMetaData = conn.metaData
            meta.getColumns(conn.catalog, schema, table, "%").use { rs ->
                while (rs.next()) {
                    out[rs.getString("COLUMN_NAME")] = rs.getInt("DATA_TYPE")
                }
            }
        }
        return out
    }
}
