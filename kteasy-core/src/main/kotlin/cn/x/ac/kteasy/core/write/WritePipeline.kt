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

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.kernel.WallClock
import cn.x.ac.kteasy.core.meta.FieldWritePolicy
import cn.x.ac.kteasy.core.meta.JsonArrays
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MetadataGraph
import cn.x.ac.kteasy.core.meta.MetadataValidator
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.meta.SystemColumns
import cn.x.ac.kteasy.core.meta.TypeRegistry
import cn.x.ac.kteasy.core.schema.SchemaDiff
import java.math.BigDecimal

/**
 * 既有行的归一视图（装配层在事务内 `forUpdate` 读出后交进来）。
 *
 * [values] 是 **ext 与真列合并后的业务字段视图**——管道不关心值原本躺在哪一列，
 * 存储归属只在最后一步（[WritePipeline] 的 split）决定，diff 因此天然跨两种存储。
 */
data class WriteRow(
    val id: String,
    val rowVersion: Long,
    val values: Map<String, DraftValue> = emptyMap(),
    val ownerUser: String? = null,
    val ownerDept: String? = null,
    val approvalState: String? = null,
    val deleted: Boolean = false,
)

/** 阶段 1–7 的输入：全部是已取好的数据，管道内**零 I/O**（可单测的根因）。 */
data class WriteInput(
    val graph: MetadataGraph,
    val ctx: WriteContext,
    val draft: RecordDraft,
    val existing: WriteRow? = null,
)

/**
 * 阶段 1–7 的产物。块 3 的执行层只照此绑定参数，不再做任何业务判断——
 * 「裁决」与「落库」分开，才能让 12 阶段表里的 1–7 全部可单测（图纸 04 §1）。
 *
 * @property columnBindings 系统列 + 业务真列的 JDBC 绑定值（键＝物理列名；值＝JDK 类型，见 [jdbcOf]）
 * @property extValues 待编码进 `ext` 的字段值（键＝api_name；[DraftValue.Cleared] 表示删键）
 * @property expectedVersion 更新语句的 `WHERE row_version = :expected`（P1：带则校、不带则用当前值）
 */
data class WritePlan(
    val id: String,
    val kind: WriteKind,
    val creating: Boolean,
    val columnBindings: Map<String, Any?>,
    val extValues: Map<String, DraftValue>,
    val diff: Map<String, FieldDiff>,
    val warnings: List<WriteWarning>,
    val expectedVersion: Long,
    val rowVersionNext: Long,
    val touchedFields: Set<String>,
    /** 触碰到的 N2N 字段 → 载荷目标 id 集（去重保序）。空＝本次不碰该关联（执行层据此算集合差落 r_ 表）。 */
    val relationTargets: Map<String, List<String>> = emptyMap(),
)

/**
 * 通用写入管道（步骤卡 M1-06 §设计要点 2 的阶段 1–7）。
 *
 * 全系统唯一写入口的裁决半区：`plan()` 顺序固定，段与段之间只传数据。
 *
 * **一处与卡面顺序不同的事实必须写明**（不是违例，是卡面没写全）：阶段 4 的「required 三态」里
 * 「来源豁免＝派生/系统列自动填」的判定，只有在阶段 5 派生之后才知道答案，故必填裁决发生在派生之后
 * （[requiredViolations]）；而类型/策略类违规在派生前就抛（快速失败）。
 * 两批违规各自聚合，承载码仍统一由 `WriteErrors.violation()` 按符号名派生（P2）。
 *
 * @param newId 新建主键生成器（注入以保证单测可复现；缺省 ULID）
 * @param autonum 自动编号取号——**M1-07 实装**。第二参＝截至当前已裁决的字段值快照（含默认值，
 *   供模板「字段变量」段渲染；后置字段取不到属已知边界，渲染为空串）。返回 null 即不填值。
 *   取号失败应抛 [WriteErrors]（如 `AUTONUM_FAILED`），禁静默跳过——静默会让业务号列假绿。
 * @param searchCode 拼音检索码生成器（装配层用 TinyPinyin 实现；缺省原样返回）
 * @param guard 权限接缝（阶段 2；M2-02 换实现）。块 5 的红绿对测靠注入"拒绝全部"守卫证明无旁路。
 */
