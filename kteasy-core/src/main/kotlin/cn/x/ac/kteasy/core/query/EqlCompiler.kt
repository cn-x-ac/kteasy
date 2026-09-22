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
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.schema.dialect.ValueCast

/**
 * 块2 编译器：元数据无关语法树 [Query] + [MetadataLookup] → 已定位定型的逻辑计划 [QueryPlan]。
 *
 * 编译入口即 `compile()`——无共享可变状态（[FieldResolver] 每次新建，join/exists 归其局部），故线程安全。
 * 职责：解析对象存在性、经解析器定位点链、经 [TypeMatrix] 校验算子/字面量/聚合、注入软删、汇总 join 与 EXISTS。
 */
object EqlCompiler {
    fun compile(
        query: Query,
        lookup: MetadataLookup,
    ): QueryPlan {
        val root = lookup.objectByApi(query.fromObject) ?: throw EqlErrors.fieldUnknown("查询对象 [${query.fromObject}] 不存在")
        val resolver = FieldResolver(lookup)

        val select = compileSelect(query, root, resolver)
        val hasAgg = query.select.any { it is SelectItem.Aggregate } || query.groupBy.isNotEmpty()
        val where = compileWhere(query.where, root, resolver)
        val orderBy = query.orderBy.map { compileOrder(it, root, resolver) }
        val groupBy = query.groupBy.map { resolver.resolveFromRoot(root, it).terminal.location }

        return QueryPlan(
            root = TableRef(ROOT_ALIAS, root.apiName),
            select = select,
            where = where,
            orderBy = orderBy,
            groupBy = groupBy,
            limit = query.limit,
            offset = query.offset,
            joins = resolver.joinList(),
            hasAggregate = hasAgg,
        )
    }

    // ---------- select ----------

    private fun compileSelect(
        query: Query,
        root: MdObject,
        resolver: FieldResolver,
    ): List<SelectPlan> {
        if (query.select.isEmpty() || query.select.any { it is SelectItem.Star }) return listOf(SelectPlan.AllBusiness)
        return query.select.map { item ->
            when (item) {
                is SelectItem.Field -> SelectPlan.Value(resolver.resolveFromRoot(root, item.path).terminal.location, item.path.tail)
                is SelectItem.Aggregate -> compileAggSelect(item.agg, root, resolver)
                SelectItem.Star -> SelectPlan.AllBusiness
            }
        }
    }

    private fun compileAggSelect(
        agg: Agg,
        root: MdObject,
        resolver: FieldResolver,
    ): SelectPlan {
        val arg = agg.arg ?: return SelectPlan.Aggregate(agg, null)
        TypeMatrix.rejectCrossEndAggregate(arg, agg.fn)
        val term = resolver.resolveFromRoot(root, arg).terminal
        if (!TypeMatrix.allowsAggregate(term.logicalType, agg.fn)) {
            throw EqlErrors.typeMismatch("聚合 [${agg.fn.name}] 不适用于字段 [${arg.tail}] 类型 ${term.logicalType}")
        }
        return SelectPlan.Aggregate(agg, term.location)
    }

    // ---------- where ----------

    private fun compileWhere(
        user: Expr?,
        root: MdObject,
        resolver: FieldResolver,
    ): RExpr {
        val softDelete = RExpr.IsNull(ValueLocation.Column(ROOT_ALIAS, "deleted_at"), negated = false)
        return if (user == null) softDelete else RExpr.And(listOf(softDelete, compileExpr(user, root, resolver)))
    }

    private fun compileExpr(
        e: Expr,
        root: MdObject,
        resolver: FieldResolver,
    ): RExpr =
        when (e) {
            is Expr.And -> RExpr.And(e.parts.map { compileExpr(it, root, resolver) })
            is Expr.Or -> RExpr.Or(e.parts.map { compileExpr(it, root, resolver) })
            is Expr.Not -> RExpr.Not(compileExpr(e.inner, root, resolver))
            is Expr.Cmp -> wrap(leafCmp(e, root, resolver))
            is Expr.In -> wrap(leafIn(e, root, resolver))
            is Expr.Like -> wrap(leafLike(e, root, resolver))
            is Expr.Match -> wrap(leafMatch(e, root, resolver))
            is Expr.Within -> wrap(leafWithin(e, root, resolver))
            is Expr.Has -> leafHas(e, root, resolver)
        }

    private class Leaf(
        val exists: ExistsScope?,
        val expr: RExpr,
    )

    /** 点链穿过 N2N 时把叶谓词包进 EXISTS（纯局部，无共享态）。 */
    private fun wrap(
        leaf: Leaf,
    ): RExpr = if (leaf.exists == null) leaf.expr else RExpr.N2n(leaf.exists, null, leaf.expr)

