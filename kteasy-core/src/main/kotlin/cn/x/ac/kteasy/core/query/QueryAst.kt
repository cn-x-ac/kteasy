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
 * EQL（实体查询语言）文法（EBNF v1）——**语法事实镜像**，唯一权威源为【模块图纸 03】§1；
 * 两处冲突＝图纸为准、本文件回写（触发停止点上报，勿静默改）。扩语法走停止点。
 *
 *   query      := [ 'select' selectlist ] 'from' object-id [ where ] [ orderby ] [ 'limit' n ] [ 'offset' n ]
 *   selectlist := item (',' item)*          ; item := field-path | agg '(' field-path ')' | '*'
 *   agg        := count | count_distinct | sum | avg | min | max   （仅 NUMBER/DECIMAL/DATE 族按型可用，语义校验归块2）
 *   where      := orExpr ; orExpr := andExpr ('OR' andExpr)* ; andExpr := pred (('AND')? pred)*
 *   pred       := ['NOT'] ( '(' orExpr ')' | cmp | hasPred | inPred | likePred | matchPred | datePred )
 *   cmp        := field-path op ( literal | field-path )           ; op := = != > >= < <=
 *   hasPred    := 'has(' field-path [',' literal] ')'              ; key 存在 / N2N & 数组含值
 *   inPred     := field-path 'in' '(' literal (',' literal){≤200} ')'
 *   likePred   := field-path 'like'  "'" 'ab%'"                    ; 仅前缀 %
 *   matchPred  := field-path '~'     "'" '拼音或关键词' "'"          ; 检索码列前缀
 *   datePred   := field-path 'within' dateToken                    ; 编译期展开为 [from,to) 参数（块3）
 *   field-path := api_name ('.' api_name){≤3跳}                     ; 引用后自动 join、N2N 后＝EXISTS
 *   orderby    := (field-path ['asc'|'desc'] | aggSort) (',' …){≤5}
 *
 * 文法级不变式（本文件只保证「语法形状」，语义/类型/元数据相关校验全归块2）：
 * - 标识符只认 api_name；一切**值**都是字面量节点，块4 渲染时恒转绑定参数，任何字面量不得进 SQL 串（红线④）。
 * - 关键字大小写不敏感；api_name 本身按 `^[a-z][a-z0-9_]{2,47}$` 已在元数据层钉死（块2 复校）。
 * - `andExpr := pred (('AND')? pred)*` 的「省略 AND」＝隐式 AND（相邻谓词即与关系），照【全景】口径。
 * - 结构化护栏（跳数/IN 元素数/limit 区间/orderby 条数）在**解析期**即拦语法级越界，语义级越界（未知字段/类型不符）在块2。
 */

// ============================ 字面量 ============================

/**
 * EQL 字面量的**语法级**形态：解析期只保留原始记法，不做类型解释——「这值到底是不是该字段的合法类型」
 * 是元数据感知问题，归块2 的 cast 矩阵。数字刻意以原始串承载（避免解析期过早定长/精度），由块2 按
 * [cn.x.ac.kteasy.core.schema.dialect.ValueCast] 精确落地。
 */
sealed interface Literal {
    /** 单/双引号包裹的字符串（引号已在词法层剥离、内部转义已还原）。 */
    data class Str(
        val value: String,
    ) : Literal

    /** 数字字面量原始串（含可选负号与小数点）；合法性交块2 按字段型校验。 */
    data class Num(
        val raw: String,
    ) : Literal

    /** 布尔字面量 `true`/`false`。 */
    data class Bool(
        val value: Boolean,
    ) : Literal

    /** 空字面量 `null`（`cmp` 右值可取，语义＝IS NULL，块2 落）。 */
    data object Null : Literal
}

// ============================ 点链 field-path ============================

/**
 * 点链取值入口（图纸 03 §1.1：引擎唯一的「取值/关联回溯」入口，display_name 模板/EQL/单据模板/公式共用同一解析器）。
 *
 * 本类型只承载**已切分的段序列**（`api_name` 序列），不解析段落在哪张表——跨引用回溯、逐段类型解析全在块2。
 * 段数上限（≤3 跳）在解析期即拦（超出抛 [EqlErrors.tooDeep] 携带 `ApiError.INVALID_PARAM`），
 * 语义级「目标对象是否真有该字段」归块2。
 */
data class FieldPath(
    val segments: List<String>,
) {
    init {
        require(segments.isNotEmpty()) { "点链至少一段" }
    }

    /** 首段（本对象 api_name，或引用型系统列 owner_user/owner_dept/created_by/updated_by——块2 特判）。 */
    val head: String get() = segments.first()

    /** 末端段（真正取值的字段/列）。 */
    val tail: String get() = segments.last()

    /** 跳数＝段数 - 1（单段无跳转）。 */
    val hops: Int get() = segments.size - 1
}

// ============================ 谓词 ============================

/** 比较运算符（`has/in/like/match/within` 各自独立成节点，不复用本枚举）。 */
enum class CmpOp {
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
}

/** 比较右值：字面量或另一条点链（字段对字段比较，块2 解析两侧类型）。 */
sealed interface CmpRhs {
    data class Lit(
        val literal: Literal,
    ) : CmpRhs

    data class Path(
        val path: FieldPath,
    ) : CmpRhs
}

