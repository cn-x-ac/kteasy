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
 * EQL 递归下降解析器（⟨可逆⟩选型：手写，非 antlr/parboiled——见证据 §2）。
 *
 * 产出一棵与元数据无关的 [Query] 语法树，只做「语法形状 + 结构级护栏」校验（跳数、IN 元素数、
 * orderby 条数、limit 区间、嵌套深度）；一切「字段是否存在、类型是否匹配算子」的语义判定归块2。
 * 恶意输入（注入串、负 limit、跳数越界、函数嵌套炸弹）在本层即被 [EqlErrors] 拒绝，绝不产出可执行结构。
 */
object EqlParser {
    /** 顶层入口：词法 → 语法 → 必须抵达 EOF。 */
    fun parse(src: String): Query {
        val p = P(EqlLexer.tokenize(src))
        val q = p.parseQuery()
        p.expectEnd()
        return q
    }

    /** 关键字集合（IDENT 归一后命中即视作关键字，仅在结构位判定，操作数位仍当 api_name）。 */
    private val KW =
        setOf(
            "select",
            "from",
            "where",
            "order",
            "by",
            "group",
            "limit",
            "offset",
            "and",
            "or",
            "not",
            "in",
            "like",
            "has",
            "within",
            "asc",
            "desc",
            "true",
            "false",
            "null",
        )

    private val AGGS = mapOf("count" to AggFn.COUNT, "count_distinct" to AggFn.COUNT_DISTINCT, "sum" to AggFn.SUM, "avg" to AggFn.AVG, "min" to AggFn.MIN, "max" to AggFn.MAX)

