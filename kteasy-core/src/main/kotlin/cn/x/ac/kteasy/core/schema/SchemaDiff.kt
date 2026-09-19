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
package cn.x.ac.kteasy.core.schema

import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.ColumnDefault
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.ForeignKeySpec
import cn.x.ac.kteasy.core.schema.dialect.IndexSpec
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.TableSpec
import cn.x.ac.kteasy.core.schema.dialect.UniqueSpec

/*
 * 物化引擎的**纯 diff 内核**（步骤卡 M1-03 设计要点 1/4）。输入＝对象元数据（期望态）+ 已物化的物理态
 * （由执行器经 IntrospectionOps 探测得到），输出＝**有序** [SchemaStep] 序列。零 JDBC、零方言字面量
 * （红线④⑤：本文件在 core.schema，非 dialect 区，只产出方言无关的 [TableSpec]/[PhysicalColumn] 等载体）。
 *
 * 关键不变式（【规格】§5.2 硬验收）：
 * - 标量字段（storage_kind=EXT）的增/删/改 **永不产出任何步**——它落 `ext` JSON，零 DDL；
 * - 只有真列判据 `storage_kind=COLUMN`（REF/DICT/ANYREF）与 N2N 才产步；
 * - diff 只算「语义增量」，崩溃安全由执行器每步 precheck 再探一次兜底（双层幂等）。
 */

/** 作业步类型（封闭枚举，映射 `schema_change_job.step_kind`；M1-03 设计要点 2）。 */
enum class StepKind {
    CREATE_TABLE,
    DROP_TABLE,
    ADD_FK_COLUMN,
    DROP_COLUMN,
    CREATE_RTABLE,
    ADD_INDEX_EXPR,
    DROP_INDEX,
    ADD_VIRTUAL_COLUMN,
    BACKFILL_BATCH,
    SWITCH_READ,
    CLEAN_EXT_KEY,
}

/** 一执行步要喂给 [cn.x.ac.kteasy.core.schema.dialect.SchemaProvider] 的方言无关参数。 */
sealed class StepOp {
    /** 建对象表（系统列 + 真列 + FK + ext）；PG 侧 [spec].area 决定落 app schema。 */
    data class CreateTable(
        val spec: TableSpec,
    ) : StepOp()

    /** 建 N2N 关联表 r_（双方 FK + 附加列 + 唯一对）。 */
    data class CreateRelationTable(
        val spec: TableSpec,
    ) : StepOp()

    /** 增量加真列（REF：列 + FK；ANYREF：列 + 伴生 _obj；DICT：列 + 路径 btree）。候选回退由执行器处理。 */
    data class AddFkColumn(
        val hostArea: LogicalArea,
        val hostTable: String,
        val column: PhysicalColumn,
        val companion: PhysicalColumn? = null,
        val foreignKey: ForeignKeySpec? = null,
        val index: IndexSpec? = null,
    ) : StepOp()

    /** 删真列（字段停用且为 COLUMN）。破坏性引用检查归执行器。 */
    data class DropColumn(
        val hostArea: LogicalArea,
        val hostTable: String,
        val column: String,
    ) : StepOp()

    /** 删对象表（对象标记 deleted 且无引用）。 */
    data class DropTable(
        val area: LogicalArea,
        val name: String,
    ) : StepOp()

    /** 物理化第一步：加 VIRTUAL 生成列（S7 档二，Block E 落地发射）。 */
    data class AddVirtualColumn(
        val hostArea: LogicalArea,
        val hostTable: String,
        val column: PhysicalColumn,
        val expressionSourceColumn: String,
        val index: Boolean,
    ) : StepOp()

    /** 分批回填（每批 [batchSize]、幂等谓词「目标列 IS NULL」，checkpoint 记 last_id）。 */
    data class BackfillBatch(
        val hostArea: LogicalArea,
        val hostTable: String,
        val targetColumn: String,
        val expressionSourceColumn: String,
        val batchSize: Int,
    ) : StepOp()
}

/** 有序作业步：[seq] 决定同一对象内的执行顺序（跨对象由执行器串行队列 + 命名锁互斥）。 */
data class SchemaStep(
    val objectId: String,
    val kind: StepKind,
    val seq: Int,
    val op: StepOp,
)

/**
 * 已物化物理态快照（执行器经 IntrospectionOps 探测当前对象表得到，喂给纯 diff 做增量判定）。
 *
 * @property tableExists 对象物理表是否已存在
 * @property columns 对象表上已存在的物理列名集合
 * @property relationTables 该对象已存在的 N2N 关联表逻辑名集合（不含 `r_` 前缀）
 */
data class MaterializedState(
    val tableExists: Boolean = false,
    val columns: Set<String> = emptySet(),
    val relationTables: Set<String> = emptySet(),
)

