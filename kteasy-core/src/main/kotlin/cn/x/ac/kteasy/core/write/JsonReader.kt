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
 * `ext` 受限文法的读取器：顶层对象 + 四种值形（string / number / true / false / 字符串数组）。
 *
 * **嵌套对象一律抛错**：出现即说明库里有脏数据，宁可这次写入失败并报人话，也不静默降级——
 * 降级会让 diff 与审计读到假值，那类问题事后不可发现（与 P3 同一判断口径）。
 */
internal class JsonReader(
    private val s: String,
) {
    private var i = 0

    fun objectOf(): Map<String, DraftValue> {
        expect('{')
        val out = LinkedHashMap<String, DraftValue>()
        if (peek() == '}') {
            next()
            tail()
            return out
        }
        while (true) {
            val key = string()
            expect(':')
            out[key] = value()
            when (next()) {
                ',' -> continue
                '}' -> break
                else -> throw IllegalStateException("ext JSON 结构非法（位置 $i）")
            }
        }
        tail()
        return out
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun peek(): Char? {
        skip()
        return s.getOrNull(i)
    }

    private fun next(): Char {
        skip()
        return s.getOrNull(i++) ?: throw IllegalStateException("ext JSON 意外结束")
    }

    private fun expect(c: Char) {
        val got = next()
        if (got != c) throw IllegalStateException("ext JSON 期望 '$c' 实得 '$got'（位置 $i）")
    }

    private fun tail() {
        skip()
        check(i == s.length) { "ext JSON 尾部有多余内容（位置 $i）" }
    }

    private fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw IllegalStateException("ext JSON 字符串未闭合")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> sb.append(escape())
                else -> sb.append(c)
            }
        }
    }

    private fun escape(): Char {
        val e = s.getOrNull(i++) ?: throw IllegalStateException("ext JSON 转义未闭合")
        return when (e) {
            '"' -> {
                '"'
            }

            '\\' -> {
                '\\'
            }

            '/' -> {
                '/'
            }

            'n' -> {
                '\n'
            }

            'r' -> {
                '\r'
            }

            't' -> {
                '\t'
            }

            'b' -> {
                '\b'
            }

            'f' -> {
                ' '
            }

            'u' -> {
                require(i + 4 <= s.length) { "ext JSON 反斜杠 u 转义不完整" }
                val code = s.substring(i, i + 4).toInt(16)
                i += 4
                code.toChar()
            }

            else -> {
                throw IllegalStateException("ext JSON 非法转义 \$e")
            }
        }
    }

    private fun value(): DraftValue =
        when (peek()) {
            '"' -> DraftValue.Text(string())
            't' -> literalWord("true", DraftValue.Bool(true))
            'f' -> literalWord("false", DraftValue.Bool(false))
            '[' -> array()
            '{' -> throw IllegalStateException("ext 不支持嵌套对象（位置 $i）")
            null -> throw IllegalStateException("ext JSON 值意外结束")
            else -> number()
        }

    private fun literalWord(
        word: String,
        result: DraftValue,
    ): DraftValue {
        expect(word[0])
        for (k in 1 until word.length) {
            val c = next()
            if (c != word[k]) throw IllegalStateException("ext JSON 字面量非法（期望 $word，位置 $i）")
        }
        return result
    }

    private fun number(): DraftValue {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val raw = s.substring(start, i)
        if (raw.isEmpty()) throw IllegalStateException("ext JSON 值起始非法（位置 $start：'${s.getOrNull(start)}'）")
        return DraftValue.Number(raw)
    }

    private fun array(): DraftValue {
        expect('[')
        val items = ArrayList<String>()
        if (peek() == ']') {
            next()
            return DraftValue.Many(items)
        }
        while (true) {
            if (peek() != '"') throw IllegalStateException("ext 数组只允许字符串元素（位置 $i）")
            items += string()
            when (next()) {
                ',' -> continue
                ']' -> return DraftValue.Many(items)
                else -> throw IllegalStateException("ext JSON 数组未闭合（位置 $i）")
            }
        }
    }
}