    /** 游标：token 流 + 位置。方法名按语法产物组织。internal 以便同模块的 [DateTokens] 复用最小游标原语。 */
    internal class P(
        private val ts: List<Token>,
    ) {
        private var i = 0
        private var depth = 0

        private fun peek(): Token = ts[i]

        private fun peekWord(): String? = peek().takeIf { it.kind == Tk.IDENT }?.text?.lowercase()

        private fun advance(): Token = ts[i++]

        private fun wordIs(w: String): Boolean = peekWord() == w

        private fun eatWord(w: String) {
            if (!wordIs(w)) throw EqlErrors.syntax("期望关键字 '$w'，实得 '${peek().text}'", mapOf("pos" to peek().pos))
            advance()
        }

        private fun eat(kind: Tk): Token {
            if (peek().kind != kind) throw EqlErrors.syntax("期望 $kind，实得 ${peek().kind}('${peek().text}')", mapOf("pos" to peek().pos))
            return advance()
        }

        fun expectEnd() {
            if (peek().kind != Tk.EOF) throw EqlErrors.syntax("多余输入 '${peek().text}'", mapOf("pos" to peek().pos))
        }

        private fun enterNesting() {
            if (++depth > MAX_NESTING_DEPTH) throw EqlErrors.syntax("表达式嵌套超上限 $MAX_NESTING_DEPTH")
        }

        private fun exitNesting() {
            depth--
        }

        // ---------- query ----------

        fun parseQuery(): Query {
            val select =
                if (wordIs("select")) {
                    eatWord("select")
                    parseSelectList()
                } else {
                    emptyList()
                }
            eatWord("from")
            val obj = eat(Tk.IDENT).text
            var where: Expr? = null
            val orderBy = ArrayList<OrderKey>()
            val groupBy = ArrayList<FieldPath>()
            var limit: Int? = null
            var offset: Int? = null
            if (wordIs("where")) {
                eatWord("where")
                where = parseOr()
            }
            if (wordIs("group")) {
                eatWord("group")
                eatWord("by")
                do {
                    groupBy += parseFieldPath()
                } while (matchComma())
            }
            if (wordIs("order")) {
                eatWord("order")
                eatWord("by")
                do {
                    orderBy += parseOrderKey()
                } while (matchComma())
                if (orderBy.size > MAX_ORDER_TERMS) throw EqlErrors.syntax("order by 键数 ${orderBy.size} 超上限 $MAX_ORDER_TERMS")
            }
            if (wordIs("limit")) {
                eatWord("limit")
                limit = parseLimitValue()
            }
            if (wordIs("offset")) {
                eatWord("offset")
                offset = eat(Tk.NUMBER).text.toIntOrNull() ?: throw EqlErrors.syntax("offset 须为整数")
                if (offset < 0) throw EqlErrors.limit(offset, MAX_LIMIT)
            }
            return Query(obj, select, where, orderBy, limit, offset, groupBy)
        }

        private fun parseLimitValue(): Int {
            if (peek().kind == Tk.MINUS) {
                advance()
                throw EqlErrors.limit(-1, MAX_LIMIT) // 负 limit 拒绝
            }
            if (peek().kind == Tk.PLUS) advance()
            val v = eat(Tk.NUMBER).text.toIntOrNull() ?: throw EqlErrors.syntax("limit 须为整数")
            if (v > MAX_LIMIT) throw EqlErrors.limit(v, MAX_LIMIT)
            return v
        }

        private fun matchComma(): Boolean {
            if (peek().kind == Tk.COMMA) {
                advance()
                return true
            }
            return false
        }

        // ---------- select ----------

        private fun parseSelectList(): List<SelectItem> {
            val items = ArrayList<SelectItem>()
            do {
                items += parseSelectItem()
            } while (matchComma())
            return items
        }

        private fun parseSelectItem(): SelectItem {
            if (peek().kind == Tk.STAR) {
                advance()
                return SelectItem.Star
            }
            val word = peekWord()
            if (word != null && AGGS.containsKey(word) && ts.getOrNull(i + 1)?.kind == Tk.LPAREN) {
                return SelectItem.Aggregate(parseAgg())
            }
            return SelectItem.Field(parseFieldPath())
        }

        private fun parseAgg(): Agg {
            val fn = AGGS[eat(Tk.IDENT).text.lowercase()]!!
            eat(Tk.LPAREN)
            val arg =
                if (peek().kind == Tk.STAR) {
                    advance()
                    null
                } else {
                    parseFieldPath()
                }
            eat(Tk.RPAREN)
            return Agg(fn, arg)
        }

        private fun parseOrderKey(): OrderKey {
            val word = peekWord()
            val agg =
                if (word != null && AGGS.containsKey(word) && ts.getOrNull(i + 1)?.kind == Tk.LPAREN) {
                    parseAgg()
                } else {
                    null
                }
            val path = agg?.arg ?: if (agg == null) parseFieldPath() else null
            val dir =
                when (peekWord()) {
                    "asc" -> {
                        advance()
                        SortDir.ASC
                    }

                    "desc" -> {
                        advance()
                        SortDir.DESC
                    }

                    else -> {
                        SortDir.ASC
                    }
                }
            return OrderKey(path, agg, dir)
        }

        // ---------- field-path ----------

        private fun parseFieldPath(): FieldPath {
            val segs = ArrayList<String>()
            segs += eat(Tk.IDENT).text
            while (peek().kind == Tk.DOT) {
                advance()
                segs += eat(Tk.IDENT).text
            }
            val hops = segs.size - 1
            if (hops > MAX_FIELD_HOPS) throw EqlErrors.tooDeep(hops, MAX_FIELD_HOPS)
            return FieldPath(segs)
        }

        // ---------- where ----------

        private fun parseOr(): Expr {
            enterNesting()
            val parts = ArrayList<Expr>()
            parts += parseAnd()
            while (wordIs("or")) {
                advance()
                parts += parseAnd()
            }
            exitNesting()
            return if (parts.size == 1) parts[0] else Expr.Or(parts)
        }

        private fun parseAnd(): Expr {
            val parts = ArrayList<Expr>()
            parts += parsePred()
            while (startsPred()) {
                if (wordIs("and")) advance() // 显式 AND 可选吃掉；隐式 AND 直接接下一个谓词
                parts += parsePred()
            }
            return if (parts.size == 1) parts[0] else Expr.And(parts)
        }

        /**
         * 当前是否处在新谓词开头（决定 andExpr 里隐式/显式 AND 的续接边界）。
         *
         * 保留 `and`（返回 true，由 [parseAnd] 消费显式 AND）；排除 `or`（交 [parseOr] 的 OR 循环）、
         * 以及一切子句/排序关键字（`from/where/order/group/by/limit/offset/asc/desc`）——命中即本 AND 结束。
         */
        private fun startsPred(): Boolean =
            when (peek().kind) {
                Tk.LPAREN -> true
                Tk.IDENT -> peekWord() !in STOP_WORDS
                else -> false
            }

        private fun parsePred(): Expr {
            enterNesting()
            val result =
                if (wordIs("not")) {
                    advance()
                    Expr.Not(parsePredOperand())
                } else {
                    parsePredOperand()
                }
            exitNesting()
            return result
        }

        private fun parsePredOperand(): Expr {
            if (peek().kind == Tk.LPAREN) {
                advance()
                val inner = parseOr()
                eat(Tk.RPAREN)
                return inner
            }
            return parseLeaf()
        }

        private fun parseLeaf(): Expr {
            if (wordIs("has") && ts.getOrNull(i + 1)?.kind == Tk.LPAREN) {
                advance()
                eat(Tk.LPAREN)
                val path = parseFieldPath()
                val key = if (matchComma()) parseLiteral() else null
                eat(Tk.RPAREN)
                return Expr.Has(path, key)
            }
            val path = parseFieldPath()
            // 比较运算符
            val op = cmpOpOrNull()
            if (op != null) {
                advance()
                val rhs = parseCmpRhs()
                return Expr.Cmp(path, op, rhs)
            }
            return when (peekWord()) {
                "in" -> {
                    advance()
                    eat(Tk.LPAREN)
                    val vs = ArrayList<Literal>()
                    do {
                        vs += parseLiteral()
                    } while (matchComma())
                    eat(Tk.RPAREN)
                    if (vs.size > MAX_IN_TERMS) throw EqlErrors.syntax("in 元素数 ${vs.size} 超上限 $MAX_IN_TERMS")
                    Expr.In(path, vs)
                }

                "like" -> {
                    advance()
                    val s = eat(Tk.STRING).text
                    Expr.Like(path, likePrefix(s))
                }

                "within" -> {
                    advance()
                    Expr.Within(path, DateTokens.parse(this))
                }

                else -> {
                    if (peek().kind == Tk.TILDE) {
                        advance()
                        Expr.Match(path, eat(Tk.STRING).text)
                    } else {
                        throw EqlErrors.syntax("字段 '${path.tail}' 后缺运算符", mapOf("pos" to peek().pos))
                    }
                }
            }
        }

        private fun cmpOpOrNull(): CmpOp? =
            when (peek().kind) {
                Tk.EQ -> CmpOp.EQ
                Tk.NE -> CmpOp.NE
                Tk.GT -> CmpOp.GT
                Tk.GE -> CmpOp.GE
                Tk.LT -> CmpOp.LT
                Tk.LE -> CmpOp.LE
                else -> null
            }

        /** `like` 仅前缀 %：尾部裸 % 剥离；无 % 亦按前缀处理（图纸 03 §1「仅前缀%」）。 */
        private fun likePrefix(
            s: String,
        ): String = if (s.endsWith("%") && !s.endsWith("\\%")) s.dropLast(1) else s

        private fun parseCmpRhs(): CmpRhs {
            val kw = peekWord()
            val literalAhead =
                peek().kind == Tk.STRING ||
                    peek().kind == Tk.NUMBER ||
                    peek().kind == Tk.MINUS ||
                    peek().kind == Tk.PLUS ||
                    kw == "true" || kw == "false" || kw == "null"
            if (literalAhead) return CmpRhs.Lit(parseLiteral())
            return CmpRhs.Path(parseFieldPath())
        }

        private fun parseLiteral(): Literal {
            val neg =
                when (peek().kind) {
                    Tk.MINUS -> {
                        advance()
                        "-"
                    }

                    Tk.PLUS -> {
                        advance()
                        ""
                    }

                    else -> {
                        ""
                    }
                }
            return when {
                peek().kind == Tk.STRING -> {
                    Literal.Str(eat(Tk.STRING).text)
                }

                peek().kind == Tk.NUMBER -> {
                    Literal.Num(neg + eat(Tk.NUMBER).text)
                }

                wordIs("true") -> {
                    advance()
                    Literal.Bool(true)
                }

                wordIs("false") -> {
                    advance()
                    Literal.Bool(false)
                }

                wordIs("null") -> {
                    advance()
                    if (neg.isNotEmpty()) throw EqlErrors.syntax("null 不可带符号")
                    Literal.Null
                }

                else -> {
                    throw EqlErrors.syntax("期望字面量，实得 '${peek().text}'", mapOf("pos" to peek().pos))
                }
            }
        }

        // 供 DateTokens 复用：暴露最小游标操作
        internal fun peekTok(): Token = peek()

        internal fun adv(): Token = advance()

        internal companion object {
            /** 出现在谓词续接位即代表「当前 AND 结束」的关键字。 */
            val STOP_WORDS: Set<String> = setOf("or", "from", "where", "order", "group", "by", "limit", "offset", "asc", "desc")
        }
    }
}