/**
 * diff 输入。@property objectMeta 对象元数据；@property fields 该对象**全部**字段（含 disabled，用于判定删列）；
 * @property objectApiById objectId→apiName 映射（解析引用目标表名，由执行器从快照提供）；
 * @property parentApi CHILD 对象的父表 api（挂 parent_id 外键）；@property actual 当前物理态。
 */
data class DiffInput(
    val objectMeta: MdObject,
    val fields: List<MdField>,
    val objectApiById: Map<String, String>,
    val parentApi: String?,
    val actual: MaterializedState,
)

/** 物化内核（【规格】§5.2「重写最险处」的可证伪核心）。 */
object SchemaDiff {
    private const val ID_LEN = 32

    private const val DICT_PATH_LEN = 512

    fun diff(input: DiffInput): List<SchemaStep> {
        val obj = input.objectMeta
        val enabled = input.fields.filter { it.enabled }
        val steps = mutableListOf<SchemaStep>()

        if (!input.actual.tableExists) {
            // 新建对象：一张 CREATE_TABLE（含全部当前 COLUMN 列）+ 每个启用 N2N 一张 CREATE_RTABLE。
            steps += buildCreateTable(obj, enabled, input)
            enabled.filter { it.storageKind == StorageKind.N2N }.forEach { nf ->
                steps += buildCreateRelation(obj, nf, input)
            }
        } else {
            // 增量：只对「新增且物理态尚未存在」的真列/N2N 发射；标量（EXT）永不产步。
            enabled.filter { it.storageKind == StorageKind.COLUMN }.forEach { f ->
                val defs = realColumnsOf(obj, f, input)
                if (defs.first.name !in input.actual.columns) {
                    steps += step(obj.id, StepKind.ADD_FK_COLUMN, StepOp.AddFkColumn(LogicalArea.ENTITY, obj.apiName, defs.first, defs.second, defs.third, defs.fourth))
                }
            }
            enabled.filter { it.storageKind == StorageKind.N2N }.forEach { nf ->
                val relName = relationTableLogicalName(obj, nf)
                if (relName !in input.actual.relationTables) {
                    steps += step(obj.id, StepKind.CREATE_RTABLE, buildCreateRelation(obj, nf, input).op)
                }
            }
            // 停用且为真列 → 删列候选（EXT 停用不产步：数据留 ext）。companion _obj 一并计。
            input.fields.filter { !it.enabled && it.storageKind == StorageKind.COLUMN }.forEach { f ->
                realColumnsOf(obj, f, input).let { (main, comp, _, _) ->
                    if (main.name in input.actual.columns) {
                        steps += step(obj.id, StepKind.DROP_COLUMN, StepOp.DropColumn(LogicalArea.ENTITY, obj.apiName, main.name))
                    }
                    comp?.let {
                        if (it.name in input.actual.columns) {
                            steps += step(obj.id, StepKind.DROP_COLUMN, StepOp.DropColumn(LogicalArea.ENTITY, obj.apiName, it.name))
                        }
                    }
                }
            }
        }

        return steps.mapIndexed { i, s -> s.copy(seq = i) }
    }

    // ---------- CREATE_TABLE ----------

    private fun buildCreateTable(
        obj: MdObject,
        enabled: List<MdField>,
        input: DiffInput,
    ): SchemaStep {
        val cols = mutableListOf<PhysicalColumn>()
        val fks = mutableListOf<ForeignKeySpec>()
        val idxs = mutableListOf<IndexSpec>()

        cols += PhysicalColumn("id", ColumnType.VARCHAR, ID_LEN, nullable = false, primaryKey = true, binaryCollation = true)
        cols += PhysicalColumn("owner_user", ColumnType.VARCHAR, ID_LEN, binaryCollation = true)
        cols += PhysicalColumn("owner_dept", ColumnType.VARCHAR, ID_LEN, binaryCollation = true)
        cols += PhysicalColumn("created_at", ColumnType.TIMESTAMP, nullable = false, default = ColumnDefault.Now)
        cols += PhysicalColumn("created_by", ColumnType.VARCHAR, ID_LEN, binaryCollation = true)
        cols += PhysicalColumn("updated_at", ColumnType.TIMESTAMP, nullable = false, default = ColumnDefault.Now)
        cols += PhysicalColumn("updated_by", ColumnType.VARCHAR, ID_LEN, binaryCollation = true)
        cols += PhysicalColumn("deleted_at", ColumnType.TIMESTAMP)
        cols += PhysicalColumn("approval_state", ColumnType.VARCHAR, 16, nullable = false, default = ColumnDefault.Literal("DRAFT"))
        cols += PhysicalColumn("row_version", ColumnType.BIGINT, nullable = false, default = ColumnDefault.Zero)
        cols += PhysicalColumn("ext", ColumnType.JSON)

        if (obj.kind == ObjectKind.CHILD) {
            val parent = requireNotNull(input.parentApi) { "CHILD 对象 [${obj.apiName}] 缺父表 api，无法建 parent_id 外键" }
            cols += PhysicalColumn("parent_id", ColumnType.VARCHAR, ID_LEN, binaryCollation = true)
            fks += ForeignKeySpec("fk_${obj.apiName}_parent", LogicalArea.ENTITY, obj.apiName, "parent_id", LogicalArea.ENTITY, parent)
        }

        // 真列（REF/DICT/ANYREF）内联进 CREATE_TABLE。
        enabled.filter { it.storageKind == StorageKind.COLUMN }.forEach { f ->
            val (main, companion, fk, idx) = realColumnsOf(obj, f, input)
            cols += main
            companion?.let { cols += it }
            fk?.let { fks += it }
            idx?.let { idxs += it }
        }

        val spec = TableSpec(LogicalArea.ENTITY, obj.apiName, cols, fks, indexes = idxs)
        return step(obj.id, StepKind.CREATE_TABLE, StepOp.CreateTable(spec))
    }

