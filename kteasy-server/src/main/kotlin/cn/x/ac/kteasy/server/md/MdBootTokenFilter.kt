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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.server.config.KteasyProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * md 治理面 boot token 鉴权（M1-01 过渡方案，TODO(M2) 换真权限）。
 *
 * - 配置 `kteasy.md.boot-token` 后：`/api/md/` 下全部路径必须带 `Authorization: Bearer <token>`
 *   或 `X-Boot-Token: <token>`，否则 401 契约体（三键，不裸 500）。
 * - 留空 = 开发直通模式：放行但启动打 WARN（仅限本地/受信环境；生产必须显式配置）。
 * - 只拦 /api/md 前缀；健康探针与数据面不受影响。
 */
@Component
@Order(20)
class MdBootTokenFilter(
    private val props: KteasyProperties,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (props.md.bootToken.isBlank()) {
            log.warn("kteasy.md.boot-token 未配置：/api/md/** 处于开发直通模式，生产部署必须显式配置（M2 将换成真权限）")
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/md/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val expected = props.md.bootToken
        if (expected.isBlank()) {
            filterChain.doFilter(request, response)
            return
        }
        val provided =
            request
                .getHeader("Authorization")
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: request.getHeader("X-Boot-Token")?.trim()?.takeIf { it.isNotEmpty() }
        if (provided == expected) {
            filterChain.doFilter(request, response)
        } else {
            response.status = ApiError.UNAUTHORIZED.httpStatus
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                """{"error_code":${ApiError.UNAUTHORIZED.code},"error_msg":"${ApiError.UNAUTHORIZED.defaultMessage}","data":null}""",
            )
        }
    }
}
