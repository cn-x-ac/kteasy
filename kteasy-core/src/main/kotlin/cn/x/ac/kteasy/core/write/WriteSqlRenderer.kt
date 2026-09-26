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

import cn.x.ac.kteasy.core.meta.DepAggOp
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast

/**
 * 写入通道的 SQL 渲染器（图纸 04 阶段 9 的**语句形状**，纯函数、零方言字面量）。
 *
 * 红线④ 的口径在 M1-06 后是「SQL 只出自 query／write(data)／schema 三处且必须参数化」——本类就是 write 那一处。
 * 方言差异（表名限定、JSON 绑定、行锁子句）全部问 [SchemaProvider] 要，这里连一个库名都不写。
 *
 * **为什么不用 `provider.upsert` 一条语句包办**（卡面 §3 原写 upsert）：乐观并发要求
 * `WHERE id = :id AND row_version = :expected`，PG 的冲突更新分支可以带这个条件，
 * MySQL 的冲突更新分支**没有**「按旧行版本条件跳过」的表达能力（只能靠 IF/VALUES 技巧，两库从此分叉）。
 * 定位阶段早已知道是新建还是更新，因此这里分两条最普通、两库完全同形的语句：
 * 新建＝INSERT，更新＝UPDATE + 版本条件，影响行数 0 即冲突。⟨可逆⟩留档于证据 §2.3。
 */