    /**
     * 一个字段的真列定义：(主列, 伴生列?, 外键?, 索引?)。仅对 storage_kind=COLUMN 调用。
     * - REF：id 列 + FK 到目标表；
     * - ANYREF：id 列 + `_obj` 伴生列（存目标 api），禁 FK；
     * - DICT：路径列 + 左前缀 btree（图纸 01 §2）。
     */
    private fun realColumnsOf(
        obj: MdObject,
        f: MdField,
        input: DiffInput,
    ): Tuple4<PhysicalColumn, PhysicalColumn?, ForeignKeySpec?, IndexSpec?> {
        val api = f.apiName
        return when (f.logicalType) {
            LogicalType.REF -> {
                val target = f.refObjectId?.let { input.objectApiById[it] }
                val main = PhysicalColumn(api, ColumnType.VARCHAR, ID_LEN, binaryCollation = true)
                val fk = target?.let { ForeignKeySpec("fk_${obj.apiName}_$api", LogicalArea.ENTITY, obj.apiName, api, LogicalArea.ENTITY, it) }
                Tuple4(main, null, fk, null)
            }

            LogicalType.ANYREF -> {
                Tuple4(
                    PhysicalColumn(api, ColumnType.VARCHAR, ID_LEN, binaryCollation = true),
                    PhysicalColumn("${api}_obj", ColumnType.VARCHAR, 64, binaryCollation = true),
                    null,
                    null,
                )
            }

            LogicalType.DICT -> {
                Tuple4(
                    PhysicalColumn(api, ColumnType.VARCHAR, DICT_PATH_LEN),
                    null,
                    null,
                    IndexSpec("ix_${obj.apiName}_$api", listOf(api)),
                )
            }

            else -> {
                Tuple4(PhysicalColumn(api, ColumnType.VARCHAR, 255), null, null, null)
            }
        }
    }

    // ---------- CREATE_RTABLE ----------

    private fun buildCreateRelation(
        obj: MdObject,
        nf: MdField,
        input: DiffInput,
    ): SchemaStep {
        val relLogical = relationTableLogicalName(obj, nf)
        val sourceCol = "src_${obj.apiName}_id"
        val targetApi = nf.refObjectId?.let { input.objectApiById[it] }
        val targetCol = targetApi?.let { "dst_${it}_id" } ?: "dst_id"
        val cols =
            listOf(
                PhysicalColumn("id", ColumnType.VARCHAR, ID_LEN, nullable = false, primaryKey = true, binaryCollation = true),
                PhysicalColumn(sourceCol, ColumnType.VARCHAR, ID_LEN, nullable = false, binaryCollation = true),
                PhysicalColumn(targetCol, ColumnType.VARCHAR, ID_LEN, nullable = false, binaryCollation = true),
                PhysicalColumn("ext", ColumnType.JSON),
            )
        val fks =
            buildList {
                add(ForeignKeySpec("fk_${relLogical}_src", LogicalArea.RELATION, relLogical, sourceCol, LogicalArea.ENTITY, obj.apiName))
                targetApi?.let { add(ForeignKeySpec("fk_${relLogical}_dst", LogicalArea.RELATION, relLogical, targetCol, LogicalArea.ENTITY, it)) }
            }
        val spec =
            TableSpec(
                area = LogicalArea.RELATION,
                name = relLogical,
                columns = cols,
                foreignKeys = fks,
                uniques = listOf(UniqueSpec("uk_${relLogical}_pair", listOf(sourceCol, targetCol))),
            )
        return step(obj.id, StepKind.CREATE_RTABLE, StepOp.CreateRelationTable(spec))
    }

    /** N2N 关联表逻辑名（区再加 `r_` 前缀）：`<对象api>_<字段api>`。 */
    fun relationTableLogicalName(
        obj: MdObject,
        nf: MdField,
    ): String = "${obj.apiName}_${nf.apiName}"

    private fun step(
        objectId: String,
        kind: StepKind,
        op: StepOp,
    ): SchemaStep = SchemaStep(objectId, kind, 0, op)

    /** 四元组（内部用，避免为真列定义拉一个 public DTO）。 */
    private data class Tuple4<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
