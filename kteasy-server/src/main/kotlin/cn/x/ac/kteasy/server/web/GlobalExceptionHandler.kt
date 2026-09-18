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
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 全局异常处理：把所有出口收敛到统一三键契约 `{error_code, error_msg, data}`（M0-03 §3、§8-④）。
 * 已知业务异常按 [ApiError.httpStatus] 映射 HTTP 码；未预期异常兜底为 500 契约体，**绝不裸 500**。
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(KnownKteasyException::class)
    fun handleKnown(ex: KnownKteasyException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity
            .status(ex.apiError.httpStatus)
            .body(ex.apiError.toBody(ex.message ?: ex.apiError.defaultMessage, ex.data))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<Map<String, Any?>> {
        log.error("未预期异常（已兜底为 500 契约体）", ex)
        return ResponseEntity
            .status(ApiError.INTERNAL.httpStatus)
            .body(ApiError.INTERNAL.toBody())
    }
}