class WriteSqlRenderer(
    private val provider: SchemaProvider,
) {
    /** 一条待执行语句 + 它的具名参数（执行层只负责绑定，不再拼串）。 */
    data class Bound(
        val sql: String,
        val params: Map<String, Any?>,
    )

    private fun table(
        objectApi: String,
    ): String = provider.namespace.qualified(LogicalArea.ENTITY, safeId(objectApi))

    /**
     * 渲染新建语句。[columns] 的键＝物理列名（已由管道算好，含系统列与 `ext`）。
     *
     * `ext` 走 `provider.json.bindJson`：PG 需要显式 JSON 类型转换、MySQL 直取——差异被封在方言区，
     * 出现在这里的字面量会被 M1-02 的字面量隔离门禁打红（§E31）。
     */
    fun insert(
        objectApi: String,
        columns: Map<String, Any?>,
    ): Bound {
        require(columns.isNotEmpty()) { "INSERT 至少要有主键列" }
        val cols = columns.keys.map { safeId(it) }
        val values = cols.map { if (it == "ext") provider.json.bindJson("ext") else ":v_$it" }
        val sql = "INSERT INTO " + table(objectApi) + " (" + cols.joinToString(", ") + ") VALUES (" + values.joinToString(", ") + ")"
        return Bound(sql, paramsOf(columns))
    }

    /**
     * 渲染带乐观并发条件的更新语句。
     *
     * @param assignments 待写列（**不含** id）；空集＝只推进版本（例如仅恢复/仅软删的场景仍会带 deleted_at，故实际不为空）
     * @param idValue 主键
     * @param expectedVersion 读到的旧版本；影响行数 0 ＝ 已被他人改写
     */
    fun update(
        objectApi: String,
        assignments: Map<String, Any?>,
        idValue: String,
        expectedVersion: Long,
    ): Bound {
        require(assignments.isNotEmpty()) { "UPDATE 的赋值集不得为空" }
        require(!assignments.containsKey("id")) { "主键列不进 SET" }
        val cols = assignments.keys.map { safeId(it) }
        val sets = cols.joinToString(", ") { if (it == "ext") "ext = " + provider.json.bindJson("ext") else "$it = :v_$it" }
        val sql =
            "UPDATE " + table(objectApi) + " SET " + sets +
                " WHERE id = :__id AND row_version = :__ver"
        val params = LinkedHashMap(paramsOf(assignments))
        params["__id"] = idValue
        params["__ver"] = expectedVersion
        return Bound(sql, params)
    }

    /**
     * 行锁读取（阶段 8：`forUpdate` 子句由方言给，两库语义同）。
     *
     * 取整行而非指定列：业务真列集合随元数据演进，写死列表会让物化字段被静默忽略。
     */
    fun selectForUpdate(
        objectApi: String,
        idValue: String,
    ): Bound =
        Bound(
            "SELECT * FROM " + table(objectApi) + " WHERE id = :__id " + provider.lockingRead.forUpdate(false),
            mapOf("__id" to idValue),
        )

    /** 无锁读（恢复路径要先看墓碑；同一事务内仍走同一连接，不额外加锁）。 */
    fun select(
        objectApi: String,
        idValue: String,
    ): Bound =
        Bound(
            "SELECT * FROM " + table(objectApi) + " WHERE id = :__id",
            mapOf("__id" to idValue),
        )

    /**
     * 列某父记录下现存活子行 id（M1-07 块 2 子项三集的数据源）。软删的不计（`deleted_at IS NULL`）。
     * 占位符 `:__pid` 由调用方绑定；编排层已在父锁保护下、同事务同连接读取，无需 forUpdate。
     */
    fun childIdsSql(
        objectApi: String,
    ): String = "SELECT id FROM " + table(objectApi) + " WHERE parent_id = :__pid AND deleted_at IS NULL ORDER BY id"

    // ---------- N2N 关联（r_ 表）集合差量（M1-07 块 3A） ----------
    // 表/列名由调用方（执行层）从 SchemaDiff 命名口 + 快照解析后传入，与物化、查询侧完全同源；
    // 这里只拼最普通的 SELECT/INSERT/DELETE，无方言分叉。附加列 ext 不在写路径显式给（新建行 ext 为空，
    // 保留行整体不碰 → 其 ext 天然存活，正是卡面红线）。

    /** 读某主机记录在关联表上现存的目标 id 集。占位符 `:__src`。 */
    fun relationTargetsSql(
        relTable: String,
        srcCol: String,
        dstCol: String,
    ): String = "SELECT $dstCol FROM $relTable WHERE $srcCol = :__src"

    /** 新增一条关联行（id/源/宿；ext 留空）。 */
    fun relationInsertSql(
        relTable: String,
        srcCol: String,
        dstCol: String,
    ): String = "INSERT INTO $relTable (id, $srcCol, $dstCol) VALUES (:__id, :__src, :__dst)"

    /** 按 (源, 宿∈集合) 物理删除关联行（连接表无墓碑列，删即删）。 */
    fun relationDeleteSql(
        relTable: String,
        srcCol: String,
        dstCol: String,
    ): String = "DELETE FROM $relTable WHERE $srcCol = :__src AND $dstCol IN (:__dsts)"

    /**
     * 汇总重算（recalc）聚合查询（M1-07 块4）：对 `srcObjectApi` 实体表按父键 `parentCol` 分组聚合来源字段。
     * 软删行不计（`deleted_at IS NULL`）。来源字段按存储归属取表达式——真列直取、ext 走 [SchemaProvider] 的
     * JSON 抽取 + 数值 cast（跨方言差异封在 provider 里，本类不写库名）。
     *
     * FIRST/LAST 非标准 SQL 聚合（需 `ORDER BY … LIMIT 1`，两库分叉），本块留桩拒，避免跨库猜——归后续方言件。
     */
    fun aggregateSql(
        srcObjectApi: String,
        sourceField: MdField,
        agg: DepAggOp,
        parentCol: String,
    ): String {
        val expr =
            when (sourceField.storageKind) {
                StorageKind.COLUMN -> safeId(sourceField.apiName)
                else -> provider.json.extractTyped("ext", JsonPath.of(sourceField.apiName), numericCast(sourceField.logicalType)).sql
            }
        val fn =
            when (agg) {
                DepAggOp.SUM -> "SUM($expr)"
                DepAggOp.COUNT -> "COUNT($expr)"
                DepAggOp.COUNT_DISTINCT -> "COUNT(DISTINCT $expr)"
                DepAggOp.AVG -> "AVG($expr)"
                DepAggOp.MIN -> "MIN($expr)"
                DepAggOp.MAX -> "MAX($expr)"
                DepAggOp.FIRST, DepAggOp.LAST -> throw UnsupportedOperationException("FIRST/LAST 聚合需方言 ORDER BY+LIMIT 跨库实现，本块（M1-07 块4）留桩未做")
            }
        return "SELECT $fn FROM " + table(srcObjectApi) + " WHERE $parentCol = :__pid AND deleted_at IS NULL"
    }

    private fun numericCast(
        type: LogicalType,
    ): ValueCast =
        when (type) {
            LogicalType.NUMBER -> ValueCast.LONG
            LogicalType.DECIMAL -> ValueCast.DOUBLE
            else -> ValueCast.LONG
        }

    /**
     * 参数名与 SQL 里的占位符一一对应：普通列为 `:v_<列名>`，`ext` 例外——它要经
     * `provider.json.bindJson("ext")` 生成方言绑定片段，占位符名由方言侧决定，故这里也必须叫 `ext`。
     */
    private fun paramsOf(
        columns: Map<String, Any?>,
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(columns.size)
        columns.forEach { (k, v) ->
            val id = safeId(k)
            out[if (id == "ext") id else "v_$id"] = v
        }
        return out
    }

    /**
     * 标识符白名单（纵深防御）：列名与表名进不了参数占位，只能靠字符集约束。
     *
     * 元数据侧的 api_name 早已被 `MetadataValidator.checkApiName` 限定为小写下划线集，
     * 系统列同样是常量——所以这里正常永不误伤，它拦的是「有人绕过治理面塞了脏名字」那条路。
     */
    private fun safeId(
        raw: String,
    ): String {
        require(ID.matches(raw)) { "非法 SQL 标识符：[$raw]" }
        return raw
    }

    private companion object {
        val ID = Regex("^[a-z][a-z0-9_]{1,63}$")
    }
}
