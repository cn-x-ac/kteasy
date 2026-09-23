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

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * traceId 贯穿链路的起点（【规格】§7：日志含 traceId 贯穿写入→自动化链）。
 *
 * 本卡只做「MDC 位」：入站若有 `X-Trace-Id` 则沿用（供上游网关透传），否则生成一段短 id；
 * 写请求头与响应头，请求结束清理 MDC，避免虚拟线程复用线程时的串味。
 * 自动化执行谱系（级联/防循环 traceId）是 M3a 的范围，此处仅立贯穿位。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val traceId =
            request.getHeader(TRACE_HEADER)?.takeIf { it.isNotBlank() }
                ?: UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 16)
        MDC.put(MDC_KEY, traceId)
        response.setHeader(TRACE_HEADER, traceId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        /** 日志 MDC 键（响应头与写通道 traceId 同源，故对外可见）。 */
        const val MDC_KEY = "traceId"
        const val TRACE_HEADER = "X-Trace-Id"
    }
}
