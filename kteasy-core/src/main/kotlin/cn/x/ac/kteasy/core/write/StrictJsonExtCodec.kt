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
 * [ExtCodec] 的缺省实现：只覆盖 `ext` 真正需要的**受限文法**，不引第三方 JSON 库。
 *
 * 为什么不挂 Jackson：core 的零第三方依赖是硬约束（ArchUnit + 许可红线），而装配层虽有 Jackson，
 * Boot 4 换 Jackson 3 后包名仍在动（M0-03 立 `ApiError` 时就因此手写了键序）。`ext` 的值形态在
 * 图纸 01 §2 里是**闭合的四种**（字符串/数字/布尔/字符串数组），手写严格解析器比引入依赖更稳。
 * 真需要富结构时换实现即可——接缝是 [ExtCodec]，不是这个类。
 *
 * 文法：顶层必须是对象；值仅允许 string / number / true / false / string[]；**嵌套对象一律拒绝**
 * （出现即数据损坏，宁可抛错也不静默降级——静默会让 diff 与审计读到假值）。
 */
class StrictJsonExtCodec : ExtCodec {
    override fun encode(
        values: Map<String, DraftValue>,
    ): String {
        val parts =
            values.filterValues { it !is DraftValue.Cleared }.map { (k, v) ->
                JsonStr.quote(k) + ':' + JsonStr.literal(v)
            }
        return parts.joinToString(",", "{", "}")
    }

    override fun decode(
        json: String?,
    ): Map<String, DraftValue> {
        val src = json?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return emptyMap()
        return JsonReader(src).objectOf()
    }
}
