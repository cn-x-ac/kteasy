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
import javax.sql.DataSource

/**
 * 验收①：两 Profile 各自对空库起服，Flyway 迁移出 V1/V2，且 schema_change_job 的
 * 列名与物理类型经「映射清单」比对——pg 与 mysql 两 job 各按本方言断言，二者同时绿即证结构等价。
 *
 * 本机无库，整体由 `KTEASY_IT_DB=true` 门控（CI service-matrix 才跑）。上下文用默认 Flyway（启用），
 * 起服即真迁移，本测同时确证「迁移在启动期成功执行」。
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
        val applied = flyway.info().applied().associate { it.version.version to it.state.name }
        assertThat(applied.keys).contains("1", "2")
        assertThat(applied["1"]).isEqualTo("SUCCESS")
        assertThat(applied["2"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `schema_change_job 列名与物理类型符合本方言映射清单`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        val table = if (pg) "schema_change_job" else "md_schema_change_job"
        val schema = if (pg) "md" else null
        val expected =
            if (pg) {
                linkedMapOf(
                    "id" to "int8",
                    "object_id" to "varchar",
                    "step_kind" to "varchar",
                    "seq" to "int4",
                    "state" to "varchar",
                    "attempts" to "int4",
                    "checkpoint_json" to "jsonb",
                    "updated_at" to "timestamptz",
                )
            } else {
                linkedMapOf(
                    "id" to "bigint",
                    "object_id" to "varchar",
                    "step_kind" to "varchar",
                    "seq" to "int",
                    "state" to "varchar",
                    "attempts" to "int",
                    "checkpoint_json" to "json",
                    "updated_at" to "datetime",
                )
            }
        assertThat(columns(table, schema)).isEqualTo(expected)
    }

    @Test
    fun `kteasy_meta 三列存在（引擎元信息）`() {
        val pg = context.dialect == Dialect.POSTGRESQL
        val schema = if (pg) "kteasy" else null
        assertThat(columns("kteasy_meta", schema).keys)
            .containsExactlyInAnyOrder("id", "engine_version", "initialized_at")
    }

    /** 读表的 列名 -> 物理类型名（DatabaseMetaData），避免手写裸 SQL。 */
    private fun columns(
        table: String,
        schema: String?,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        dataSource.connection.use { conn ->
            val meta: DatabaseMetaData = conn.metaData
            meta.getColumns(conn.catalog, schema, table, "%").use { rs ->
                while (rs.next()) {
                    out[rs.getString("COLUMN_NAME")] = rs.getString("TYPE_NAME")
                }
            }
        }
        return out
    }
}
