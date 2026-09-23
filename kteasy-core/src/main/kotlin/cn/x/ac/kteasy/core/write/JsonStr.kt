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
 * `ext` 受限文法的编码原语（解析在 [JsonReader]）。
 * 取向同 M1-01/M1-04 的 `JsonArrays`：core 只处理自己能穷举的形状，不冒充通用 JSON 库。
 */
internal object JsonStr {
    fun quote(s: String): String =
        buildString {
            append('"')
            s.forEach { c ->
                when (c) {
                    '"' -> {
                        append("\\\"")
                    }

                    '\\' -> {
                        append("\\\\")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    else -> {
                        if (c < ' ') {
                            append("\\u").append(c.code.toString(16).padStart(4, '0'))
                        } else {
                            append(c)
                        }
                    }
                }
            }
            append('"')
        }

    fun literal(v: DraftValue): String =
        when (v) {
            is DraftValue.Text -> quote(v.value)
            is DraftValue.Bool -> v.value.toString()
            is DraftValue.Number -> number(v.literal)
            is DraftValue.Many -> v.items.joinToString(",", "[", "]") { quote(it) }
            is DraftValue.Cleared -> error("Cleared 不进编码（清空＝删键）")
        }

    private fun number(raw: String): String {
        val t = raw.trim()
        require(t.isNotEmpty() && (t[0].isDigit() || t[0] == '-') && t.all { it.isDigit() || it in ".eE+-" }) { "非法数字字面量：[$raw]" }
        return t
    }
}
