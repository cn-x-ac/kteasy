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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource

/**
 * 验收①前置：V3 六张 md 表在两库各自迁移成功，且结构「列名集合一致」。
 * 类型等价性由 Flyway 脚本按方言成对维护（jsonb↔json、timestamptz↔DATETIME(6)），
 * 断言用列名集合（方言无关），沿用 M0-03 §E23 的教训不用物理类型名字面量。
 * 本机无库 skipped，CI service-matrix 真跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class MetadataSchemaTest {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var context: KteasyContext

    private val dialect: String get() = if (context.dialect == Dialect.POSTGRESQL) "pg" else "mysql"

    @Autowired
    lateinit var flyway: org.flywaydb.core.Flyway

    @Test
    fun `V3 迁移成功`() {
        val applied =
            flyway.info().applied().associate { (it.version?.version ?: "<n/a>") to it.state.name }
        assertThat(applied["1"]).isEqualTo("SUCCESS")
        assertThat(applied["2"]).isEqualTo("SUCCESS")
        assertThat(applied["3"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `六张 md 表列名集合符合图纸 01 §1`() {
        val expected =
            linkedMapOf(
                "md_object" to
                    setOf(
                        "id",
                        "api_name",
                        "label",
                        "kind",
                        "parent_object_id",
                        "display_name",
                        "quick_search_json",
                        "status",
                        "disabled",
                        "created_at",
                        "created_by",
                        "updated_at",
                        "updated_by",
                    ),
                "md_field" to
                    setOf(
                        "id",
                        "object_id",
                        "api_name",
                        "label",
                        "logical_type",
                        "storage_kind",
                        "required",
                        "default_json",
                        "validation_json",
                        "ui_json",
                        "ref_object_id",
                        "ref_any_objs_json",
                        "dict_id",
                        "option_set_id",
                        "seq",
                        "enabled",
                        // M1-06：服务端硬只读的元数据位（一列档位 + 一列必填作用域）
                        "write_policy",
                        "required_scope",
                        "created_at",
                        "updated_at",
                    ),
                "md_dict" to setOf("id", "name", "created_at"),
                "md_dict_item" to setOf("id", "dict_id", "parent_id", "path", "p_label", "seq", "enabled"),
                "md_option_set" to setOf("id", "name", "closed", "created_at"),
                "md_option" to setOf("id", "set_id", "code", "label", "seq", "enabled"),
            )
        expected.forEach { (logical, columns) ->
            val actual = MdTestSupport.columns(dataSource, dialect, logical).keys
            assertThat(actual)
                .`as`("表 %s（%s）", logical, dialect)
                .containsExactlyInAnyOrderElementsOf(columns)
        }
    }
}
