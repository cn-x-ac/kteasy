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
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

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

    // ---------- 传输层 4xx（补【总纲】§E22 挂到本卡的遗留） ----------
    //
    // M0-03 只有 Exception 兜底：Spring 自己在进控制器之前抛的那批 4xx（路径不存在、方法不允许、
    // 请求体读不出…）全被渲染成 500 契约体——满足「禁裸 500」，但把「你请求打错了」报成「服务端坏了」，
    // 客户端、监控、值班的人三方都被误导。这里逐条给回正确的 HTTP 状态；契约码仍走基座段（D1），
    // 符号名单独一张表（[TransportErrors]），因为它们发生在业务规则之前。

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        ex: NoResourceFoundException,
    ): ResponseEntity<Map<String, Any?>> = render(TransportErrors.of(TransportErrors.ID_ROUTE_NOT_FOUND, ApiError.NOT_FOUND, 404, "请求路径不存在"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
    ): ResponseEntity<Map<String, Any?>> =
        render(
            TransportErrors.of(
                TransportErrors.ID_METHOD_NOT_ALLOWED,
                ApiError.INVALID_PARAM,
                405,
                "该路径不支持 " + (ex.method ?: "?") + " 方法",
                mapOf("method" to (ex.method ?: "")),
            ),
        )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaType(
        ex: HttpMediaTypeNotSupportedException,
    ): ResponseEntity<Map<String, Any?>> =
        render(
            TransportErrors.of(
                TransportErrors.ID_MEDIA_TYPE,
                ApiError.INVALID_PARAM,
                415,
                "请求体媒体类型不支持，请用 application/json",
                mapOf("content_type" to (ex.contentType?.toString() ?: "")),
            ),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(
        ex: HttpMessageNotReadableException,
    ): ResponseEntity<Map<String, Any?>> = render(TransportErrors.of(TransportErrors.ID_BODY_UNREADABLE, ApiError.INVALID_PARAM, 400, "请求体不是可解析的 JSON"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleArgTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
    ): ResponseEntity<Map<String, Any?>> =
        render(
            TransportErrors.of(
                TransportErrors.ID_PARAM_TYPE,
                ApiError.INVALID_PARAM,
                400,
                "参数 [" + ex.name + "] 类型非法",
                mapOf("param" to ex.name),
            ),
        )

    /** 兜底：未预期异常走 500 契约体（禁裸 500）。放在最后，具名 handler 优先级更高。 */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<Map<String, Any?>> {
        log.error("未预期异常（已兜底为 500 契约体）", ex)
        return ResponseEntity
            .status(ApiError.INTERNAL.httpStatus)
            .body(ApiError.INTERNAL.toBody())
    }

    /** 传输层渲染：HTTP 状态与契约体分开给（405/415 这类状态只在传输层有意义）。 */
    private fun render(
        r: TransportErrors.Response,
    ): ResponseEntity<Map<String, Any?>> = ResponseEntity.status(r.httpStatus).body(r.body)
}
