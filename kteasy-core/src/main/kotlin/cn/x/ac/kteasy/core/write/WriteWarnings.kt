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
 * 非致命告警（写入成功但调用方/运维应当知道的事）。
 *
 * 形状按 P6 定为类型化 `{code, field?, msg}`，而不是裸串 `CODE:detail`：告警天生要被
 * M5 审计、M6 前端提示与 M3「结果一致跳过」消费，裸串早晚要做 split 解析＋转义，
 * 届时改形状就是破坏性变更。它同时与 420 的 `data.fields[]` 共用一套形状词汇。
 *
 * @property code 符号名，取自 [WriteWarnings.ALL_CODES]（清单由单测按精确集合相等锁死）
 * @property field 关联字段 api_name；无特定字段时为 null（序列化时该键缺省）
 */
data class WriteWarning(
    val code: String,
    val field: String? = null,
    val message: String,
) {
    init {
        require(code in WriteWarnings.ALL_CODES) { "未登记的告警符号名：$code（先在 ALL_CODES 登记并同步图纸 04）" }
    }

    /** 对外载荷（`WriteResult.warnings[]` 与提交事件共用）。无 `field` 时不写显式 null——与 M1-05 D9 的键存在性口径一致。 */
    fun toWire(): Map<String, Any?> =
        LinkedHashMap<String, Any?>().apply {
            put("code", code)
            field?.let { put("field", it) }
            put("msg", message)
        }
}

/**
 * 告警码清单。与错误符号名分表维护：错误＝拒绝并中止，告警＝放行但记账，
 * 两者混在一张表里会让「这条到底阻不阻塞」变成读代码才能知道的事。
 */
object WriteWarnings {
    /** 未带 `expectedVersion` 的更新覆盖了当前值（P1：照常写入，但覆盖链要可查）。 */
    const val OVERRIDE_WITHOUT_VERSION = "OVERRIDE_WITHOUT_VERSION"

    /**
     * 字段已停用（`enabled=false`）但历史值仍在存储里。
     *
     * 与 `FIELD_DISABLED`（M1-06 的**拒绝**码，P3：新值不可写）区分：本码用于 M1-07 字段
     * 存储迁移/EXT 遗留值场景——写成功、只是提醒有陈旧值。语义不同，故不共用符号名。
     */
    const val FIELD_DEPRECATED = "FIELD_DEPRECATED"

    /** 编号取号被跳过（**M1-07 预留**：导入不推进自动编号，见【全景】导入语义）。 */
    const val AUTONUM_SKIPPED = "AUTONUM_SKIPPED"

    /** 全部已登记告警码（新增须同步图纸 04 §2 与 API 总表）。 */
    val ALL_CODES: List<String> = listOf(OVERRIDE_WITHOUT_VERSION, FIELD_DEPRECATED, AUTONUM_SKIPPED)
}
