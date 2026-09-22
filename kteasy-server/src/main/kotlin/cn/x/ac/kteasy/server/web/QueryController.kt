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
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.server.query.QueryEngine
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * EQL 查询端点（卡面 §3 / 图纸 03）。**唯一出口**＝[QueryEngine]——控制器只做入参校验 + 上下文装配，
 * 不自己解析/渲染/执行（否则破坏「查询出口唯一化」，M2 权限注入将现旁路）。
 *
 * 语法/语义/护栏错误经 [KnownKteasyException] 冒泡至全局异常处理器渲染为三键契约体（红线：禁裸 500）。
 */
@RestController
@RequestMapping("/api")
class QueryController(
    private val engine: QueryEngine,
) {
    @PostMapping("/query")
    fun query(
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val eql =
            body["eql"] as? String
                ?: return ResponseEntity
                    .status(HttpStatus.valueOf(ApiError.INVALID_PARAM.httpStatus))
                    .body(ApiError.INVALID_PARAM.toBody("缺少必填参数 'eql'", mapOf("error_id" to "EQL_SYNTAX")))
        // M1-05 无鉴权上下文（M2 接入登录用户/角色）；时钟用系统默认时区的此刻。
        val ctx = QueryContext(userId = null, now = ZonedDateTime.now(ZoneId.systemDefault()).withNano(0))
        val result = engine.run(eql, ctx)
        val data =
            linkedMapOf<String, Any?>(
                "rows" to result.rows,
                "truncated" to result.truncated,
                "elapsed_ms" to result.elapsedMs,
            )
        return ResponseEntity.ok(linkedMapOf("error_code" to 0, "error_msg" to "ok", "data" to data))
    }
}
