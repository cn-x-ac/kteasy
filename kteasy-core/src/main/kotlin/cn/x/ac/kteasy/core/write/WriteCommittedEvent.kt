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
package cn.x.ac.kteasy.core.write

/**
 * 写入提交事件（图纸 04 §1 阶段 12 + §3 载荷，**形状冻结**：M3 自动化与 M5 审计都按此消费）。
 *
 * 只有事务提交后才允许发布（红线⑦：缓存失效/事件只挂提交后）——发布点唯一在装配层的
 * `@TransactionalEventListener(AFTER_COMMIT)`，与 [cn.x.ac.kteasy.core.kernel.MetadataChangedEvent] 同族。
 * 事务回滚则本事件静默消失，下游不会看到半条变更。
 *
 * @property diff 字段级新旧值（M3「结果一致则跳过」与 M5 审计的数据源；无变化时为空图）
 * @property traceId 贯穿链路（与响应头、日志 MDC 同源）
 *
 * 注：脱敏（图纸 04 §3 的 `masked:true`）归图纸 13 / M5，本载荷不含敏感明文义务由消费方实现；
 * 本卡只保证**载荷里有 diff 与来源**，使那件事可判定。
 */
data class WriteCommittedEvent(
    val eventId: String,
    val traceId: String,
    val objectApi: String,
    val recordId: String,
    val kind: WriteKind,
    val source: WriteSource,
    val actor: WriteActor,
    val diff: Map<String, FieldDiff>,
    val occurredAt: Long = System.currentTimeMillis(),
)
