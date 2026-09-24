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
package cn.x.ac.kteasy.core.autonum

/**
 * `segments_json` 受限文法读取器：顶层是**对象数组**，对象值只允许字符串或（无小数的）整数。
 *
 * 与 `core.write.JsonReader`（ext 专用、吐 DraftValue、拒嵌套对象）刻意分开：编号模板是「数组套对象」，
 * 那是 ext 明确禁止的形状，故本包自带一个更小的闭合解析器，只认模板需要用到的形状，
 * 出现布尔/null/小数/更深嵌套一律抛错——宁可配置保存即失败并报人话，也不静默降级让号形悄悄跑偏。
 */
internal object SegmentsJson {
    /** 解析为 `List<Map<key, String|Long>>`；顺序＝数组声明顺序，对象内键序不敏感。 */
    fun read(src: String): List<Map<String, Any>> {
        val p = Cursor(src)
        val out = p.arrayOfObjects()
        p.end()
        return out
    }

    private class Cursor(
        val s: String,
    ) {
        private var i = 0

        /** 顶层：`[ {..}, {..} ]`，元素必须是对象。 */
        fun arrayOfObjects(): List<Map<String, Any>> {
            expect('[')
            val out = ArrayList<Map<String, Any>>()
            if (peek() == ']') {
                next()
                return out
            }
            while (true) {
                if (peek() != '{') fail("编号模板数组元素必须是对象")
                out += `object`()
                when (next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("数组未闭合")
                }
            }
        }

        fun `object`(): Map<String, Any> {
            expect('{')
            val out = LinkedHashMap<String, Any>()
            if (peek() == '}') {
                next()
                return out
            }
            while (true) {
                val key = string()
                expect(':')
                out[key] = value()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("对象未闭合")
                }
            }
        }

        private fun value(): Any =
            when (peek()) {
                '"' -> string()
                '-' -> number()
                else -> if ((peek() ?: 'x').isDigit()) number() else fail("值只允许字符串或整数")
            }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("字符串未闭合")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(escape())
                    else -> sb.append(c)
                }
            }
        }

        private fun escape(): Char {
            val e = s.getOrNull(i++) ?: fail("转义未闭合")
            return when (e) {
                '"', '\\', '/' -> e
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                else -> fail("非法转义 \\$e")
            }
        }

        private fun number(): Long {
            val start = i
            if (s.getOrNull(i) == '-') i++
            var digits = 0
            while (i < s.length && s[i].isDigit()) {
                i++
                digits++
            }
            if (digits == 0) fail("非法整数")
            // 模板里的数字段（width/start）只允许整数；出现小数点/指数＝配置错误。
            if (i < s.length && s[i] in ".eE") fail("编号模板数字只允许整数")
            return s.substring(start, i).toLong()
        }

        fun end() {
            skipWs()
            if (i != s.length) fail("尾部有多余内容")
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? {
            skipWs()
            return s.getOrNull(i)
        }

        private fun next(): Char {
            skipWs()
            return s.getOrNull(i++) ?: fail("意外结束")
        }

        private fun expect(c: Char) {
            val got = next()
            if (got != c) fail("期望 '$c' 实得 '$got'")
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("segments_json 非法（位置 $i）：$msg")
    }
}
