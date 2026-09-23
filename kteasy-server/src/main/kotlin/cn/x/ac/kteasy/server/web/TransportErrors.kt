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

/**
 * 传输层契约（REST 边界专属，与写通道的 `WriteErrors` 分表）。
 *
 * 分表的理由：这里发生的是「请求根本没进到业务规则」——路由不存在、方法不允许、媒体类型不对、
 * 请求体读不出。把它们塞进写通道清单会让 `ALL_IDS` 混进两类完全不同的责任，M2/M3 消费时无法判断该不该重试。
 *
 * **承载码仍只用基座段**（D1）：契约码是 410/404，HTTP 状态按语义给 405/415/400/404——
 * 传输状态与契约码本来就是两件事（P4 同一口径：将来限流要 429 也走这条通道，不新增节段）。
 *
 * 这条表也补掉【总纲】§E22 挂到本卡的遗留：M0-03 的 `Exception` 兜底会把 Spring 框架 4xx 一律渲染成 500，
 * 满足「禁裸 500」但把「路径不存在」也报成服务端错误，客户端与监控都被误导。
 */
object TransportErrors {
    const val ID_ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND"
    const val ID_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    const val ID_MEDIA_TYPE = "MEDIA_TYPE_UNSUPPORTED"
    const val ID_BODY_UNREADABLE = "BODY_UNREADABLE"
    const val ID_PARAM_TYPE = "PARAM_TYPE_INVALID"
    const val ID_DRAFT_SHAPE = "DRAFT_VALUE_SHAPE"

    /** 全部传输符号名（新增须同步 API 总表 §1 与本断言）。 */
    val ALL_IDS: List<String> =
        listOf(
            ID_ROUTE_NOT_FOUND,
            ID_METHOD_NOT_ALLOWED,
            ID_MEDIA_TYPE,
            ID_BODY_UNREADABLE,
            ID_PARAM_TYPE,
            ID_DRAFT_SHAPE,
        )

    /**
     * @param httpStatus **HTTP 传输层**状态；契约体里的 `error_code` 仍取 [apiError] 的基座码。
     */
    fun of(
        errorId: String,
        apiError: ApiError,
        httpStatus: Int,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Response = Response(httpStatus, apiError.toBody(message, extra + ("error_id" to errorId)))

    /** 渲染结果：HTTP 状态 + 三键契约体。 */
    data class Response(
        val httpStatus: Int,
        val body: Map<String, Any?>,
    )

    /** 载荷字段值形态非法（还没进到写通道就被拒）——冒泡给全局处理器，与业务拒绝同一渲染路径。 */
    fun draftShape(
        detail: List<String>,
    ): KnownKteasyException = KnownKteasyException(ApiError.INVALID_PARAM, "字段值形态非法：" + detail.joinToString("；"), mapOf("error_id" to ID_DRAFT_SHAPE, "fields" to detail))
}
