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
 * 传输层 4xx 契约验收（补【总纲】§E22 的遗留：M0-03 的兜底会把框架 4xx 一律渲染成 500）。
 *
 * 三条各打一个方向：路由不存在＝404、方法不允许＝405、请求体不可解析＝400；
 * 三者都必须回到**严格三键契约体**（禁裸 500），且契约码仍取基座段（D1）、符号名进 `data.error_id`。
 * 全都不触达数据源（Spring 在进控制器前就抛），故关 Flyway、不需要库。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=false"],
)
@ActiveProfiles("pg")
class TransportContractWebTest {
    @Autowired
    lateinit var environment: Environment

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun call(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val port = requireNotNull(environment.getProperty("local.server.port"))
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `不存在的路径给 404 契约体 不再谎报服务端错误`() {
        val resp = call("GET", "/api/definitely-not-here")
        assertThat(resp.statusCode()).isEqualTo(404)
        assertThat(resp.body()).contains("\"error_code\":404").contains("\"error_id\":\"ROUTE_NOT_FOUND\"")
    }

    @Test
    fun `方法不允许给 405 且契约码仍是基座段`() {
        val resp = call("GET", "/api/query")
        assertThat(resp.statusCode()).isEqualTo(405)
        assertThat(resp.body()).contains("\"error_code\":410").contains("\"error_id\":\"METHOD_NOT_ALLOWED\"").contains("\"method\"")
    }

    @Test
    fun `请求体不可解析给 400 三键契约体`() {
        val resp = call("POST", "/api/query", "{not json")
        assertThat(resp.statusCode()).isEqualTo(400)
        assertThat(resp.body()).contains("\"error_code\":410").contains("\"error_id\":\"BODY_UNREADABLE\"")
    }

    @Test
    fun `三键键序在传输层同样成立`() {
        val body = call("GET", "/api/nope").body()
        assertThat(body.indexOf("\"error_code\"")).isLessThan(body.indexOf("\"error_msg\""))
        assertThat(body.indexOf("\"error_msg\"")).isLessThan(body.indexOf("\"data\""))
    }
}
