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
 * 验收①（pg 侧）：`SPRING_PROFILES_ACTIVE=pg` 起服，`/api/health` 返回 `dialect=pg`。
 *
 * 不连真库也能起（Hikari 启动期不建连），health 不触库；方言由 Profile 显式映射，
 * 因此本测验证的是「Profile → 方言 → 响应体」这条装配链，而非数据可达性（探活见 CI service-matrix）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("pg")
class PgProfileHealthTest {
    @Autowired
    lateinit var environment: Environment

    @Test
    fun `pg Profile 起服且 health 报 dialect=pg`() {
        val response = HealthClient.health(environment)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body)
            .contains("\"status\":\"UP\"")
            .contains("\"dialect\":\"pg\"")
            .contains("\"version\":\"")
            .contains("\"capabilities\":[]")
            .contains("\"clock\":\"")
        // Profile 确实注入到方言与连接串，二者一致
        assertThat(environment.getProperty("kteasy.db.dialect")).isEqualTo("pg")
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:postgresql:")
    }
}
