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

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 查询上下文（M1-05 冻结形状，M2-02 据 [userId] 与角色实装真注入）。
 *
 * @property now 编译/渲染期时钟（注入以保证双库 oracle 可复现）
 * @property includeDeleted 仅治理/内部通道置真的标志；公开查询入口恒 false（软删默认）
 */
data class QueryContext(
    val userId: String?,
    val now: ZonedDateTime,
    val includeDeleted: Boolean = false,
) {
    val zone: ZoneId get() = now.zone
}

/**
 * 权限注入接缝（卡面 §3：`QueryPlan → PrivilegeInjector.inject(plan, ctx)`，本卡默认透传、接口冻结）。
 *
 * M2-02 把「层级/条件/字段权限」谓词追加到计划最外层 AND。**查询出口唯一化**保证「漏注入=编译不过」：
 * 只有经 [PrivilegeInjector] 一条路渲染，红绿对测（注入器换成"拒绝全部"→全查询测试红）证明无旁路。
 */
interface PrivilegeInjector {
    fun inject(
        plan: QueryPlan,
        ctx: QueryContext,
    ): QueryPlan
}

/** 默认实现：原样返回（M1-05 无权限模型，M2 替换此 bean）。 */
class PassthroughPrivilegeInjector : PrivilegeInjector {
    override fun inject(
        plan: QueryPlan,
        ctx: QueryContext,
    ): QueryPlan = plan
}

/**
 * `queryNoFilter` 白名单调用点（kernel 内免权限过滤的内部通道）。**枚举即清单**——persist4j 教训的 SPI 化：
 * 新增绕过点必须显式加枚举 + 过 [NoFilterWhitelist] 白名单评审，否则 [cn.x.ac.kteasy.core.query.EqlErrors.injectForbidden]。
 */
enum class NoFilterCallSite {
    /** 仪表盘/统计聚合的跨记录扫（M3a/§3.5 落地时启用）。 */
    DASHBOARD_ROLLOUT,

    /** recalc 依赖图回填时读关联记录（M1-07 启用）。 */
    RECALC_BACKFILL,
}

/**
 * 允许走免过滤通道的调用点集合。⟨本卡为空⟩：M1-05 无合法内部消费者，任何 [NoFilterCallSite] 请求都拒（403），
 * 白名单随下游卡按评审逐项开启；单测锁此集合内容防「悄悄放宽」（[NoFilterWhitelist] 是唯一真源）。
 */
object NoFilterWhitelist {
    val ALLOWED: Set<NoFilterCallSite> = emptySet()

    fun isAllowed(
        site: NoFilterCallSite,
    ): Boolean = site in ALLOWED
}
