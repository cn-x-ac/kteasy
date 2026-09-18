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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.sql.Connection
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * 任务④「虚拟线程下 JDBC 探活并发测」：真连一把库，验证 Profile 决定的方言与驱动一致，并并发取连接。
 *
 * 仅当环境置 `KTEASY_IT_DB=true` 时启用（即 CI 的双库 service-matrix，SINGLE profile per run）。
 * 本地无库时该测整体跳过——这是「显式 capability 跳过并注明」，而非静默假绿；
 * 方言装配本身的正确性由守卫单测 + 双 Profile health 测在无库下已证明。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class DataSourceProbeSmokeTest {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var context: KteasyContext

    @Test
    fun `真连接的数据库产品与方言一致`() {
        dataSource.connection.use { conn ->
            assertThat(conn.isValid(5)).isTrue()
            assertThat(conn.metaData.databaseProductName).isEqualTo(expectedProduct(context.dialect))
        }
    }

    @Test
    fun `虚拟线程并发探活取连接`() {
        val concurrency = 20
        val results =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                (1..concurrency)
                    .map {
                        executor.submit<Boolean> {
                            dataSource.connection.use { conn: Connection -> conn.isValid(5) }
                        }
                    }.map { it.get() }
            }
        assertThat(results).hasSize(concurrency).allMatch { it }
    }

    private fun expectedProduct(dialect: Dialect): String =
        when (dialect) {
            Dialect.POSTGRESQL -> "PostgreSQL"
            Dialect.MYSQL -> "MySQL"
        }
}
