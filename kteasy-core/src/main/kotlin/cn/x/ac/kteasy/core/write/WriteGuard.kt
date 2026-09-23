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
 * 写入守卫接缝（卡面 §2 阶段 2；M2-02 换实现即接真权限模型，本卡透传桩）。
 *
 * 与查询层 [cn.x.ac.kteasy.core.query.PrivilegeInjector] 同款设计，但**刻意不做逃生舱**：
 * 查询侧的 `queryNoFilter` 之所以需要白名单，是因为仪表盘/recalc 有合法的免过滤读；
 * 写入侧不存在「免权限写」——特权通道靠 [WriteSource.SYSTEM] 表达、由守卫实现自行裁决，
 * 因此这里没有 `writeNoGuard`，也就没有可被悄悄放宽的口子（卡面「出口唯一」的写侧落点）。
 * 守卫类拒绝只有一个符号名 `WRITE_FORBIDDEN`（P5）——差异进 message，不设第二个拒绝出口。
 */
interface WriteGuard {
    /**
     * 放行则正常返回；拒绝则抛 [WriteErrors.forbidden]（403 契约体）。
     *
     * 不返回布尔：返回布尔等于把「忘了处理 false」这种事故留给调用方。
     */
    fun check(
        req: WriteGuardRequest,
    )
}

/**
 * 守卫请求（M1-06 冻结形状）。字段集合与记录 id 都带上，M2 的「6 操作×5 层级 + 字段权限三态」
 * 才有足够信息判定，而不必回来改签名。
 *
 * @property touched 本次触碰的字段 api_name 集合（含显式清空）
 * @property recordId 既有行 id（新建为 null）；M2 需按记录归属人/部门判层级
 */
data class WriteGuardRequest(
    val ctx: WriteContext,
    val touched: Set<String>,
    val recordId: String?,
)

/** 缺省实现：一律放行（M1-06 无权限模型；M2-02 替换此 bean，管道与出口不变）。 */
class PassthroughWriteGuard : WriteGuard {
    override fun check(
        req: WriteGuardRequest,
    ) = Unit
}

/**
 * `writeAllInTx` 的调用来源闸门（卡面 §6：批量同事务是内部 API，仅 SYSTEM/TRANSFORM/IMPORT 可用）。
 *
 * 枚举即清单：新增特权来源必须显式改这里并被评审，否则 UI/OPENAPI 就能把「多条一个大事务」
 * 变成锁死吞吐的入口。
 */
object InternalWriteSources {
    val ALLOWED: Set<WriteSource> = setOf(WriteSource.SYSTEM, WriteSource.TRANSFORM, WriteSource.IMPORT)

    fun isAllowed(
        source: WriteSource,
    ): Boolean = source in ALLOWED
}
