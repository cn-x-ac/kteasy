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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles

/**
 * 验收①（mysql 侧）：同一套代码换 Profile，`/api/health` 报 `dialect=mysql`；
 * 连接串带 utf8mb4 + serverTimezone=UTC 存储约定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class MysqlProfileHealthTest {
    @Autowired
    lateinit var environment: Environment

    @Test
    fun `mysql Profile 起服且 health 报 dialect=mysql`() {
        val response = HealthClient.health(environment)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body)
            .contains("\"dialect\":\"mysql\"")
            .contains("\"status\":\"UP\"")
        assertThat(environment.getProperty("kteasy.db.dialect")).isEqualTo("mysql")
        assertThat(environment.getProperty("spring.datasource.url"))
            .startsWith("jdbc:mysql:")
            .contains("serverTimezone=UTC")
    }
}
