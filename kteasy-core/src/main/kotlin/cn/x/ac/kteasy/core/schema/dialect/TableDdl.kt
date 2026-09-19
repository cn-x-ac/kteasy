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
package cn.x.ac.kteasy.core.schema.dialect

/*
 * 物化引擎（M1-03）用的**方言无关建表载体**：diff 引擎据此描述「一张对象表 / N2N 关联表应当长什么样」，
 * 各方言 [TableOps] 实现把它渲染成 `DdlStatement`。表/列/约束名一律传逻辑名，物理限定名由实现经
 * [NamespaceMapper] 现算（禁上层预拼 `md.`/`e_`，红线⑤）。
 *
 * 刻意不 import 任何具体 SQL 语法——类型/默认值只以中性枚举承载，`now()` 与 `CURRENT_TIMESTAMP` 的差异
 * 圈在 Provider 里（与 Types.kt 的 [ValueCast]/[JsonPath] 同一设计取向）。
 */

/** 中性列类型：把「建表要什么列」收敛到这一小组，屏蔽 `timestamptz`↔`DATETIME(6)`、`jsonb`↔`json` 等写法差异。 */
enum class ColumnType {
    /** 定长/变长字符串（[PhysicalColumn.length] 必填，用于 id/编码/路径等可索引列）。 */
    VARCHAR,

    /** 无界文本（PG `text` / MySQL `LONGTEXT`；不进索引）。 */
    TEXT,

    BIGINT,
    INTEGER,
    BOOLEAN,

    /** 带时区时刻（PG `timestamptz` / MySQL `DATETIME(6)`）。 */
    TIMESTAMP,

    /** JSON 文档列（PG `jsonb` / MySQL `json`）。 */
    JSON,
}

/** 中性列默认值：由方言渲染成合法 `DEFAULT` 子句（`NOW` 在 PG＝`now()`、MySQL＝`CURRENT_TIMESTAMP(6)`）。 */
sealed class ColumnDefault {
    data object Now : ColumnDefault()

    data object Zero : ColumnDefault()

    /** 字面量默认（调用方保证已符合目标类型；实现负责加引号转义）。 */
    data class Literal(
        val text: String,
    ) : ColumnDefault()
}

/** 一列物理列定义。 */
data class PhysicalColumn(
    val name: String,
    val type: ColumnType,
    val length: Int? = null,
    val nullable: Boolean = true,
    val primaryKey: Boolean = false,
    val default: ColumnDefault? = null,
    /**
     * 标识符列（id / 各类引用列）显式钉 binary 排序规则（PG `COLLATE "C"`、MySQL `COLLATE utf8mb4_bin`）。
     *
     * 禁用库/表默认：ci/ai 不该用于标识符，binary 比较退化为 memcmp，且 ci 字典序打乱 ULID
     * 「按 id 排＝按时间排」（M1-03 设计要点 7）。FK/JOIN 两端须同此规则，否则撞 `Illegal mix of collations`。
     */
    val binaryCollation: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "列名不能为空" }
        if (type == ColumnType.VARCHAR) {
            require(length != null && length > 0) { "VARCHAR 列 [$name] 必须给定 length" }
        }
        require(!(primaryKey && nullable)) { "主键列 [$name] 不得可空" }
        require(!(binaryCollation && type != ColumnType.VARCHAR)) { "仅字符串列可钉 binary 排序规则：[$name]" }
    }
}

/**
 * 外键约束（引用另一张动态表的列）。[hostTable]/[refTable] 传逻辑表名，实现按各自 [LogicalArea] 现算限定名。
 *
 * 同时服务两处：建表内联（[TableSpec.foreignKeys]，[hostArea]/[hostTable] 恒等于该 [TableSpec]），
 * 以及独立 `ADD_FK_COLUMN` 步（[TableOps.addForeignKey]，须自带宿主表定位）。
 */
data class ForeignKeySpec(
    val name: String,
    val hostArea: LogicalArea,
    val hostTable: String,
    val column: String,
    val refArea: LogicalArea,
    val refTable: String,
    val refColumn: String = "id",
)

/** 唯一约束（对象表用不到，N2N 关联表的「唯一对」用）。 */
data class UniqueSpec(
    val name: String,
    val columns: List<String>,
) {
    init {
        require(columns.isNotEmpty()) { "唯一约束 [$name] 至少一列" }
    }
}

/** 随建表一起创建的普通/唯一索引（如 DICT 路径列的 btree）。 */
data class IndexSpec(
    val name: String,
    val columns: List<String>,
    val unique: Boolean = false,
) {
    init {
        require(columns.isNotEmpty()) { "索引 [$name] 至少一列" }
    }
}

/**
 * 一张待建物理表的完整描述（对象表与 N2N 关联表共用此结构，差异仅在 [area] 与内容）。
 *
 * @property area 逻辑区（[LogicalArea.ENTITY]＝对象表；[LogicalArea.RELATION]＝N2N 关联表）
 * @property name 逻辑表名（对象＝api_name；关联＝n2n 标识；实现按区加 `e_`/`r_`/`app.r_`）
 * @property columns 全部物理列（系统列 + 真列 + JSON `ext`，顺序即建表列序）
 */
data class TableSpec(
    val area: LogicalArea,
    val name: String,
    val columns: List<PhysicalColumn>,
    val foreignKeys: List<ForeignKeySpec> = emptyList(),
    val uniques: List<UniqueSpec> = emptyList(),
    val indexes: List<IndexSpec> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "表名不能为空" }
        require(columns.isNotEmpty()) { "建表至少一列" }
        require(columns.any { it.primaryKey }) { "对象/关联表须有主键列" }
    }
}

/**
 * 单引号字面量渲染（`DEFAULT 'x'` / CHECK 值等）：内部单引号按 SQL 规范翻倍转义。
 *
 * 仅供建表列默认值这类**受控元数据字面量**使用（值来源于已校验的 api_name/枚举），非任意用户输入。
 */
internal fun sqlLiteral(text: String): String = "'" + text.replace("'", "''") + "'"