    private fun leafCmp(
        e: Expr.Cmp,
        root: MdObject,
        resolver: FieldResolver,
    ): Leaf {
        val rp = resolver.resolveFromRoot(root, e.path)
        val t = rp.terminal
        val opKind = if (e.op == CmpOp.EQ || e.op == CmpOp.NE) TypeMatrix.Op.EQ else TypeMatrix.Op.ORD
        requireAllowed(t.logicalType, opKind, e.path)
        val inner =
            when (val rhs = e.rhs) {
                is CmpRhs.Lit -> {
                    if (rhs.literal is Literal.Null) {
                        RExpr.IsNull(t.location, negated = e.op == CmpOp.NE)
                    } else {
                        requireLiteral(t, rhs.literal)
                        RExpr.Cmp(t.location, e.op, Rhs.Val(TypedOperand(effectiveCast(t), rhs.literal)))
                    }
                }

                is CmpRhs.Path -> {
                    if (e.op != CmpOp.EQ && e.op != CmpOp.NE) throw EqlErrors.typeMismatch("字段对字段比较仅支持 = / !=")
                    RExpr.Cmp(t.location, e.op, Rhs.Loc(resolver.resolveFromRoot(root, rhs.path).terminal.location))
                }
            }
        return Leaf(rp.exists, inner)
    }

    private fun leafIn(
        e: Expr.In,
        root: MdObject,
        resolver: FieldResolver,
    ): Leaf {
        val rp = resolver.resolveFromRoot(root, e.path)
        val t = rp.terminal
        requireAllowed(t.logicalType, TypeMatrix.Op.IN, e.path)
        e.values.forEach { requireLiteral(t, it) }
        return Leaf(rp.exists, RExpr.In(t.location, effectiveCast(t), e.values))
    }

    private fun leafLike(
        e: Expr.Like,
        root: MdObject,
        resolver: FieldResolver,
    ): Leaf {
        val rp = resolver.resolveFromRoot(root, e.path)
        requireAllowed(rp.terminal.logicalType, TypeMatrix.Op.LIKE, e.path)
        return Leaf(rp.exists, RExpr.Like(rp.terminal.location, e.prefix))
    }

    private fun leafMatch(
        e: Expr.Match,
        root: MdObject,
        resolver: FieldResolver,
    ): Leaf {
        val rp = resolver.resolveFromRoot(root, e.path)
        requireAllowed(rp.terminal.logicalType, TypeMatrix.Op.MATCH, e.path)
        val pinyin = if (e.path.hops == 0) resolver.pinyinColumn(root, e.path.head) else null
        return Leaf(rp.exists, RExpr.Match(pinyin, rp.terminal.location, e.term))
    }

    private fun leafWithin(
        e: Expr.Within,
        root: MdObject,
        resolver: FieldResolver,
    ): Leaf {
        val rp = resolver.resolveFromRoot(root, e.path)
        requireAllowed(rp.terminal.logicalType, TypeMatrix.Op.WITHIN, e.path)
        return Leaf(rp.exists, RExpr.Within(rp.terminal.location, e.token, rp.terminal.logicalType))
    }

    private fun leafHas(
        e: Expr.Has,
        root: MdObject,
        resolver: FieldResolver,
    ): RExpr {
        if (e.path.hops == 0) {
            resolver.n2nScope(root, e.path.head)?.let { return RExpr.N2n(it, e.key, null) }
        }
        val t = resolver.resolveFromRoot(root, e.path).terminal
        requireAllowed(t.logicalType, TypeMatrix.Op.HAS, e.path)
        val ext = t.location as? ValueLocation.Ext ?: throw EqlErrors.typeMismatch("has() 仅适用 ext 字段或 N2N 关联")
        return if (e.key == null) RExpr.HasKey(ext) else RExpr.ArrayMember(ext, e.key)
    }

    // ---------- order ----------

    private fun compileOrder(
        key: OrderKey,
        root: MdObject,
        resolver: FieldResolver,
    ): OrderPlan {
        if (key.path == null) return OrderPlan(null, requireNotNull(key.agg) { "排序键须为字段或聚合" }, key.dir)
        val rp = resolver.resolveFromRoot(root, key.path)
        if (rp.exists != null) throw EqlErrors.typeMismatch("不支持按穿过 N2N 的点链排序（本卡边界，见证据 §2）")
        return OrderPlan(rp.terminal.location, null, key.dir)
    }

    // ---------- helpers ----------

    private fun requireAllowed(
        logical: LogicalType,
        op: TypeMatrix.Op,
        path: FieldPath,
    ) {
        if (!TypeMatrix.allows(logical, op)) {
            throw EqlErrors.typeMismatch("类型 $logical 不支持算子 $op（字段 [${path.segments.joinToString(".")}]）")
        }
    }

    private fun requireLiteral(
        t: ResolvedValue,
        lit: Literal,
    ) {
        TypeMatrix.validateLiteral(t.logicalType, t.fieldType, lit)?.let { throw EqlErrors.typeMismatch(it) }
    }

    private fun effectiveCast(
        t: ResolvedValue,
    ): ValueCast =
        when (val loc = t.location) {
            is ValueLocation.Ext -> loc.cast
            is ValueLocation.Column -> t.fieldType.cast ?: ValueCast.TEXT
        }
}
