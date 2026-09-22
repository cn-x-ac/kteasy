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
import cn.x.ac.kteasy.core.meta.MetadataValidator
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.meta.TypeRegistry
import cn.x.ac.kteasy.core.schema.dialect.ColumnDefault
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.ForeignKeySpec
import cn.x.ac.kteasy.core.schema.dialect.IndexSpec
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.TableSpec
import cn.x.ac.kteasy.core.schema.dialect.UniqueSpec
import cn.x.ac.kteasy.core.schema.dialect.ValueCast

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

    /**
     * 物理化第一步：加一列**可空真列**（非 STORED 生成列，避免全表重写；执行器走 `addNullableColumn`）。
     *
     * [expressionSourceColumn] 记录 ext 来源键（供回填取数与清 key 定位），[index] 标记是否顺带建表达式索引。
     * 类名 `AddVirtualColumn` 为历史命名保留（PG18 VIRTUAL 生成列是提列者可选的替代策略，非本步默认）。
     */
    data class AddVirtualColumn(
        val hostArea: LogicalArea,
        val hostTable: String,
        val column: PhysicalColumn,
        val expressionSourceColumn: String,
        val index: Boolean,
        /** 该列的权威类型解释（由 `TypeRegistry.cast` 于计划期供给；执行器据此建可空列，不再从 ColumnType 猜）。 */
        val cast: ValueCast,
    ) : StepOp()

    /** 分批回填（每批 [batchSize]、幂等谓词「目标列 IS NULL」，checkpoint 记 last_id）。 */
    data class BackfillBatch(
        val hostArea: LogicalArea,
        val hostTable: String,
        val targetColumn: String,
        val expressionSourceColumn: String,
        val cast: ValueCast,
        val batchSize: Int,
    ) : StepOp()

    /** 表达式索引（S7 档一热字段加速）：对 [extColumn] 的 [keyPath] 按 [cast] 取值建索引，可在线。 */
    data class AddIndexExpr(
        val hostArea: LogicalArea,
        val hostTable: String,
        val indexName: String,
        val extColumn: String,
        val keyPath: List<String>,
        val cast: ValueCast,
        val online: Boolean,
    ) : StepOp()

    /** 删索引（档一回退 / 表达式索引逆步）；幂等由执行器 `indexExists` 探测。 */
    data class DropIndex(
        val hostArea: LogicalArea,
        val hostTable: String,
        val indexName: String,
        val online: Boolean,
    ) : StepOp()

    /**
     * 读切换屏障（物理化③→④之间）：无 DDL，仅在校验「目标列已无 NULL 残留」后把 md_field 读判据翻 COLUMN。
     *
     * [fieldId] 供执行器定位并幂等更新 `storage_kind`；[hostTable]/[column] 供回填完成度校验。因 `storage_kind`
     * 已是唯一读判据，此步不落新列、不改读源位，仅保证「回填全量完成」这一前置后才认列。
     */
    data class SwitchRead(
        val fieldId: String,
        val hostTable: String,
        val column: String,
    ) : StepOp()

    /**
     * 分批清理 ext key（物理化末步）：仅对「目标列已回填非空」的行 `ext = removeKey(ext, keyPath)`，
     * 数据不丢护栏＝绝不清未迁移行；幂等（键已删再删无副作用），可续跑。
     */
    data class CleanExtKey(
        val hostArea: LogicalArea,
        val hostTable: String,
        val targetColumn: String,
        val extColumn: String,
        val keyPath: List<String>,
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

    /** 拼音检索码伴生列后缀与长度（M1-04 块 3：快查 ∩ 可拼音字段各配一条 varchar 真列 + btree）。 */
    private const val PINYIN_SUFFIX = "_pinyin"

    private const val PINYIN_LEN = 255

    /** DECIMAL 标量物理化落 native 定点列的精度/标度（字段配置的小数位仅展示用，存储统一此标度，见 DECISIONS D4）。 */
    private const val DECIMAL_PRECISION = 30

    private const val DECIMAL_SCALE = 8

    /** 物理化回填 / 清 ext key 的每批行数（卡面 2000，⟨可逆⟩）。 */
    private const val BACKFILL_BATCH_SIZE = 2000

    fun diff(input: DiffInput): List<SchemaStep> {
        val obj = input.objectMeta
        // 对象删除：仅当 md 标记 disabled 且物理表存在 → 单条 DROP_TABLE（破坏性引用守卫在执行器 precheck 把关）。
        if (obj.disabled) {
            return if (input.actual.tableExists) {
                listOf(step(obj.id, StepKind.DROP_TABLE, StepOp.DropTable(LogicalArea.ENTITY, obj.apiName)))
            } else {
                emptyList()
            }
        }
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

        // 拼音检索码伴生真列（M1-04 块 3）：快查字段 ∩ 可拼音型，各配 `<api>_pinyin` varchar + btree。
        // 源字段即便存于 ext（无独立真列），此列仍单独物化——供 EQL 前缀命中（M1-05）与检索码回填（M1-06）。
        pinyinCompanions(obj, enabled).forEach { (col, idx) ->
            cols += col
            idxs += idx
        }

        val spec = TableSpec(LogicalArea.ENTITY, obj.apiName, cols, fks, indexes = idxs)
        return step(obj.id, StepKind.CREATE_TABLE, StepOp.CreateTable(spec))
    }

    /**
     * 拼音检索码伴生列声明（M1-04 块 3）：对象快查字段集（`quickSearchJson`）与「可拼音型」
     * （[cn.x.ac.kteasy.core.meta.FieldType.pinyinGeneratable]）的交集，取每个**启用**字段配一条
     * `<api>_pinyin` varchar 真列 + btree 索引。数字/布尔/日期/引用等非可拼音型即使被标快查也不产列。
     *
     * 本函数只做**方言无关的声明**：物理落列复用 M1-03 `TableOps.addColumn`/`IndexOps.createIndex`（增量路径），
     * 检索码值写入/回填归 M1-06 写通道，EQL `~` 端到端命中归 M1-05——M1-04 只保证「列存在且为可前缀检索的真列」。
     */
    private fun pinyinCompanions(
        obj: MdObject,
        enabled: List<MdField>,
    ): List<Pair<PhysicalColumn, IndexSpec>> {
        val quick = MetadataValidator.parseStringArray(obj.quickSearchJson)?.toSet() ?: return emptyList()
        return enabled
            .filter { it.apiName in quick && TypeRegistry.of(it.logicalType).pinyinGeneratable }
            .map { f ->
                val col = "${f.apiName}$PINYIN_SUFFIX"
                PhysicalColumn(col, ColumnType.VARCHAR, PINYIN_LEN) to IndexSpec("ix_${obj.apiName}_$col", listOf(col))
            }
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

    /** 某标量字段是否属「可物理化标量」：storage_kind=COLUMN 且非关系型（REF/DICT/ANYREF）。 */
    fun isPhysicalizableScalar(field: MdField): Boolean =
        field.storageKind == StorageKind.COLUMN &&
            field.logicalType != LogicalType.REF &&
            field.logicalType != LogicalType.DICT &&
            field.logicalType != LogicalType.ANYREF

    /**
     * 为「把已存 ext 的标量字段提为独立可写真列」产出**有序、幂等、可续跑、不丢数**的物理化步序列（S7 档二）。
     *
     * 由显式提列动作（M1-04）提交，**非**通用 diff 推导（守住「EXT 永不产步」不变式）。五步全幂等，故 kill -9
     * 重跑收敛到同一终态：加可空列（precheck 探列）→ 分批回填（`WHERE 目标列 IS NULL`）→ 可选表达式索引（precheck 探索引）
     * → 读切换（校验回填全量非空后翻 `storage_kind`）→ 清 ext key（仅清已回填行、绝不动未迁移数据）。
     * [makeIndex] 决定是否夹带档一表达式索引；[cast] 是从 ext 抽取后落列的类型（由字段类型注册表解析，M1-04）。
     */
    fun planPhysicalize(
        objectId: String,
        hostTable: String,
        fieldId: String,
        fieldApi: String,
        column: PhysicalColumn,
        cast: ValueCast,
        makeIndex: Boolean,
    ): List<SchemaStep> {
        val steps = mutableListOf<SchemaStep>()
        steps += step(objectId, StepKind.ADD_VIRTUAL_COLUMN, StepOp.AddVirtualColumn(LogicalArea.ENTITY, hostTable, column, fieldApi, makeIndex, cast))
        steps += step(objectId, StepKind.BACKFILL_BATCH, StepOp.BackfillBatch(LogicalArea.ENTITY, hostTable, fieldApi, fieldApi, cast, BACKFILL_BATCH_SIZE))
        if (makeIndex) {
            steps += step(objectId, StepKind.ADD_INDEX_EXPR, StepOp.AddIndexExpr(LogicalArea.ENTITY, hostTable, "ix_${hostTable}_$fieldApi", "ext", listOf(fieldApi), cast, online = true))
        }
        steps += step(objectId, StepKind.SWITCH_READ, StepOp.SwitchRead(fieldId, hostTable, fieldApi))
        steps += step(objectId, StepKind.CLEAN_EXT_KEY, StepOp.CleanExtKey(LogicalArea.ENTITY, hostTable, fieldApi, "ext", listOf(fieldApi), BACKFILL_BATCH_SIZE))
        return steps.mapIndexed { i, s -> s.copy(seq = i) }
    }

    /**
     * 可物理化标量 → 真列定义（M1-04 块 5 `type-convert`：EXT 标量提为可写真列）。
     *
     * 仅覆盖 `FieldType.physicalizable=true` 的标量；非可物理化型返回 null（调用方先行守卫）。
     * `DATE`/`DECIMAL` 落 native 列（DECIMAL(30,8)；字段配置的小数位仅展示，存储统一此标度，见 DECISIONS D4）。
     */
    fun physicalColumnOf(field: MdField): PhysicalColumn? {
        val api = field.apiName
        return when (field.logicalType) {
            LogicalType.TEXT -> PhysicalColumn(api, ColumnType.VARCHAR, 512)
            LogicalType.TEXTAREA -> PhysicalColumn(api, ColumnType.TEXT)
            LogicalType.PHONE, LogicalType.EMAIL, LogicalType.URL, LogicalType.PICKLIST, LogicalType.TIME -> PhysicalColumn(api, ColumnType.VARCHAR, 64)
            LogicalType.NUMBER -> PhysicalColumn(api, ColumnType.BIGINT)
            LogicalType.DECIMAL -> PhysicalColumn(api, ColumnType.DECIMAL, DECIMAL_PRECISION, scale = DECIMAL_SCALE)
            LogicalType.DATE -> PhysicalColumn(api, ColumnType.DATE)
            LogicalType.DATETIME -> PhysicalColumn(api, ColumnType.TIMESTAMP)
            LogicalType.BOOL -> PhysicalColumn(api, ColumnType.BOOLEAN)
            LogicalType.LOCATION -> PhysicalColumn(api, ColumnType.VARCHAR, 512)
            else -> null
        }
    }
}
