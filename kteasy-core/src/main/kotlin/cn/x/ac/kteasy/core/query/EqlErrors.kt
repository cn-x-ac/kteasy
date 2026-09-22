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
package cn.x.ac.kteasy.core.query

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException

/*
 * EQL 护栏错误工厂（图纸 03 §3 错误码表）。
 *
 * **错误码承载约定**（⟨可逆⟩代拍，见证据 §2）：M1-05 不改 M0-03 冻结的 [ApiError] 枚举
 * （`ApiErrorTest` 明确断言 412/413… 节段当前禁用），而是复用基座码——语法/字段/跳数/limit 越界归
 * [ApiError.INVALID_PARAM]（code 410）、类型不符归 [ApiError.BUSINESS_RULE]（code 420）、
 * 非白名单调用 `queryNoFilter` 归 [ApiError.FORBIDDEN]（code 403）；卡面的符号名（`EQL_SYNTAX` 等）
 * 落进响应 `data.error_id`，既守住三键契约、又让定位符可断言。
 */
object EqlErrors {
    /** 符号名 → 契约体 `data.error_id`（块5 注入反例 golden 断言按此校验）。 */
    const val ID_SYNTAX = "EQL_SYNTAX"
    const val ID_FIELD_UNKNOWN = "EQL_FIELD_UNKNOWN"
    const val ID_TYPE_MISMATCH = "EQL_TYPE_MISMATCH"
    const val ID_TOO_DEEP = "EQL_TOO_DEEP"
    const val ID_LIMIT = "EQL_LIMIT"
    const val ID_INJECT_FORBIDDEN = "EQL_INJECT_FORBIDDEN"

    private fun eql(
        apiError: ApiError,
        errorId: String,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = KnownKteasyException(apiError, message, extra + ("error_id" to errorId))

    /** 语法/词法越界（解析期），HTTP 400 / code 410。 */
    fun syntax(
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = eql(ApiError.INVALID_PARAM, ID_SYNTAX, "EQL 语法错误：$message", extra)

    /** 引用了对象上不存在/未启用的字段（编译期），HTTP 400 / code 410。 */
    fun fieldUnknown(
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = eql(ApiError.INVALID_PARAM, ID_FIELD_UNKNOWN, "EQL 未知字段：$message", extra)

    /** 字段类型与所用算子/字面量不匹配（编译期），HTTP 409 / code 420。 */
    fun typeMismatch(
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = eql(ApiError.BUSINESS_RULE, ID_TYPE_MISMATCH, "EQL 类型不符：$message", extra)

    /** 点链跳数超上限（结构/编译期），HTTP 400 / code 410。 */
    fun tooDeep(
        hops: Int,
        max: Int,
    ): KnownKteasyException =
        eql(
            ApiError.INVALID_PARAM,
            ID_TOO_DEEP,
            "EQL 点链跳数 $hops 超上限 $max",
            mapOf("hops" to hops, "max" to max),
        )

    /** LIMIT 缺失兜底外的越界（> 上限），HTTP 400 / code 410。 */
    fun limit(
        requested: Int,
        max: Int,
    ): KnownKteasyException =
        eql(
            ApiError.INVALID_PARAM,
            ID_LIMIT,
            "EQL LIMIT $requested 超上限 $max",
            mapOf("requested" to requested, "max" to max),
        )

    /** 非白名单调用点企图走免权限过滤通道，HTTP 403 / code 403。 */
    fun injectForbidden(
        callSite: String,
    ): KnownKteasyException = eql(ApiError.FORBIDDEN, ID_INJECT_FORBIDDEN, "EQL 免权限过滤通道拒绝调用点：$callSite", mapOf("call_site" to callSite))
}
