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
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.ValueCast

/*
 * 块2 产物：**元数据无关方言、但类型已定型**的逻辑计划。块4 据此经 SchemaProvider 渲染双方言参数化 SQL。
 *
 * 表别名统一用 `t0/t1/…`（[QueryPlan.root] 恒 `t0`），**不用 api_name 作别名**——api_name 可撞 SQL 保留字
 * （order/group/…），列名保留字则由块4 经方言引号口处理；别名先规避掉一类方言引号需求。
 */

/** 一个取值定位：落在某别名表上的真列，或 `ext` JSON 里的一条路径（带按型解释的 [ValueCast]）。 */
sealed interface ValueLocation {
    /** 物理真列（系统列 / 真列化标量 / DICT 路径列 / REF 引用列 / 拼音伴生列）。 */
    data class Column(
        val alias: String,
        val column: String,
    ) : ValueLocation

    /** `ext` JSON 路径取值：块4 渲染为 `JsonOps.extractTyped("<alias>.ext", key, cast)`。 */
    data class Ext(
        val alias: String,
        val key: JsonPath,
        val cast: ValueCast,
    ) : ValueLocation
}

/** 计划内一张物理表的角色：别名 + 逻辑表名（块4 经 NamespaceMapper 现算限定名）。 */
data class TableRef(
    val alias: String,
    val logicalTable: String,
)

/** 一条 REF join：把 [alias] 目标表以 `onLeftAlias.onLeftColumn = alias.id` 挂进 FROM。 */
data class Join(
    val alias: String,
    val logicalTable: String,
    val onLeftAlias: String,
    val onLeftColumn: String,
)

/*
 * N2N 关联子查询定位统一用 [ExistsScope]（定义于 FieldResolver.kt，块2 解析器产出、此处与块4 渲染器共用）。
 */

/** 一个已定型操作数（字面量 + 其在目标列/抽取上的有效 [ValueCast]）；块4 按 cast 转 JDBC 绑定值，值永不进 SQL 串。 */
data class TypedOperand(
    val cast: ValueCast,
    val literal: Literal,
)

/** 比较右值：字面量，或另一处取值（字段对字段）。 */
sealed interface Rhs {
    data class Val(
        val operand: TypedOperand,
    ) : Rhs

    data class Loc(
        val location: ValueLocation,
    ) : Rhs
}

/** 逻辑排序键（块4 追加 `(expr IS NULL)` 前置键实现空值恒末）。 */
data class OrderPlan(
    val location: ValueLocation?,
    val agg: Agg?,
    val dir: SortDir,
)

/** 已定位、已定型、已校验的查询逻辑树（[Expr] 的解析对应物；[Within] 的区间展开推迟到渲染期）。 */
sealed interface RExpr {
    data class Cmp(
        val location: ValueLocation,
        val op: CmpOp,
        val rhs: Rhs,
    ) : RExpr

    data class In(
        val location: ValueLocation,
        val cast: ValueCast,
        val values: List<Literal>,
    ) : RExpr

    /** 文本前缀匹配：真列或 ext 文本上的 `LIKE prefix%`。 */
    data class Like(
        val location: ValueLocation,
        val prefix: String,
    ) : RExpr

    /** `~` 检索：优先命中 [pinyin] 伴生列前缀；无伴生列时退化为 [fallback] 文本上的 `LIKE`。 */
    data class Match(
        val pinyin: ValueLocation.Column?,
        val fallback: ValueLocation,
        val term: String,
    ) : RExpr

    /** JSON key 存在性（ext 路径 `#>` IS NOT NULL / JSON_CONTAINS_PATH）。 */
    data class HasKey(
        val ext: ValueLocation.Ext,
    ) : RExpr

    /** 数组型 `has(arr, value)`：块4 走 `JsonOps.arrayContains`（PG GIN 包含 / MySQL 多值索引 MEMBER OF）。 */
    data class ArrayMember(
        val ext: ValueLocation.Ext,
        val value: Literal,
    ) : RExpr

    /**
     * `has(rel)` / `has(rel, id)` / `rel.<field> op v`：N2N 关联存在（EXISTS）。
     *
     * [inner] 为 null＝仅判关联行存在；非 null＝穿过关联表到目标表上的谓词（其 location 别名＝[ExistsScope.targetAlias]）。
     */
    data class N2n(
        val n2n: ExistsScope,
        val idFilter: Literal?,
        val inner: RExpr?,
    ) : RExpr

    /** `x = null` / `x != null`（块4 渲染 IS NULL / IS NOT NULL）；软删谓词亦用之。 */
    data class IsNull(
        val location: ValueLocation,
        val negated: Boolean,
    ) : RExpr

    /** `path within token`：块4 渲染期调 [DateIntervals] 现算 [from,to) 参数（与块5 oracle 共用）。 */
    data class Within(
        val location: ValueLocation,
        val token: DateToken,
        val logicalType: LogicalType,
    ) : RExpr

    data class And(
        val parts: List<RExpr>,
    ) : RExpr

    data class Or(
        val parts: List<RExpr>,
    ) : RExpr

    data class Not(
        val inner: RExpr,
    ) : RExpr
}

/** 计划里的一个 select 列：直取、聚合，或 `*`（块4 据元数据展开为全业务列，故此处仅记标志）。 */
sealed interface SelectPlan {
    data object AllBusiness : SelectPlan

    data class Value(
        val location: ValueLocation,
        val label: String,
    ) : SelectPlan

    data class Aggregate(
        val agg: Agg,
        val location: ValueLocation?,
    ) : SelectPlan
}

/**
 * 编译产物：一棵逻辑查询计划。块4 唯一消费此类型渲染 SQL；[QueryPlan.root] 恒别名 `t0`。
 *
 * @property joins REF 跳数产生的 join（按别名去重、有序）
 * @property where 已注入软删谓词（`t0.deleted_at IS NULL`）的最终谓词树（除非 [noFilter] 内部治理位）
 */
data class QueryPlan(
    val root: TableRef,
    val select: List<SelectPlan>,
    val where: RExpr?,
    val orderBy: List<OrderPlan>,
    val groupBy: List<ValueLocation>,
    val limit: Int?,
    val offset: Int?,
    val joins: List<Join>,
    val hasAggregate: Boolean,
) {
    /** 该别名是否已在 FROM 里（root 或已登记 join），供编译器复用 join。 */
    fun hasAlias(
        alias: String,
    ): Boolean = root.alias == alias || joins.any { it.alias == alias }
}
