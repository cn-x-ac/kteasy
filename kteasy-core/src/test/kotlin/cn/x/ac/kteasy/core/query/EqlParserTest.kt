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

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 步骤卡 M1-05 块1 · EQL 解析器 L1（纯语法面，不连库、不碰元数据）：
 * ① 合法查询 → 期望 AST 结构；② 运算符优先级/结合（AND 紧于 OR、括号、隐式 AND）；
 * ③ 点链/字段/谓词各形态（cmp/in/like/match/has/within）；④ 日期 token 词形；⑤ 聚合 + group by；
 * ⑥ 结构级护栏与恶意输入（注入串、负/超限 limit、跳数越界、嵌套炸弹、未闭合、多余输入）全部拒绝。
 * 元数据语义（字段是否存在、类型匹配）归块2，此处不断言。
 */
class EqlParserTest {
    private fun eqlError(
        src: String,
    ): KnownKteasyException =
        try {
            EqlParser.parse(src)
            throw AssertionError("期望抛 EQL 错误但未抛：$src")
        } catch (e: KnownKteasyException) {
            e
        }

    private fun errorId(
        e: KnownKteasyException,
    ): String? = (e.data as? Map<*, *>)?.get("error_id") as? String

    @Test
    fun `完整查询各子句结构正确`() {
        val q =
            EqlParser.parse(
                "select name, amount, count(id) from order " +
                    "where status = 'A' and amount > 50000 " +
                    "group by name order by amount desc, name asc limit 50 offset 10",
            )
        assertThat(q.fromObject).isEqualTo("order")
        assertThat(q.select).hasSize(3)
        assertThat(q.select[0]).isEqualTo(SelectItem.Field(FieldPath(listOf("name"))))
        assertThat(q.select[2]).isEqualTo(SelectItem.Aggregate(Agg(AggFn.COUNT, FieldPath(listOf("id")))))
        assertThat(q.where).isEqualTo(
            Expr.And(
                listOf(
                    Expr.Cmp(FieldPath(listOf("status")), CmpOp.EQ, CmpRhs.Lit(Literal.Str("A"))),
                    Expr.Cmp(FieldPath(listOf("amount")), CmpOp.GT, CmpRhs.Lit(Literal.Num("50000"))),
                ),
            ),
        )
        assertThat(q.groupBy).containsExactly(FieldPath(listOf("name")))
        assertThat(q.orderBy).containsExactly(
            OrderKey(FieldPath(listOf("amount")), null, SortDir.DESC),
            OrderKey(FieldPath(listOf("name")), null, SortDir.ASC),
        )
        assertThat(q.limit).isEqualTo(50)
        assertThat(q.offset).isEqualTo(10)
    }

    @Test
    fun `未写 select 与 where 时为空集合与 null`() {
        val q = EqlParser.parse("from customer")
        assertThat(q.fromObject).isEqualTo("customer")
        assertThat(q.select).isEmpty()
        assertThat(q.where).isNull()
        assertThat(q.limit).isNull()
    }

    @Test
    fun `AND 优先级紧于 OR 且括号可改写`() {
        assertThat(EqlParser.parse("from o where a = 1 or b = 2 and c = 3").where).isEqualTo(
            Expr.Or(
                listOf(
                    Expr.Cmp(FieldPath(listOf("a")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("1"))),
                    Expr.And(
                        listOf(
                            Expr.Cmp(FieldPath(listOf("b")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("2"))),
                            Expr.Cmp(FieldPath(listOf("c")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("3"))),
                        ),
                    ),
                ),
            ),
        )
        // 括号把 OR 提前：(a=1 or b=2) c=3（隐式 AND）
        assertThat(EqlParser.parse("from o where (a = 1 or b = 2) c = 3").where).isEqualTo(
            Expr.And(
                listOf(
                    Expr.Or(
                        listOf(
                            Expr.Cmp(FieldPath(listOf("a")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("1"))),
                            Expr.Cmp(FieldPath(listOf("b")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("2"))),
                        ),
                    ),
                    Expr.Cmp(FieldPath(listOf("c")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("3"))),
                ),
            ),
        )
    }

    @Test
    fun `NOT 前缀与相邻谓词隐式 AND`() {
        val q = EqlParser.parse("from o where not a = 1 b = 2")
        assertThat(q.where).isEqualTo(
            Expr.And(
                listOf(
                    Expr.Not(Expr.Cmp(FieldPath(listOf("a")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("1")))),
                    Expr.Cmp(FieldPath(listOf("b")), CmpOp.EQ, CmpRhs.Lit(Literal.Num("2"))),
                ),
            ),
        )
    }

    @Test
    fun `字段对字段比较与点链`() {
        val q = EqlParser.parse("from o where a.b.c = x.y")
        assertThat(q.where).isEqualTo(
            Expr.Cmp(FieldPath(listOf("a", "b", "c")), CmpOp.EQ, CmpRhs.Path(FieldPath(listOf("x", "y")))),
        )
    }

