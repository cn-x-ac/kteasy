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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 块4b 接缝 L1：默认注入器恒等（M1-05 不改变计划）、queryNoFilter 白名单**本卡锁为空**（任何绕过点都拒，403）。
 *
 * 白名单是「免权限过滤」的唯一闸；此测把当前清单钉死，未来开启任一调用点须显式改此断言（过评审、防悄悄放宽）。
 */
class PrivilegeSeamTest {
    private val plan = QueryPlan(TableRef("t0", "customer"), listOf(SelectPlan.AllBusiness), null, emptyList(), emptyList(), null, null, emptyList(), false)

    @Test
    fun `默认透传注入器返回原计划引用`() {
        val ctx = QueryContext(userId = "u1", now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))
        assertThat(PassthroughPrivilegeInjector().inject(plan, ctx)).isSameAs(plan)
    }

    @Test
    fun `queryNoFilter 白名单本卡为空且拒绝一切调用点`() {
        assertThat(NoFilterWhitelist.ALLOWED).isEmpty()
        NoFilterCallSite.entries.forEach { site ->
            assertThat(NoFilterWhitelist.isAllowed(site)).isFalse()
        }
        // 供 server QueryEngine 复用：未授权调用点工厂产 403 异常（throw 发生在调用点，此处验工厂产物）
        val ex = EqlErrors.injectForbidden(NoFilterCallSite.DASHBOARD_ROLLOUT.name)
        assertThat(ex.apiError).isEqualTo(ApiError.FORBIDDEN)
        assertThat((ex.data as Map<*, *>)["error_id"]).isEqualTo(EqlErrors.ID_INJECT_FORBIDDEN)
    }
}
