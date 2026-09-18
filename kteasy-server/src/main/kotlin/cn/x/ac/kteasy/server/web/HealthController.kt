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

import cn.x.ac.kteasy.core.kernel.KteasyContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * `GET /api/health` —— 存活探针（步骤卡 M0-02 §3）。
 *
 * 只读进程事实快照，不触库（本卡禁旁路 SQL）：dialect 证明 Profile 映射生效，
 * clock 供与服务端 `date` 对时（验收偏差 <2s）。capabilities 暂空，M1-02 填。
 */
@RestController
@RequestMapping("/api")
class HealthController(
    private val context: KteasyContext,
) {
    @GetMapping("/health")
    fun health(): HealthResponse =
        HealthResponse(
            status = "UP",
            version = context.version,
            dialect = context.dialect.profile,
            capabilities = context.capabilities,
            clock = OffsetDateTime.now().toString(),
        )
}

/**
 * 健康响应体。字段顺序即 JSON 顺序：status / version / dialect / capabilities / clock。
 */
data class HealthResponse(
    val status: String,
    val version: String,
    val dialect: String,
    val capabilities: List<String>,
    val clock: String,
)