class WritePipeline(
    private val newId: () -> String = { Ulid.next() },
    private val autonum: (MdField, Map<String, DraftValue>) -> String? = { _, _ -> null },
    private val searchCode: (String) -> String = { it },
    private val guard: WriteGuard = PassthroughWriteGuard(),
) {
    fun plan(
        input: WriteInput,
    ): WritePlan {
        val located = locate(input) // 阶段 1
        guard.check(
            WriteGuardRequest(
                input.ctx,
                input.draft.touchedFields,
                located.id.takeIf { !located.creating },
            ),
        ) // 阶段 2

        val fields = input.graph.fields.associateBy { it.apiName }
        val creating = located.creating
        val accepted = LinkedHashMap<String, DraftValue>()
        val relationTargets = LinkedHashMap<String, List<String>>()
        val early = ArrayList<WriteErrors.FieldViolation>()

        // ---- 阶段 4（键与档位）+ 阶段 3（形状/类型/域）：先收集、一次抛，避免客户端改一处报一处 ----
        for ((api, raw) in input.draft.values) {
            keyViolation(api, fields[api], creating)?.let {
                early += it
                continue
            }
            val field = fields.getValue(api)
            // N2N（块 3A）：载荷＝目标记录 id 列表；管道零 IO 算不了 r_ 表现存集，只校值形并透传目标集，
            // 三集差量（加/删/保留）在执行层持主机行锁时落 r_ 表。不进 accepted/ext/列。
            if (field.logicalType == LogicalType.N2N) {
                val targets = n2nTargetIds(raw)
                if (targets == null) {
                    early += WriteErrors.violation(api, WriteErrors.ID_FIELD_TYPE, "字段 [$api] 是多引用，值须为目标 id 数组或单个 id 串")
                } else {
                    relationTargets[api] = targets
                }
                continue
            }
            val shaped =
                shapeOf(field, raw) ?: run {
                    early += WriteErrors.violation(api, WriteErrors.ID_FIELD_TYPE, "字段 [$api] 值形态与类型 ${field.logicalType.name} 不符")
                    continue
                }
            val canonical = normalizeValue(field, shaped)
            typeViolation(field, canonical)?.let {
                early += it
                continue
            }
            domainViolation(input.graph, field, canonical)?.let {
                early += it
                continue
            }
            // NO_UPDATE 档只在值真的变化时拒（图纸 04 阶段 4「非空变更拒绝」）：
            // 原样把旧值再提交一次也算违规的话，前端整单保存会因为一个不可改字段而全红——那是不能用的行为。
            if (field.writePolicy == FieldWritePolicy.NO_UPDATE && !creating) {
                if (sameValue(input.existing?.values?.get(api), canonical)) {
                    continue // 未变即不触碰：不进 accepted、不进 diff、不写列
                }
                early +=
                    WriteErrors.violation(
                        api,
                        WriteErrors.ID_FIELD_READONLY,
                        "字段 [$api] 禁修改（写策略 NO_UPDATE），值不可变更",
                    )
                continue
            }
            accepted[api] = canonical
        }
        if (early.isNotEmpty()) throw WriteErrors.rejected(early)

        // ---- 阶段 5 派生 ----
        val derived = LinkedHashMap(accepted)
        val warnings = ArrayList<WriteWarning>()
        if (creating) {
            for (f in input.graph.fields) {
                // 派生可填：除「更新路径遇到 NO_UPDATE」外都可填——元数据只读档位恰恰只能靠这里落值
                if (!f.enabled || derived.containsKey(f.apiName)) continue
                if (f.writePolicy == FieldWritePolicy.NO_UPDATE && !creating) continue
                defaultOf(f)?.let { derived[f.apiName] = it }
                if (f.logicalType == LogicalType.AUTONUM) {
                    if (input.ctx.source == WriteSource.IMPORT) {
                        // 【全景】导入不推进编号（M1-07 决策台 P14）：导入文件应自带编号，自带时走正常载荷
                        // 不会进到这里；不带也不代取号（防批量导入一次性吃光号段），记告警放行、字段留空。
                        // 已知后果（如实留档）：导入值与计数器从此分叉，迁移场景需事后对齐计数器。
                        warnings += WriteWarning(WriteWarnings.AUTONUM_SKIPPED, f.apiName, "导入不推进编号，字段留空")
                    } else {
                        autonum(f, derived)?.let { derived[f.apiName] = DraftValue.Text(it) }
                    }
                }
            }
        }
        requiredViolations(input, derived, creating).let { if (it.isNotEmpty()) throw WriteErrors.rejected(it) }

        // ---- 阶段 7 diff（喂 M5 审计与 M3「结果一致跳过」） ----
        val diff = computeDiff(input, fields, derived, creating)
        if (input.ctx.expectedVersion == null && !creating && diff.isNotEmpty()) {
            warnings +=
                WriteWarning(
                    WriteWarnings.OVERRIDE_WITHOUT_VERSION,
                    message = "未带 row_version 即更新，已按当前值覆盖（不丢改动仍由记录写锁保证）",
                )
        }

        // ---- 阶段 6 尾 + 落库切分：存储归属只在这里决定 ----
        val columns = LinkedHashMap<String, Any?>()
        val ext = LinkedHashMap<String, DraftValue>()
        val now = input.ctx.now
        val current = input.existing?.rowVersion ?: 0L
        val expected = input.ctx.expectedVersion ?: current
        val next = current + 1
        columns[SystemColumns.ID] = located.id
        columns["owner_user"] = input.existing?.ownerUser ?: input.ctx.actor.userId
        columns["owner_dept"] = input.existing?.ownerDept ?: input.ctx.actor.deptId
        if (creating) {
            columns["created_at"] = WallClock.utcLocalDateTime(now)
            columns["created_by"] = input.ctx.actor.userId
            // 子项必须挂主（parent_id 是子表 NOT NULL + FK 列）：parentId 只可能来自 writeWithDetails 的注入，
            // 缺它即内部不变量被破坏（有人绕过编排层直接写子对象）——守卫拒，绝不让子行以孤儿落库。
            if (input.graph.objectMeta.kind == ObjectKind.CHILD) {
                columns["parent_id"] = input.ctx.parentId ?: throw WriteErrors.forbidden(
                    "子项写入缺主记录 id（parent_id 只能由编排层注入）",
                    mapOf("object" to input.ctx.objectApi),
                )
            }
        }
        columns["updated_at"] = WallClock.utcLocalDateTime(now)
        columns["updated_by"] = input.ctx.actor.userId
        columns["approval_state"] = input.existing?.approvalState ?: "DRAFT"
        columns["deleted_at"] =
            when (located.kind) {
                WriteKind.DELETED -> WallClock.utcLocalDateTime(now)
                else -> null // RESTORED＝清回未删；UPDATED/CREATED 本来就该是 null
            }
        columns["row_version"] = next

        // ext is whole-column overwrite, so untouched legacy keys must be merged in first --
        // otherwise one PATCH erases every other field living in that column.
        val extStorage =
            input.graph.fields
                .filter { it.storageKind == StorageKind.EXT }
                .map { it.apiName }
                .toSet()
        input.existing?.values?.forEach { (api, old) ->
            if (api in extStorage && !derived.containsKey(api) && old !is DraftValue.Cleared) ext[api] = old
        }
        for (f in input.graph.fields) {
            val v = derived[f.apiName] ?: continue
            if (f.storageKind == StorageKind.EXT) {
                ext[f.apiName] = v
            } else {
                jdbcOf(f, v)?.let { columns[f.apiName] = it }
            }
        }
        // 检索码伴生列（快查字段的拼音前缀，M1-04 建的列）：值变了才重算
        for (api in quickSearchApis(input.graph)) {
            val f = fields[api] ?: continue
            if (!TypeRegistry.of(f.logicalType).pinyinGeneratable) continue
            when (val v = derived[api]) {
                is DraftValue.Text -> columns[SchemaDiff.pinyinColumn(api)] = searchCode(v.value)
                DraftValue.Cleared -> columns[SchemaDiff.pinyinColumn(api)] = null
                else -> Unit
            }
        }
        // ext 清空语义：Cleared 也要进 ext 图（编码时删键），否则「清空字段」写不进库
        for ((api, v) in derived) if (v is DraftValue.Cleared && fields[api]?.storageKind == StorageKind.EXT) ext[api] = v

        return WritePlan(
            id = located.id,
            kind = located.kind,
            creating = creating,
            columnBindings = columns,
            extValues = ext,
            diff = diff,
            warnings = warnings,
            expectedVersion = expected,
            rowVersionNext = next,
            touchedFields = input.draft.touchedFields,
            relationTargets = relationTargets,
        )
    }

    // ---------- 阶段 1 ----------

    /** 内部：阶段 1 的裁决产物（[WriteKind] 由意图 + id 有无 + 既有行是否存在派生，调用方不得自declare）。 */
    private data class Located(
        val id: String,
        val kind: WriteKind,
        val creating: Boolean,
    )

    private fun locate(
        input: WriteInput,
    ): Located {
        val obj = input.graph.objectMeta
        if (obj.disabled || obj.status != "ACTIVE") throw WriteErrors.objectDisabled(obj.apiName)
        val id = input.ctx.recordId
        val row = input.existing
        return when (input.ctx.intent) {
            WriteIntent.UPSERT -> {
                if (id == null) {
                    Located(newId(), WriteKind.CREATED, true)
                } else if (row == null || row.deleted) {
                    throw WriteErrors.notFound(obj.apiName, id)
                } else {
                    Located(id, WriteKind.UPDATED, false)
                }
            }

            // 删除/恢复做成幂等：已删再删、未删先恢复都按无变化处理（diff 空），
            // 因为批量导入与开放接口重试时「重复删」不该变成事故。
            WriteIntent.DELETE -> {
                if (id == null || row == null) {
                    throw WriteErrors.notFound(obj.apiName, id ?: "(无 id)")
                } else {
                    Located(id, WriteKind.DELETED, false)
                }
            }

            WriteIntent.RESTORE -> {
                if (id == null || row == null) {
                    throw WriteErrors.notFound(obj.apiName, id ?: "(无 id)")
                } else {
                    Located(id, WriteKind.RESTORED, false)
                }
            }
        }
    }

    // ---------- 阶段 3/4 裁决 ----------

    /** 键级裁决：系统列、未注册、已停用、当前通道尚不支持的存储型。 */
    private fun keyViolation(
        api: String,
        field: MdField?,
        creating: Boolean,
    ): WriteErrors.FieldViolation? {
        if (api in SystemColumns.ALL || api == "parent_id") {
            return WriteErrors.violation(api, WriteErrors.ID_SYSTEM_COLUMN_READONLY, "系统列 [$api] 不接受调用方提供值（含 OPENAPI）")
        }
        if (api.isBlank()) return WriteErrors.violation("(空)", WriteErrors.ID_EXT_UNKNOWN_KEY, "字段名不得为空")
        val f =
            field
                ?: return WriteErrors.violation(api, WriteErrors.ID_EXT_UNKNOWN_KEY, "对象上没有名为 [$api] 的字段（未注册键一律拒收，宽容性在上游清洗器）")
        if (!f.enabled) return WriteErrors.violation(api, WriteErrors.ID_FIELD_DISABLED, "字段 [$api] 已停用，不接受写入")
        // N2N 不在此拒（块 3A 已实装集合差量写）：其载荷值形校验与目标集透传在 plan 主循环里单独处理。
        // 档位判定。NO_UPDATE 不在此拒——交给调用点做「同值即不触碰」豁免（见 plan 的阶段 4 注释）；
        // 其余档位（元数据只读、自动化下发位、禁新建）一律硬拒，且对任何来源都成立（P3）。
        val blockedByPolicy =
            when (f.writePolicy) {
                FieldWritePolicy.READONLY,
                FieldWritePolicy.DERIVED,
                -> true

                FieldWritePolicy.NO_CREATE -> creating

                FieldWritePolicy.WRITABLE, FieldWritePolicy.NO_UPDATE -> false
            }
        if (blockedByPolicy) {
            return WriteErrors.violation(
                api,
                WriteErrors.ID_FIELD_READONLY,
                "字段 [$api] 写策略 ${f.writePolicy.name}，${if (creating) "新建" else "更新"}时不可由调用方提供值",
            )
        }
        if (f.logicalType == LogicalType.AUTONUM) {
            return WriteErrors.violation(api, WriteErrors.ID_FIELD_READONLY, "字段 [$api] 是自动编号，只能由服务端取号生成")
        }
        return null
    }

    /** N2N 载荷值形归一：接受 `Many`（目标 id 列表）或单个 `Text`（一个 id）；去空、去重、保序。非法返回 null。 */
    private fun n2nTargetIds(
        raw: DraftValue,
    ): List<String>? {
        val items =
            when (raw) {
                is DraftValue.Many -> raw.items
                is DraftValue.Text -> listOf(raw.value)
                else -> return null
            }
        val clean = LinkedHashSet<String>()
        for (id in items) {
            if (id.isBlank()) return null
            clean += id
        }
        return clean.toList()
    }

    /** 值形态归一（不做内容校验）：多值型接受单值串，标量型拒绝数组。 */
    private fun shapeOf(
        field: MdField,
        value: DraftValue,
    ): DraftValue? {
        if (value is DraftValue.Cleared) return value
        val multi = field.logicalType in MULTI_TYPES
        return when {
            multi && value is DraftValue.Many -> {
                if (value.items.any { it.isBlank() }) null else DraftValue.Many(value.items.map { it.trim() })
            }

            multi && value is DraftValue.Text -> {
                DraftValue.Many(listOf(value.value.trim()))
            }

            multi -> {
                null
            }

            !multi && value is DraftValue.Many -> {
                null
            }

            field.logicalType in NUMERIC_TYPES -> {
                when (value) {
                    is DraftValue.Number -> {
                        value
                    }

                    is DraftValue.Text -> {
                        value.value.toLongOrNull()?.let { DraftValue.Number(it.toString()) }
                            ?: value.value.toBigDecimalOrNull()?.let { DraftValue.Number(it.toPlainString()) }
                            ?: DraftValue.Number(value.value.trim())
                    }

                    else -> {
                        null
                    }
                }
            }

            field.logicalType == LogicalType.BOOL -> {
                when (value) {
                    is DraftValue.Bool -> {
                        value
                    }

                    is DraftValue.Text -> {
                        value.value.trim().lowercase().let {
                            if (it in BOOL_TRUE) {
                                DraftValue.Bool(true)
                            } else if (it in BOOL_FALSE) {
                                DraftValue.Bool(false)
                            } else {
                                null
                            }
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                if (value is DraftValue.Text) value else null
            }
        }
    }

    /** 内容校验（正则/长度等格式级；语义域校验在 [domainViolation]）。 */
    private fun typeViolation(
        field: MdField,
        value: DraftValue,
    ): WriteErrors.FieldViolation? {
        val ft = TypeRegistry.of(field.logicalType)
        val raws =
            when (value) {
                // 多值型（标签/附件/多选）在存储与类型层都是「JSON 字符串数组」一整个值，故按整串校验；
                // 逐元素校验会把 `Tags.validate` 的契约用错，标签就永远校验不过。
                is DraftValue.Many -> listOfNotNull(JsonArrays.canonicalStringArray(value.items))

                is DraftValue.Text -> listOf(value.value)

                is DraftValue.Number -> listOf(value.literal)

                is DraftValue.Bool -> listOf(value.value.toString())

                is DraftValue.Cleared -> return null
            }
        raws.forEach { r -> ft.validate(r)?.let { return WriteErrors.violation(field.apiName, WriteErrors.ID_FIELD_TYPE, it) } }
        return null
    }

    /** 候选域校验：下拉/多选取封闭选项集（标签可选可输故不校）；分类取字典路径。 */
    private fun domainViolation(
        graph: MetadataGraph,
        field: MdField,
        value: DraftValue,
    ): WriteErrors.FieldViolation? {
        val items = if (value is DraftValue.Many) value.items else listOfNotNull((value as? DraftValue.Text)?.value)
        return when (field.logicalType) {
            LogicalType.PICKLIST, LogicalType.MULTISELECT -> {
                val codes =
                    graph.options
                        .filter { it.setId == field.optionSetId && it.enabled }
                        .map { it.code }
                        .toSet()
                items
                    .firstOrNull { it !in codes }
                    ?.let { WriteErrors.violation(field.apiName, WriteErrors.ID_OPTION_DOMAIN, "字段 [${field.apiName}] 的值 [$it] 不在选项候选内") }
            }

            LogicalType.DICT -> {
                val paths =
                    graph.dictItems
                        .filter { it.dictId == field.dictId && it.enabled }
                        .map { it.path }
                        .toSet()
                items
                    .firstOrNull { it !in paths }
                    ?.let { WriteErrors.violation(field.apiName, WriteErrors.ID_OPTION_DOMAIN, "字段 [${field.apiName}] 的分类路径 [$it] 不存在") }
            }

            else -> {
                null
            }
        }
    }

    /**
     * 值等价判定——diff 与 `NO_UPDATE` 共用同一把尺（两处判据不一致会造出「diff 说没变、档位说变了」）。
     *
     * **数字按值比、不按字符串比**：`12.50` 写进 ext 后 MySQL 的 JSON 规范化回读成 `12.5`
     * （PG 的 jsonb 保留尾零），字符串等值会把一次「什么都没改」的整单保存误判成变更——
     * 代价不只是脏 diff，M3「结果一致则跳过」会失效并连带触发级联。块 5 双库 IT 抓到。
     */
    private fun sameValue(
        old: DraftValue?,
        @Suppress("PARAMETER_NAME") new: DraftValue,
    ): Boolean =
        when {
            new is DraftValue.Cleared -> old == null || old is DraftValue.Cleared
            old is DraftValue.Number && new is DraftValue.Number -> numberEquals(old.literal, new.literal)
            old is DraftValue.Many && new is DraftValue.Many -> old.items == new.items
            else -> old == new
        }

    /**
     * 解析不动就退回字符串相等：宁可不判等（多出一条 diff），也不把非法数字当 0——
     * 后者会让一次假变更变成一次假跳过。
     */
    private fun numberEquals(
        a: String,
        b: String,
    ): Boolean =
        try {
            BigDecimal(a).compareTo(BigDecimal(b)) == 0
        } catch (e: NumberFormatException) {
            a == b
        }

    /**
     * 必填三态（阶段 4）。两件事必须在这里讲清，否则实现一定会做错：
     *
     * ① **「来源豁免」的判定在派生之后**——系统列/派生/编号自动填的值也算满足，故本函数看的是派生完的
     *   [derived]，不是调用方原始载荷；
     * ② **更新路径上库里已有值即视为满足**——必填约束的是「记录必须有值」，不是「每次提交都必须带上」；
     *   否则 PATCH 单个字段会被其他必填字段全部拦下，等于逼客户端每次回传整行。
     */
    private fun requiredViolations(
        input: WriteInput,
        derived: Map<String, DraftValue>,
        creating: Boolean,
    ): List<WriteErrors.FieldViolation> {
        val missing = ArrayList<WriteErrors.FieldViolation>()
        for (f in input.graph.fields) {
            if (!f.enabled || !f.isRequiredOn(creating)) continue
            // N2N 的"必填"＝关联至少一条，其存在性在 r_ 表侧、管道看不到载荷是否清空集，本块先不裁决（留桩）。
            if (f.logicalType == LogicalType.N2N) continue
            val submitted = derived[f.apiName]
            if (submitted != null && submitted !is DraftValue.Cleared) continue
            if (!creating && input.existing?.values?.get(f.apiName) != null) continue
            missing +=
                WriteErrors.violation(
                    f.apiName,
                    WriteErrors.ID_FIELD_REQUIRED,
                    "字段 [${f.apiName}] 必填（作用域 ${f.requiredScope.name}）",
                )
        }
        return missing
    }

    // ---------- 阶段 6 净化 / 阶段 7 差量 / 落库切分 ----------

    private fun normalizeValue(
        field: MdField,
        value: DraftValue,
    ): DraftValue =
        when (value) {
            is DraftValue.Text -> TypeRegistry.of(field.logicalType).normalize(value.value)?.let { DraftValue.Text(it) } ?: DraftValue.Cleared

            // 多值型整体是一个 JSON 数组值，逐元素 normalize 会把每个元素当数组解析而全部判 null
            is DraftValue.Many -> value

            else -> value
        }

    private fun computeDiff(
        input: WriteInput,
        fields: Map<String, MdField>,
        derived: Map<String, DraftValue>,
        creating: Boolean,
    ): Map<String, FieldDiff> {
        val out = LinkedHashMap<String, FieldDiff>()
        for ((api, nv) in derived) {
            if (fields[api] == null) continue
            val old = input.existing?.values?.get(api)
            val newVal = if (nv is DraftValue.Cleared) null else nv
            if (creating) {
                if (newVal != null) out[api] = FieldDiff(api, null, newVal)
            } else if (newVal == null) {
                if (old != null && old !is DraftValue.Cleared) out[api] = FieldDiff(api, old, null)
            } else if (!sameValue(old, newVal)) {
                out[api] = FieldDiff(api, old, newVal)
            }
        }
        return out
    }

    /**
     * 真列绑定值（JDBC 友好的 JDK 类型）。时间值统一走 [WallClock]，与查询侧同一形状（两库可比）。
     *
     * 留桩：ANYREF 的伴生 `_obj` 列（存被引用对象 api_name）需要目标对象解析，随 M1-07 关联差量一并落地——
     * 现在只写 id 列，软校验缺位在这是**已知**的，不是漏的。
     */
    private fun jdbcOf(
        field: MdField,
        value: DraftValue,
    ): Any? =
        when {
            value is DraftValue.Cleared -> {
                null
            }

            field.logicalType in NUMERIC_TYPES -> {
                val lit = (value as? DraftValue.Number)?.literal ?: (value as? DraftValue.Text)?.value ?: return null
                if (field.logicalType == LogicalType.NUMBER) lit.toLongOrNull() ?: BigDecimal(lit) else BigDecimal(lit)
            }

            value is DraftValue.Bool -> {
                value.value
            }

            // 时间型真列必须绑 java.time 值：绑字符串在 PG 的**赋值位**会被判成 varchar
            // （查询侧的比较语境能推断，故这个坑只在写入通道暴露——块 5 实测）。
            field.logicalType == LogicalType.DATE -> {
                (value as? DraftValue.Text)?.value?.let { dateLiteral(it) }
            }

            field.logicalType == LogicalType.DATETIME -> {
                (value as? DraftValue.Text)?.value?.let { dateTimeLiteral(it) }
            }

            field.logicalType == LogicalType.TIME -> {
                (value as? DraftValue.Text)?.value?.let { timeLiteral(it) }
            }

            value is DraftValue.Many -> {
                value.items.joinToString(",")
            }

            value is DraftValue.Text -> {
                value.value
            }

            else -> {
                null
            }
        }

    /**
     * 时间字面量解析。解析失败**直接抛**、不退回绑字符串：M1-04 的类型矩阵已管住格式，
     * 走到这里还解析不动就说明两侧格式约定分叉了——那是要当场看见的 bug，不是可静默绕过的输入问题。
     */
    private fun dateLiteral(
        raw: String,
    ): java.time.LocalDate = java.time.LocalDate.parse(raw.trim().take(10))

    private fun dateTimeLiteral(
        raw: String,
    ): java.time.LocalDateTime {
        val s = raw.trim().replace('T', ' ')
        val padded = if (s.length == 19) s else (s + ":00").take(19)
        return java.time.LocalDateTime.parse(padded)
    }

    private fun timeLiteral(
        raw: String,
    ): java.time.LocalTime = java.time.LocalTime.parse(raw.trim().take(8))

    private fun defaultOf(
        field: MdField,
    ): DraftValue? {
        val raw = field.defaultJson?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        MetadataValidator.parseStringArray(raw)?.let { return if (it.isEmpty()) null else DraftValue.Many(it) }
        val unquoted = raw.removeSurrounding("\"")
        return if (field.logicalType in MULTI_TYPES) DraftValue.Many(listOf(unquoted)) else DraftValue.Text(unquoted)
    }

    private fun quickSearchApis(
        graph: MetadataGraph,
    ): List<String> = MetadataValidator.parseStringArray(graph.objectMeta.quickSearchJson) ?: emptyList()

    private companion object {
        val MULTI_TYPES = setOf(LogicalType.MULTISELECT, LogicalType.TAGS, LogicalType.FILE, LogicalType.IMAGE, LogicalType.AVATAR)
        val NUMERIC_TYPES = setOf(LogicalType.NUMBER, LogicalType.DECIMAL)
        val BOOL_TRUE = setOf("true", "1", "yes", "y", "t")
        val BOOL_FALSE = setOf("false", "0", "no", "n", "f")
    }
}
