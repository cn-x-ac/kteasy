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
        val table = if (pg) "schema_change_job" else "md_schema_change_job"
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
