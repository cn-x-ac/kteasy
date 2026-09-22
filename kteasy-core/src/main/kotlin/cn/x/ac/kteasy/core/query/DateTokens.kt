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
 * `within <dateToken>` 的**语法**解析（图纸 03 §3 token 名录；块3 负责区间展开，本文件只做词形识别）。
 *
 * ⟨可逆⟩ 字面词形（本卡选定、记入证据 §2；块3 若需可微调）：
 *   相对窗口  last|next|ago|after + 整数 + 单位(d|m|y|h)，合并为单个 IDENT：`last7d`、`next3m`、`after2h`
 *   本周期    thisw | thism | thisq | thisy（周/月/季/年）
 *   本/每逢刻 thiswed:N | thismonth:N | thismay:N | everywed:N | everymonth:N | everymay:N
 *   精确偏移  exactday | exactmonth | exactyear | exacthour  后接可选 [+-]N（如卡面「指定-5时」＝`exacthour-5`）
 *   区间      range(b, b)，b ∈ { now | 'yyyy-MM-dd[THH:mm:ss]' | 相对词 }
 * 一切越界/未知词形 → [EqlErrors.syntax]（结构级护栏，语义合法性交块3）。
 */
internal object DateTokens {
    private val RELATIVE = Regex("^(last|next|ago|after)(\\d+)([dmyh])$")

    fun parse(
        p: EqlParser.P,
    ): DateToken {
        val t = p.peekTok()
        if (t.kind != Tk.IDENT) throw EqlErrors.syntax("期望日期 token，实得 '${t.text}'", mapOf("pos" to t.pos))
        val w = t.text.lowercase()
        RELATIVE.matchEntire(w)?.let { m ->
            p.adv()
            return DateToken.Relative(
                direction = relDir(m.groupValues[1]),
                n = m.groupValues[2].toInt(),
                unit = relUnit(m.groupValues[3]),
            )
        }
        return when (w) {
            "thisw" -> {
                p.adv()
                DateToken.This(ThisPeriod.WEEK)
            }

            "thism" -> {
                p.adv()
                DateToken.This(ThisPeriod.MONTH)
            }

            "thisq" -> {
                p.adv()
                DateToken.This(ThisPeriod.QUARTER)
            }

            "thisy" -> {
                p.adv()
                DateToken.This(ThisPeriod.YEAR)
            }

            "range" -> {
                parseRange(p)
            }

            "thiswed", "thismonth", "thismay" -> {
                val scale = cycleScale(w)
                p.adv()
                DateToken.ThisOn(scale, readIndex(p))
            }

            "everywed", "everymonth", "everymay" -> {
                val scale = cycleScale(w)
                p.adv()
                DateToken.Every(scale, readIndex(p))
            }

            else -> {
                if (w.startsWith("exact")) parseExact(p, w) else throw EqlErrors.syntax("无法识别的日期 token '$w'", mapOf("pos" to t.pos))
            }
        }
    }

    private fun parseRange(
        p: EqlParser.P,
    ): DateToken.Range {
        p.adv() // range
        expect(p, Tk.LPAREN)
        val from = boundary(p)
        expect(p, Tk.COMMA)
        val to = boundary(p)
        expect(p, Tk.RPAREN)
        return DateToken.Range(from, to)
    }

    private fun boundary(
        p: EqlParser.P,
    ): DateBoundary {
        val t = p.peekTok()
        return when {
            t.kind == Tk.STRING -> {
                p.adv()
                DateBoundary(t.text)
            }

            t.kind == Tk.IDENT -> {
                p.adv()
                DateBoundary(t.text.lowercase())
            }

            else -> {
                throw EqlErrors.syntax("range 端点须为 now / ISO 串 / 相对词", mapOf("pos" to t.pos))
            }
        }
    }

    private fun parseExact(
        p: EqlParser.P,
        w: String,
    ): DateToken.Exact {
        val kind =
            when (w) {
                "exactday" -> ExactKind.DAY
                "exactmonth" -> ExactKind.MONTH
                "exactyear" -> ExactKind.YEAR
                "exacthour" -> ExactKind.HOUR
                else -> throw EqlErrors.syntax("未知的精确偏移类型 '$w'")
            }
        p.adv()
        var sign = 1
        when (p.peekTok().kind) {
            Tk.PLUS -> {
                p.adv()
            }

            Tk.MINUS -> {
                sign = -1
                p.adv()
            }

            else -> {}
        }
        val n = number(p)
        return DateToken.Exact(kind, sign * n)
    }

    /** `this`/`every` 系列冒号后的 N（正整数，前无符号）。 */
    private fun readIndex(
        p: EqlParser.P,
    ): Int {
        expect(p, Tk.COLON)
        return number(p)
    }

    private fun number(
        p: EqlParser.P,
    ): Int =
        run {
            val t = p.peekTok()
            if (t.kind != Tk.NUMBER) throw EqlErrors.syntax("期望整数，实得 '${t.text}'", mapOf("pos" to t.pos))
            p.adv()
            t.text.toIntOrNull() ?: throw EqlErrors.syntax("整数越界 '${t.text}'")
        }

    private fun expect(
        p: EqlParser.P,
        kind: Tk,
    ) {
        val t = p.peekTok()
        if (t.kind != kind) throw EqlErrors.syntax("期望 $kind，实得 ${t.kind}", mapOf("pos" to t.pos))
        p.adv()
    }

    private fun relDir(
        s: String,
    ): RelativeDirection =
        when (s) {
            "last" -> RelativeDirection.LAST
            "next" -> RelativeDirection.NEXT
            "ago" -> RelativeDirection.AGO
            else -> RelativeDirection.AFTER
        }

    private fun relUnit(
        s: String,
    ): CalendarUnit =
        when (s) {
            "d" -> CalendarUnit.DAY
            "m" -> CalendarUnit.MONTH
            "y" -> CalendarUnit.YEAR
            else -> CalendarUnit.HOUR
        }

    private fun cycleScale(
        w: String,
    ): CycleScale =
        when (w.removePrefix("this").removePrefix("every")) {
            "wed" -> CycleScale.WEEKDAY
            "month" -> CycleScale.MONTH_DAY
            else -> CycleScale.YEAR_MONTH
        }
}
