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
package cn.x.ac.kteasy.server.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 查询出口响应契约验收（M0-03 §5 立此锁三键形状；M1-05 端点已实装，本测改验「非法入参仍走严格三键契约体、禁裸 500」）。
 *
 * `POST /api/query` 缺 `eql` → 400 + `{error_code:410, error_msg, data}`（`error_id=EQL_SYNTAX`）。与库无关，故关 Flyway；
 * 控制器在调用查询引擎前即返回，不触达数据源。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=false"],
)
@ActiveProfiles("pg")
class ErrorContractWebTest {
    @Autowired
    lateinit var environment: Environment

    private val client: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `api query 缺 eql 返回 400 且契约三键齐备`() {
        val port = requireNotNull(environment.getProperty("local.server.port"))
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/api/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"bogus":1}"""))
                .build()
        val resp = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(resp.statusCode()).isEqualTo(400)
        assertThat(resp.body())
            .contains("\"error_code\":410")
            .contains("\"error_msg\":")
            .contains("\"data\":")
    }
}
