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

import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors

/**
 * 测试侧健康端点访问器：直接打真实起服端口（RANDOM_PORT 下由 `local.server.port` 暴露）。
 *
 * 用 JDK 自带 HttpClient + 原始响应体字符串断言，既绕开 Boot 4 里被搬模块/换包（Jackson 3＝`tools.jackson`）
 * 的测试工具类，也保持「真起服 + 真 HTTP」的行为证据属性。health 响应体小且字段稳定，子串断言足够。
 */
object HealthClient {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build()

    data class Response(
        val status: Int,
        val body: String,
    )

    fun health(environment: Environment): Response {
        val port =
            requireNotNull(environment.getProperty("local.server.port")) {
                "RANDOM_PORT 未注入 local.server.port——起服未成功"
            }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/health")).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body().orEmpty())
    }
}
