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

import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.SystemColumns
import cn.x.ac.kteasy.core.schema.dialect.Fragment
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/*
 * 块4 渲染器：逻辑计划 [QueryPlan] + [SchemaProvider] + 时钟 → 一条**双方言参数化** [Fragment]。
 *
 * 红线④：值全进绑定参数，字面量绝不进 SQL 串。红线⑤：方言差异只经 provider 口，本文件无 `if(isMySQL)`。
 * 谓词渲染统一返回 SQL 字符串、绑定的值集中写全局 [params]（避免参数名在多层片段间错位）。
 *
 * 标识符沿用物化引擎建表时的裸名约定（与 CREATE 侧一致、不加引号）；`SELECT *`（全业务列）交 server 结果映射按元数据裁剪。
 * ⟨已知限制，证据 §2⟩：`arrayContains` 的 PG 参数名为方言内固定单名，一条查询多处数组包含谓词会重名（M1-05 覆盖用例单实例）。
 */
class SqlRenderer(
    private val provider: SchemaProvider,
    private val now: ZonedDateTime,
) {
    private val params = LinkedHashMap<String, Any?>()
    private var seq = 0

    fun render(
        plan: QueryPlan,
    ): Fragment {
        params.clear()
        seq = 0
        val sb = StringBuilder()
        sb.append("SELECT ").append(selectClause(plan))
        sb
            .append(" FROM ")
            .append(qual(plan.root.logicalTable))
            .append(" ")
            .append(plan.root.alias)
        plan.joins.forEach { sb.append(renderJoin(it)) }
        plan.where?.let { sb.append(" WHERE ").append(renderExpr(it)) }
        if (plan.groupBy.isNotEmpty()) sb.append(" GROUP BY ").append(plan.groupBy.joinToString(", ") { locExpr(it) })
        if (plan.orderBy.isNotEmpty()) sb.append(renderOrderBy(plan))
        sb.append(" LIMIT ").append(plan.limit ?: DEFAULT_LIMIT.toString())
        plan.offset?.let { sb.append(" OFFSET ").append(it) }
        return Fragment(sb.toString(), LinkedHashMap(params))
    }

    // ---------- FROM / SELECT ----------

    private fun renderJoin(
        j: Join,
    ): String = " LEFT JOIN ${qual(j.logicalTable)} ${j.alias} ON ${j.onLeftAlias}.${j.onLeftColumn} = ${j.alias}.${SystemColumns.ID}"

    private fun qual(
        logicalTable: String,
    ): String = provider.namespace.qualified(LogicalArea.ENTITY, logicalTable)

    private fun selectClause(
        plan: QueryPlan,
    ): String {
        if (plan.select.size == 1 && plan.select[0] is SelectPlan.AllBusiness) return "${plan.root.alias}.*"
        return plan
            .select
            .map { sp ->
                when (sp) {
                    is SelectPlan.AllBusiness -> "${plan.root.alias}.*"
                    is SelectPlan.Value -> locExpr(sp.location)
                    is SelectPlan.Aggregate -> aggExpr(sp.agg.fn, sp.location)
                }
            }.joinToString(", ")
    }

    private fun aggExpr(
        fn: AggFn,
        loc: ValueLocation?,
    ): String {
        val inner = loc?.let { locExpr(it) } ?: "*"
        return when (fn) {
            AggFn.COUNT -> "COUNT($inner)"
            AggFn.COUNT_DISTINCT -> "COUNT(DISTINCT $inner)"
            AggFn.SUM -> "SUM($inner)"
            AggFn.AVG -> "AVG($inner)"
            AggFn.MIN -> "MIN($inner)"
            AggFn.MAX -> "MAX($inner)"
        }
    }

    private fun renderOrderBy(
        plan: QueryPlan,
    ): String {
        val keys =
            plan.orderBy.map { op ->
                val target =
                    when {
                        op.agg != null -> aggExpr(op.agg.fn, op.location)
                        op.location != null -> locExpr(op.location)
                        else -> error("空排序键")
                    }
                val dir = if (op.dir == SortDir.DESC) "DESC" else "ASC"
                "($target IS NULL) ASC, $target $dir" // 空值恒末（⟨可逆⟩）
            }
        return " ORDER BY " + keys.joinToString(", ")
    }

    // ---------- 取值定位 → SQL ----------

    private fun locExpr(
        loc: ValueLocation,
    ): String =
        when (loc) {
            is ValueLocation.Column -> "${loc.alias}.${loc.column}"
            is ValueLocation.Ext -> provider.json.extractTyped("${loc.alias}.${SystemColumns.EXT}", loc.key, loc.cast).sql
        }

    // ---------- 谓词（返回 SQL 字符串，值写全局 params） ----------

    private fun renderExpr(
        e: RExpr,
    ): String =
        when (e) {
            is RExpr.And -> e.parts.joinToString(" AND ") { "(${renderExpr(it)})" }
            is RExpr.Or -> e.parts.joinToString(" OR ") { "(${renderExpr(it)})" }
            is RExpr.Not -> "NOT (${renderExpr(e.inner)})"
            is RExpr.IsNull -> "${locExpr(e.location)} ${if (e.negated) "IS NOT NULL" else "IS NULL"}"
            is RExpr.Cmp -> renderCmp(e)
            is RExpr.In -> renderIn(e)
            is RExpr.Like -> renderLike(e)
            is RExpr.Match -> renderMatch(e)
            is RExpr.HasKey -> provider.json.predicateExists("${e.ext.alias}.${SystemColumns.EXT}", e.ext.key).sql
            is RExpr.ArrayMember -> renderArrayMember(e)
            is RExpr.Within -> renderWithin(e)
            is RExpr.N2n -> renderN2n(e)
        }

    private fun renderCmp(
        e: RExpr.Cmp,
    ): String {
        val left = locExpr(e.location)
        return when (val rhs = e.rhs) {
            is Rhs.Loc -> {
                "$left ${opSym(e.op)} ${locExpr(rhs.location)}"
            }

            is Rhs.Val -> {
                val key = bindVal(rhs.operand.cast, rhs.operand.literal)
                "$left ${opSym(e.op)} :$key"
            }
        }
    }

    private fun renderIn(
        e: RExpr.In,
    ): String {
        val holders = e.values.joinToString(", ") { ":${bindVal(e.cast, it)}" }
        return "${locExpr(e.location)} IN ($holders)"
    }

    private fun renderLike(
        e: RExpr.Like,
    ): String {
        val key = "p${seq++}"
        params[key] = e.prefix.lowercase() + "%"
        return provider.fullText.likePredicate(locExpr(e.location), key).sql
    }

    private fun renderMatch(
        e: RExpr.Match,
    ): String {
        val key = "p${seq++}"
        params[key] = e.term.lowercase() + "%"
        val target = e.pinyin?.let { "${it.alias}.${it.column}" } ?: locExpr(e.fallback)
        return provider.fullText.likePredicate(target, key).sql
    }

    private fun renderArrayMember(
        e: RExpr.ArrayMember,
    ): String {
        val f = provider.json.arrayContains("${e.ext.alias}.${SystemColumns.EXT}", e.ext.key, strOf(e.value))
        params.putAll(f.params)
        return f.sql
    }

    private fun renderWithin(
        e: RExpr.Within,
    ): String =
        when (val res = DateIntervals.resolve(e.token, now)) {
            is DateResolution.Window -> {
                val left = locExpr(e.location)
                val isDate = e.logicalType == LogicalType.DATE
                val fk = "p${seq++}"
                val tk = "p${seq++}"
                params[fk] = boundInstant(res.from, isDate)
                params[tk] = boundInstant(res.to, isDate)
                "$left >= :$fk AND $left < :$tk"
            }

            is DateResolution.Recurrence -> {
                val expr = locExpr(e.location)
                val part =
                    when (res.scale) {
                        CycleScale.WEEKDAY -> provider.date.dayOfWeek(expr).sql
                        CycleScale.MONTH_DAY -> provider.date.dayOfMonth(expr).sql
                        CycleScale.YEAR_MONTH -> provider.date.monthOfYear(expr).sql
                    }
                val k = "p${seq++}"
                params[k] = res.index
                "$part = :$k"
            }
        }

    private fun renderN2n(
        e: RExpr.N2n,
    ): String {
        val s = e.n2n
        val rs = "rs_${s.targetAlias}"
        val conds = ArrayList<String>()
        conds += "$rs.${s.srcColumn} = ${s.hostAlias}.${SystemColumns.ID}"
        if (e.idFilter != null) {
            val k = "p${seq++}"
            params[k] = strOf(requireNotNull(e.idFilter))
            conds += "$rs.${s.dstColumn} = :$k"
        }
        val sb = StringBuilder("EXISTS(SELECT 1 FROM ${provider.namespace.qualified(LogicalArea.RELATION, s.relTable)} $rs")
        if (e.inner != null) {
            sb.append(" JOIN ${qual(s.targetTable)} ${s.targetAlias} ON $rs.${s.dstColumn} = ${s.targetAlias}.${SystemColumns.ID}")
            conds += renderExpr(e.inner)
        }
        sb.append(" WHERE ").append(conds.joinToString(" AND ")).append(")")
        return sb.toString()
    }

    // ---------- 参数绑定 ----------

    private fun bindVal(
        cast: ValueCast,
        lit: Literal,
    ): String {
        val key = "p${seq++}"
        params[key] = toJdbc(cast, lit)
        return key
    }

    private fun boundInstant(
        z: ZonedDateTime,
        isDate: Boolean,
    ): Any = if (isDate) LocalDate.of(z.year, z.monthValue, z.dayOfMonth) else z.withZoneSameInstant(ZoneOffset.UTC)

    private fun opSym(
        op: CmpOp,
    ): String =
        when (op) {
            CmpOp.EQ -> "="
            CmpOp.NE -> "<>"
            CmpOp.GT -> ">"
            CmpOp.GE -> ">="
            CmpOp.LT -> "<"
            CmpOp.LE -> "<="
        }

    private fun strOf(
        lit: Literal,
    ): String =
        when (lit) {
            is Literal.Str -> lit.value
            is Literal.Num -> lit.raw
            is Literal.Bool -> lit.value.toString()
            Literal.Null -> ""
        }

    private fun toJdbc(
        cast: ValueCast,
        lit: Literal,
    ): Any? =
        if (lit is Literal.Null) {
            null
        } else {
            when (cast) {
                ValueCast.BOOL -> (lit as? Literal.Bool)?.value ?: litRaw(lit).toBooleanStrictOrNull()

                ValueCast.LONG -> litRaw(lit).toLongOrNull() ?: litRaw(lit)

                ValueCast.DOUBLE -> litRaw(lit).toDoubleOrNull() ?: litRaw(lit)

                ValueCast.DATE -> litRaw(lit)

                // ISO 串；驱动/列类型隐式转换，双库测复证
                ValueCast.TIMESTAMP -> litRaw(lit)

                ValueCast.TEXT -> litRaw(lit)
            }
        }

    private fun litRaw(
        lit: Literal,
    ): String =
        when (lit) {
            is Literal.Str -> lit.value
            is Literal.Num -> lit.raw
            is Literal.Bool -> lit.value.toString()
            Literal.Null -> ""
        }
}