    @Test
    fun `in like match has 谓词`() {
        val inQ = EqlParser.parse("from o where status in ('A','B','C')")
        assertThat((inQ.where as Expr.In).values).containsExactly(Literal.Str("A"), Literal.Str("B"), Literal.Str("C"))

        val likeQ = EqlParser.parse("from o where name like 'ab%'")
        assertThat(likeQ.where).isEqualTo(Expr.Like(FieldPath(listOf("name")), "ab"))

        val matchQ = EqlParser.parse("from o where name ~ 'kehu'")
        assertThat(matchQ.where).isEqualTo(Expr.Match(FieldPath(listOf("name")), "kehu"))

        val hasQ = EqlParser.parse("from o where has(tags, 'x')")
        assertThat(hasQ.where).isEqualTo(Expr.Has(FieldPath(listOf("tags")), Literal.Str("x")))
        val hasKeyQ = EqlParser.parse("from o where has(meta)")
        assertThat(hasKeyQ.where).isEqualTo(Expr.Has(FieldPath(listOf("meta")), null))
    }

    @Test
    fun `日期 within token 词形解析`() {
        assertThat(EqlParser.parse("from o where created within last7d").where).isEqualTo(
            Expr.Within(FieldPath(listOf("created")), DateToken.Relative(RelativeDirection.LAST, 7, CalendarUnit.DAY)),
        )
        assertThat((EqlParser.parse("from o where t within thisw").where as Expr.Within).token).isEqualTo(
            DateToken.This(ThisPeriod.WEEK),
        )
        assertThat((EqlParser.parse("from o where t within thiswed:3").where as Expr.Within).token).isEqualTo(
            DateToken.ThisOn(CycleScale.WEEKDAY, 3),
        )
        assertThat((EqlParser.parse("from o where t within exacthour-5").where as Expr.Within).token).isEqualTo(
            DateToken.Exact(ExactKind.HOUR, -5),
        )
        assertThat((EqlParser.parse("from o where t within range('2026-01-01', now)").where as Expr.Within).token).isEqualTo(
            DateToken.Range(DateBoundary("2026-01-01"), DateBoundary("now")),
        )
    }

    @Test
    fun `聚合 count 星 与 sum 点链`() {
        val q = EqlParser.parse("select count(*), sum(amount) from o group by dept")
        assertThat(q.select[0]).isEqualTo(SelectItem.Aggregate(Agg(AggFn.COUNT, null)))
        assertThat(q.select[1]).isEqualTo(SelectItem.Aggregate(Agg(AggFn.SUM, FieldPath(listOf("amount")))))
        assertThat(q.groupBy).containsExactly(FieldPath(listOf("dept")))
    }

    @Test
    fun `注入串被当作字面量而非 SQL`() {
        // 经典注入尝试：值里的引号转义后整体只是 Str 字面量，块4 渲染时进绑定参数，永不拼进 SQL 串
        val q = EqlParser.parse("from o where name = 'x\\'; DROP TABLE md_object;--'")
        assertThat(q.where).isEqualTo(
            Expr.Cmp(FieldPath(listOf("name")), CmpOp.EQ, CmpRhs.Lit(Literal.Str("x'; DROP TABLE md_object;--"))),
        )
    }

    @Test
    fun `恶意输入矩阵全部拒绝且带 error_id`() {
        assertThat(errorId(eqlError("from o where a = 1 limit -5"))).isEqualTo(EqlErrors.ID_LIMIT)
        assertThat(errorId(eqlError("from o where a = 1 limit 9999999"))).isEqualTo(EqlErrors.ID_LIMIT)
        assertThat(errorId(eqlError("from o where a.b.c.d.e = 1"))).isEqualTo(EqlErrors.ID_TOO_DEEP)
        assertThat(errorId(eqlError("from o where not not"))).isEqualTo(EqlErrors.ID_SYNTAX)
        assertThat(errorId(eqlError("from o where name = 'unclosed"))).isEqualTo(EqlErrors.ID_SYNTAX)
        assertThat(errorId(eqlError("from o garbage tail"))).isEqualTo(EqlErrors.ID_SYNTAX)
        assertThat(errorId(eqlError("from o where a ! 1"))).isEqualTo(EqlErrors.ID_SYNTAX)
        // in 元素超 200
        val bigIn = (1..201).joinToString(",") { "'v$it'" }
        assertThat(errorId(eqlError("from o where a in ($bigIn)"))).isEqualTo(EqlErrors.ID_SYNTAX)
    }

    @Test
    fun `函数嵌套炸弹被深度护栏拦截`() {
        val bomb = "from o where " + "(".repeat(MAX_NESTING_DEPTH + 5) + "a = 1" + ")".repeat(MAX_NESTING_DEPTH + 5)
        val e = eqlError(bomb)
        assertThat(e.apiError).isEqualTo(ApiError.INVALID_PARAM)
        assertThat(errorId(e)).isEqualTo(EqlErrors.ID_SYNTAX)
    }
}
