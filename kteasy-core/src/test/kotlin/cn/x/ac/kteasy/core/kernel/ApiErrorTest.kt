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
package cn.x.ac.kteasy.core.kernel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 错误契约的 L1 单测：三键形状、键序、码值与异常默认值。core 纯库，不拉 Spring。
 */
class ApiErrorTest {
    @Test
    fun `toBody 恒为严格三键且键序固定`() {
        val body = ApiError.INVALID_PARAM.toBody()
        assertThat(body.keys).containsExactly("error_code", "error_msg", "data")
        assertThat(body["error_code"]).isEqualTo(410)
        assertThat(body["error_msg"]).isEqualTo(ApiError.INVALID_PARAM.defaultMessage)
        assertThat(body["data"]).isNull()
    }

    @Test
    fun `toBody 带定位数据`() {
        val body = ApiError.BUSINESS_RULE.toBody("名称字段重复", mapOf("fields" to listOf("name")))
        assertThat(body["error_code"]).isEqualTo(420)
        assertThat(body["error_msg"]).isEqualTo("名称字段重复")
        @Suppress("UNCHECKED_CAST")
        val data = body["data"] as Map<String, Any?>
        assertThat(data["fields"]).isEqualTo(listOf("name"))
    }

    @Test
    fun `码值唯一且段区间不撞 M1+ 预留段`() {
        // 基础段只用 401/403/404/410/420/500/501；412/413/414/415/416/417/418/419 归 M1+（图纸04）。
        val codes = ApiError.entries.map { it.code }
        assertThat(codes).doesNotHaveDuplicates()
        assertThat(codes).contains(401, 403, 410, 420)
        val reservedForLater = listOf(412, 413, 414, 415, 416, 417, 418, 419)
        assertThat(codes).doesNotContainAnyElementsOf(reservedForLater)
    }

    @Test
    fun `KnownKteasyException 携契约码与默认人话`() {
        val ex = KnownKteasyException(ApiError.FORBIDDEN)
        assertThat(ex.apiError).isEqualTo(ApiError.FORBIDDEN)
        assertThat(ex.message).isEqualTo(ApiError.FORBIDDEN.defaultMessage)
        assertThat(ex.data).isNull()
        assertThat(ex).isInstanceOf(KteasyException::class.java)
    }
}