/** WHERE/ON 位置的布尔表达式树（叶子谓词 + AND/OR/NOT；括号只影响结合结构，不单设节点）。 */
sealed interface Expr {
    /** `path op rhs`。 */
    data class Cmp(
        val path: FieldPath,
        val op: CmpOp,
        val rhs: CmpRhs,
    ) : Expr

    /** `path in (lit, …)`；[values] 长度已保证 ≤ [MAX_IN_TERMS]。 */
    data class In(
        val path: FieldPath,
        val values: List<Literal>,
    ) : Expr

    /** `path like 'prefix%'`；仅前缀通配，[prefix] 为剥去尾部 `%` 后的前缀串。 */
    data class Like(
        val path: FieldPath,
        val prefix: String,
    ) : Expr

    /** `path ~ '检索词'`：拼音检索码/关键词前缀匹配（块2 优先命中 `<api>_pinyin` 伴生列）。 */
    data class Match(
        val path: FieldPath,
        val term: String,
    ) : Expr

    /** `has(path [, literal])`：无第二参＝JSON key 存在/N2N 关联存在；有＝数组含值。 */
    data class Has(
        val path: FieldPath,
        val key: Literal?,
    ) : Expr

    /** `path within dateToken`：编译期展开为区间（块3）。 */
    data class Within(
        val path: FieldPath,
        val token: DateToken,
    ) : Expr

    /** n 元与（含隐式 AND）。 */
    data class And(
        val parts: List<Expr>,
    ) : Expr

    /** n 元或。 */
    data class Or(
        val parts: List<Expr>,
    ) : Expr

    data class Not(
        val inner: Expr,
    ) : Expr
}

// ============================ select / 聚合 / group by ============================

/** 聚合函数（`count_distinct` 语法记法含下划线，枚举名与之对应）。 */
enum class AggFn {
    COUNT,
    COUNT_DISTINCT,
    SUM,
    AVG,
    MIN,
    MAX,
}

/** 聚合调用：`count(*)` → [arg] 为 null；其余聚合必须带 field-path（块2 校验「禁跨端聚合」）。 */
data class Agg(
    val fn: AggFn,
    val arg: FieldPath?,
)

/** select 列项：`*`＝全部业务列（块2 据元数据展开），点链＝直取，聚合＝算子。 */
sealed interface SelectItem {
    data object Star : SelectItem

    data class Field(
        val path: FieldPath,
    ) : SelectItem

    data class Aggregate(
        val agg: Agg,
    ) : SelectItem
}

/** 排序方向。 */
enum class SortDir {
    ASC,
    DESC,
}

/**
 * 一条排序键：`field-path [asc|desc]` 或聚合别名排序（`aggSort`＝对 select 里某聚合结果排序）。
 * [path] 与 [agg] 二选一。方向缺省＝[SortDir.ASC]；空值恒末由块4 以两库同构表达式兜（⟨可逆⟩代拍，见证据 §2）。
 */
data class OrderKey(
    val path: FieldPath?,
    val agg: Agg?,
    val dir: SortDir,
)

// ============================ 顶层查询 ============================

/**
 * 解析产物：一棵与元数据无关的 EQL 语法树。块2 消费它 → [cn.x.ac.kteasy.core.query] 之外的逻辑计划。
 *
 * @property fromObject 查询对象 api_name（逻辑表名，物理限定名由块4 经 NamespaceMapper 现算）
 * @property select 显式 select 列项；空＝未写 select（块2 展开为全业务列）
 * @property where 谓词树；null＝无 where（块2 仍注入软删谓词）
 * @property orderBy 排序键；条数已保证 ≤ [MAX_ORDER_TERMS]
 * @property limit 用户显式 LIMIT；null＝未写（块4 强制默认，见性能护栏）
 * @property offset 偏移；null＝0
 * @property groupBy `group by` 字段路径（图纸 03 selectlist 未单列，聚合伴随分组在此承载；块2 校验聚合/分组一致性）
 */
data class Query(
    val fromObject: String,
    val select: List<SelectItem>,
    val where: Expr?,
    val orderBy: List<OrderKey>,
    val limit: Int?,
    val offset: Int?,
    val groupBy: List<FieldPath>,
)

// ============================ 护栏常量（可逆调参，改值须同步测试与文档） ============================

/** 点链最大跳数（超出 → 块2/解析期 `EQL_TOO_DEEP`）。图纸 03 §1。 */
const val MAX_FIELD_HOPS: Int = 3

/** `in (...)` 元素上限。图纸 03 §1。 */
const val MAX_IN_TERMS: Int = 200

/** `order by` 键上限。图纸 03 §1。 */
const val MAX_ORDER_TERMS: Int = 5

/** 未写 limit 时的默认行数（块4 强制注入）。卡面 §性能护栏。 */
const val DEFAULT_LIMIT: Int = 100

/** limit 硬上限（超出 → `EQL_LIMIT`，块4 判定）。卡面 §性能护栏。 */
const val MAX_LIMIT: Int = 5000

/** 递归下降解析的最大表达式嵌套深度（函数嵌套炸弹护栏，超出 → 语法拒绝）。⟨可逆⟩卡面「函数嵌套炸弹」。 */
const val MAX_NESTING_DEPTH: Int = 64
