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

/*
 * EQL 词法器：把源文本切成 [Token] 流。刻意做得「哑」——所有裸词（含 select/from 等潜在关键字）
 * 一律产 [Tk.IDENT]，关键字 vs api_name 的判定交给 [EqlParser] 按上下文决定。理由：api_name 受
 * `^[a-z][a-z0-9_]{2,47}$` 约束、天然覆盖 count/min 等词，若在词法器硬保留关键字，恰好叫 count/min
 * 的字段就无法查询。位置敏感消歧更稳。
 *
 * 负号不并入数字（- 单独产 [Tk.MINUS]），由解析器在「值前缀位」「日期偏移位」识别，
 * 从而 !=（!+=）与负数、count_distinct（下划线在 IDENT 内）互不干扰。
 */

/** 词法单元类别。 */
enum class Tk {
    IDENT,
    STRING,
    NUMBER,
    LPAREN,
    RPAREN,
    COMMA,
    DOT,
    COLON,
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    TILDE,
    STAR,
    PLUS,
    MINUS,
    EOF,
}

/**
 * 一个词法单元。
 *
 * @property kind 类别
 * @property text 归一后的文本：STRING 已剥引号并还原转义；其余为原文子串（IDENT 原样，解析器自行小写）
 * @property pos 起始偏移（供语法错误定位）
 */
data class Token(
    val kind: Tk,
    val text: String,
    val pos: Int,
)

/**
 * 把 EQL 文本扫描为 token 列表（末尾恒附 [Tk.EOF]）。只负责「形状合法」，不校验语义。
 * 非法字符 / 未闭合字符串 / 悬空 ! → [EqlErrors.syntax]。
 */
object EqlLexer {
    private fun err(
        t: String,
        pos: Int,
    ): Nothing = throw EqlErrors.syntax(t, mapOf("pos" to pos))

    fun tokenize(src: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c.isWhitespace() -> {
                    i++
                }

                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Token(Tk.IDENT, src.substring(s, i), s)
                }

                c.isDigit() -> {
                    val s = i
                    while (i < n && src[i].isDigit()) i++
                    if (i < n && src[i] == '.' && i + 1 < n && src[i + 1].isDigit()) {
                        i++
                        while (i < n && src[i].isDigit()) i++
                    }
                    out += Token(Tk.NUMBER, src.substring(s, i), s)
                }

                c == '\'' || c == '"' -> {
                    val (tok, next) = readString(src, i)
                    out += tok
                    i = next
                }

                else -> {
                    val (k, len) = symbol(src, i)
                    out += Token(k, src.substring(i, i + len), i)
                    i += len
                }
            }
        }
        out += Token(Tk.EOF, "", n)
        return out
    }

    /** 读单/双引号字符串，还原转义（\\ \' \" \n \t \%）；返回 (token, 闭合引号之后的下标)。未闭合报语法错。 */
    private fun readString(
        src: String,
        start: Int,
    ): Pair<Token, Int> {
        val quote = src[start]
        val sb = StringBuilder()
        var i = start + 1
        var closed = false
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                when (val e = src[i + 1]) {
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '%' -> sb.append('%')
                    else -> sb.append('\\').append(e)
                }
                i += 2
                continue
            }
            if (c == quote) {
                closed = true
                i++
                break
            }
            sb.append(c)
            i++
        }
        if (!closed) err("字符串未闭合", start)
        return Token(Tk.STRING, sb.toString(), start) to i
    }

    private fun symbol(
        src: String,
        i: Int,
    ): Pair<Tk, Int> {
        val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
        return when {
            two == "!=" -> {
                Tk.NE to 2
            }

            two == ">=" -> {
                Tk.GE to 2
            }

            two == "<=" -> {
                Tk.LE to 2
            }

            else -> {
                when (src[i]) {
                    '(' -> Tk.LPAREN to 1
                    ')' -> Tk.RPAREN to 1
                    ',' -> Tk.COMMA to 1
                    '.' -> Tk.DOT to 1
                    ':' -> Tk.COLON to 1
                    '=' -> Tk.EQ to 1
                    '>' -> Tk.GT to 1
                    '<' -> Tk.LT to 1
                    '~' -> Tk.TILDE to 1
                    '*' -> Tk.STAR to 1
                    '+' -> Tk.PLUS to 1
                    '-' -> Tk.MINUS to 1
                    else -> err("非法字符 '${src[i]}'", i)
                }
            }
        }
    }
}
