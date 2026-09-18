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

import cn.x.ac.kteasy.core.kernel.ApiError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * EQL（实体查询语言）端点占位（M0-03 §5）。**只锁形状、不写解析器**（解析归 M1-05）。
 *
 * 卡定：`POST /api/query` 返回 HTTP 501 + 契约样例 `error_code=420`，
 * 以此把「查询出口」的响应契约定型，防止各卡各写各的。
 */
@RestController
@RequestMapping("/api")
class QueryController {
    @PostMapping("/query")
    fun queryPlaceholder(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(
                ApiError.BUSINESS_RULE.toBody(
                    message = "EQL 查询解析尚未实现（M1-05 落地）",
                    data = mapOf("endpoint" to "/api/query", "planned_milestone" to "M1-05"),
                ),
            )
}
