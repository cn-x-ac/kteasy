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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource

/**
 * 里程碑收尾迁移压扁（squash）用的**清库工具**：把测试/DEV 库回退到空库，好让改写后的 CREATE 链
 * （checksum 变了、Flyway validate 对已应用旧链会红）从零重跑。长期默认——每个里程碑收尾走一次。
 *
 * 关闭 Flyway（只清不迁），门控 `KTEASY_DB_CLEAN=true` 且需连库，正常 CI/本地测试永不触发。
 * PG：drop 引擎私有 schema（`md`/`app`/`kteasy`）CASCADE；`public` 不碰。
 * MySQL：无 schema 概念，关外键后 drop 当前库内全部表（含 flyway 历史表），开回外键。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.flyway.enabled=false"],
)
@EnabledIfEnvironmentVariable(named = "KTEASY_DB_CLEAN", matches = "true")
class DbCleanTest {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var context: KteasyContext

    @Test
    fun `清空测试库到空库`() {
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { st ->
                if (context.dialect == Dialect.POSTGRESQL) {
                    listOf("md", "app", "kteasy").forEach { st.execute("DROP SCHEMA IF EXISTS $it CASCADE") }
                } else {
                    st.execute("SET FOREIGN_KEY_CHECKS = 0")
                    val tables = mutableListOf<String>()
                    conn
                        .createStatement()
                        .executeQuery(
                            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                        ).use { rs -> while (rs.next()) tables += rs.getString(1) }
                    tables.forEach { st.execute("DROP TABLE IF EXISTS `$it`") }
                    st.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        }
        println("[DbCleanTest] 已清空 ${context.dialect.profile} 测试库到空库")
    }
}
