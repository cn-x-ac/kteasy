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
package cn.x.ac.kteasy.server.write

import cn.x.ac.kteasy.core.write.DetailRow
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.server.web.TransportErrors

/**
 * JSON 载荷 → [DraftValue] 的边界映射（块 4）。
 *
 * **为什么在这里就拒绝 Float/Double**：小数以 `DECIMAL(30,8)` 存储（M1-04 定），而 JSON 浮点数经
 * double 二进制后已经丢了十进制精度——真要保精度，调用方必须把小数**以字符串**传（开放接口文档同此口径）。
 * 静默接受浮点会让第 17 位小数变成噪声，事后无法归因。
 *
 * 只判**值形**不判类型合法性：字段是否存在、域校验、必填等全在写通道（阶段 3/4），
 * 边界层重复判会把同一套规则写两遍、迟早分叉。
 */
object DraftValues {
    /** 仅字段（无子项）。[fields] 为 null＝本次不触碰任何字段（PATCH 语义下合法）。 */
    fun toDraft(
        fields: Map<*, *>?,
    ): RecordDraft {
        val bad = ArrayList<String>()
        val values = parseFields(fields, bad)
        if (bad.isNotEmpty()) throw TransportErrors.draftShape(bad)
        return RecordDraft(values)
    }

    /**
     * 字段 + 子项差量（M1-07 块 2；A1）。[details] 为 null/缺省＝本次不碰任何子表。
     *
     * @param details `子对象 api_name → 行数组`；行＝`{id?, fields}`，行内出现 `details`＝孙级，块 2 拒
     */
    fun toDraft(
        fields: Map<*, *>?,
        details: Map<*, *>?,
    ): RecordDraft {
        val bad = ArrayList<String>()
        val values = parseFields(fields, bad)
        val parsedDetails = parseDetails(details, bad)
        if (bad.isNotEmpty()) throw TransportErrors.draftShape(bad)
        return RecordDraft(values, parsedDetails)
    }

    private fun parseFields(
        fields: Map<*, *>?,
        bad: MutableList<String>,
    ): Map<String, DraftValue> {
        if (fields.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, DraftValue>(fields.size)
        fields.forEach { (key, raw) ->
            val api =
                key as? String ?: run {
                    bad += "字段名必须是字符串（实际 $key）"
                    return@forEach
                }
            if (api.isBlank()) {
                bad += "字段名为空"
                return@forEach
            }
            when (raw) {
                null -> {
                    out[api] = DraftValue.Cleared
                }

                is String -> {
                    out[api] = DraftValue.Text(raw)
                }

                is Boolean -> {
                    out[api] = DraftValue.Bool(raw)
                }

                is Int, is Long, is Short, is Byte -> {
                    out[api] = DraftValue.Number(raw.toString())
                }

                is java.math.BigInteger -> {
                    out[api] = DraftValue.Number(raw.toString())
                }

                is java.math.BigDecimal -> {
                    out[api] = DraftValue.Number(raw.toPlainString())
                }

                is List<*> -> {
                    val items = raw.map { it }
                    val strings = items.filterIsInstance<String>()
                    val numbers = items.filterIsInstance<Number>()
                    when {
                        items.isEmpty() -> {
                            out[api] = DraftValue.Many(emptyList())
                        }

                        strings.size == items.size -> {
                            out[api] = DraftValue.Many(strings)
                        }

                        numbers.size == items.size && items.none { it is Float || it is Double } -> {
                            out[api] = DraftValue.Many(numbers.map { it.toString() })
                        }

                        else -> {
                            bad += "字段 [$api] 的数组元素必须是同类（字符串或不含浮点的数字）"
                        }
                    }
                }

                is Float, is Double -> {
                    bad += "字段 [$api] 是浮点数：小数请用字符串传（DECIMAL(30,8) 不接受二进制精度损失）"
                }

                else -> {
                    bad += "字段 [$api] 的值类型 ${raw.javaClass.simpleName} 不被支持（对象/嵌套结构不进字段值）"
                }
            }
        }
        return out
    }

    /** 子项差量解析：缺键即不碰（返回空表＝不写子树）；空数组是合法的「清空该子表」。 */
    private fun parseDetails(
        details: Map<*, *>?,
        bad: MutableList<String>,
    ): Map<String, List<DetailRow>> {
        if (details.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, List<DetailRow>>()
        details.forEach { (key, value) ->
            val childApi =
                key as? String ?: run {
                    bad += "details 的子对象名必须是字符串"
                    return@forEach
                }
            if (childApi.isBlank()) {
                bad += "details 子对象名为空"
                return@forEach
            }
            val rows =
                value as? List<*> ?: run {
                    bad += "details[$childApi] 必须是行数组（空数组＝清空该子表）"
                    return@forEach
                }
            val parsed = ArrayList<DetailRow>(rows.size)
            for (r in rows) {
                val rm =
                    r as? Map<*, *> ?: run {
                        bad += "details[$childApi] 的每条子行必须是对象 {id?, fields}"
                        continue
                    }
                if (rm.containsKey("details")) {
                    bad += "details[$childApi] 子行内不得再带 details（块 2 只支持一层父子）"
                    continue
                }
                val idRaw = rm["id"]
                val id =
                    when (idRaw) {
                        null -> {
                            null
                        }

                        is String -> {
                            idRaw
                        }

                        else -> {
                            bad += "details[$childApi].id 必须是字符串或省略"
                            continue
                        }
                    }
                val rowFields = parseFields(rm["fields"] as? Map<*, *>, bad)
                parsed += DetailRow(id, rowFields)
            }
            out[childApi] = parsed
        }
        return out
    }
}
