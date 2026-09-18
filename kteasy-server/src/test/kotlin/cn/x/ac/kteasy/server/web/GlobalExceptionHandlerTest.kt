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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 全局异常处理器的 L1 单测：已知异常按契约码映射 HTTP 码、未预期异常兜底为 500 契约体（禁裸 500）。
 * 直接调用 advice 方法，不需起 Spring 上下文。
 */
class GlobalExceptionHandlerTest {
    private val advice = GlobalExceptionHandler()

    @Test
    fun `已知异常渲染严格三键并映射 HTTP 码`() {
        val resp = advice.handleKnown(KnownKteasyException(ApiError.INVALID_PARAM, "缺少必填 field"))
        assertThat(resp.statusCode.value()).isEqualTo(400)
        val body = requireNotNull(resp.body)
        assertThat(body.keys).containsExactly("error_code", "error_msg", "data")
        assertThat(body["error_code"]).isEqualTo(410)
        assertThat(body["error_msg"]).isEqualTo("缺少必填 field")
    }

    @Test
    fun `未预期异常兜底为 500 契约体而非裸 500`() {
        val resp = advice.handleUnexpected(RuntimeException("数据库炸了"))
        assertThat(resp.statusCode.value()).isEqualTo(500)
        val body = requireNotNull(resp.body)
        assertThat(body.keys).containsExactly("error_code", "error_msg", "data")
        assertThat(body["error_code"]).isEqualTo(500)
    }
}
