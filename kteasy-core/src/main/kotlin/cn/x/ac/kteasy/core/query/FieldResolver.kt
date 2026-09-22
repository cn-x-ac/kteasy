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

import cn.x.ac.kteasy.core.meta.FieldType
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.MetadataValidator
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.meta.SystemColumns
import cn.x.ac.kteasy.core.meta.TypeRegistry
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.ValueCast

/** 根表别名（块2/块4 共用）。 */
const val ROOT_ALIAS: String = "t0"

/**
 * 解析后的取值定位 + 类型信息。
 *
 * @property n2nField 末端字段为 N2N 时携带（供 `has()` 构造 EXISTS），否则 null
 */
data class ResolvedValue(
    val location: ValueLocation,
    val logicalType: LogicalType,
    val fieldType: FieldType,
)

/**
 * 一条 N2N 回溯产生的 EXISTS 作用域：块4 渲染为
 * `EXISTS(SELECT 1 FROM <relTable> s JOIN <targetTable> <targetAlias> ON s.dst=targetAlias.id WHERE s.src=<hostAlias>.id AND <inner>)`。
 */
data class ExistsScope(
    val hostAlias: String,
    val relTable: String,
    val srcColumn: String,
    val dstColumn: String,
    val targetTable: String,
    val targetAlias: String,
)

/** 一条点链的解析结果：末端 [terminal] + 若穿过 N2N 则带 [exists]（本卡支持至多一层 N2N，见类注释）。 */
data class ResolvedPath(
    val terminal: ResolvedValue,
    val exists: ExistsScope?,
)

/** 解析用的当前作用域：对象 + 其表别名。 */
private data class Scope(
    val objectMeta: MdObject,
    val alias: String,
)

/**
 * **统一取值/关联回溯解析器**（图纸 03 §1.1：引擎唯一入口；display_name 模板/EQL/单据/公式后续复用）。
 *
 * REF 点后自动 join（进 FROM 的 `Join`，按路径去重复用）；N2N 点后＝EXISTS（[ExistsScope]）。
 * 只产定位与类型、累积 join，绝不渲染 SQL、不碰方言（红线④⑤）。
 *
 * ⟨本卡边界，见证据 §2⟩：N2N 仅支持单层回溯（`has(rel)` / `has(rel,id)` / `rel.<目标直接字段> op v`）；
 * 多层穿过 N2N（如 `rel.ref.field`）报 [EqlErrors.typeMismatch] 并划归 M1-07/M2。REF 点链 ≤3 跳完整支持。
 */
class FieldResolver(
    private val lookup: MetadataLookup,
) {
    private val joins = LinkedHashMap<String, Join>()
    private var joinSeq = 1
    private var existsSeq = 1

    fun joinList(): List<Join> = joins.values.toList()

    fun resolveFromRoot(
        rootObject: MdObject,
        path: FieldPath,
    ): ResolvedPath = resolve(Scope(rootObject, ROOT_ALIAS), path.segments)

    private fun refJoin(
        hostAlias: String,
        hostColumn: String,
        target: MdObject,
    ): Join {
        val key = "$hostAlias.$hostColumn->${target.apiName}"
        joins[key]?.let { return it }
        val alias = "t${joinSeq++}"
        return Join(alias, target.apiName, hostAlias, hostColumn).also { joins[key] = it }
    }

    private fun resolve(
        scope: Scope,
        segments: List<String>,
    ): ResolvedPath {
        val head = segments.first()
        val rest = segments.drop(1)
        val current = resolveHead(scope, head)
        if (rest.isEmpty()) return ResolvedPath(current, null)

        return when (current.logicalType) {
            LogicalType.REF -> {
                val target = refTarget(scope, head, current)
                val join = refJoin(scope.alias, (current.location as ValueLocation.Column).column, target)
                resolve(Scope(target, join.alias), rest)
            }

            LogicalType.N2N -> {
                if (rest.size > 1) {
                    throw EqlErrors.typeMismatch("N2N 点后暂不支持多层回溯 [$head.${rest.joinToString(".")}]（归 M1-07/M2）")
                }
                val field = requireNotNull(current.n2nFieldOf(scope, head))
                val targetApi =
                    field.refObjectId?.let { lookup.objectById(it)?.apiName }
                        ?: throw EqlErrors.fieldUnknown("N2N 字段 [$head] 无引用目标对象")
                val target = lookup.objectByApi(targetApi) ?: throw EqlErrors.fieldUnknown("N2N 目标对象 [$targetApi] 不存在")
                val tAlias = "e${existsSeq++}"
                val exists =
                    ExistsScope(
                        hostAlias = scope.alias,
                        relTable = SchemaDiff.relationTableLogicalName(scope.objectMeta, field),
                        srcColumn = SchemaDiff.relationSourceColumn(scope.objectMeta.apiName),
                        dstColumn = SchemaDiff.relationTargetColumn(targetApi),
                        targetTable = targetApi,
                        targetAlias = tAlias,
                    )
                val innerScope = Scope(target, tAlias)
                val term = resolveHead(innerScope, rest.single())
                ResolvedPath(term, exists)
            }

            else -> {
                throw EqlErrors.typeMismatch("字段 [$head] 类型 ${current.logicalType} 不支持点链回溯（仅 REF/N2N）")
            }
        }
    }

    private fun resolveHead(
        scope: Scope,
        api: String,
    ): ResolvedValue {
        if (api == "ext") throw EqlErrors.fieldUnknown("字段名 [ext] 为内部存储列保留名")
        if (api in SystemColumns.ALL) return systemColumn(scope, api)
        val field = fieldOf(scope, api) ?: throw EqlErrors.fieldUnknown("对象 [${scope.objectMeta.apiName}] 无字段 [$api]")
        if (!field.enabled) throw EqlErrors.fieldUnknown("字段 [${field.apiName}] 已停用")
        val ft = TypeRegistry.of(field.logicalType)
        val location =
            when (field.storageKind) {
                StorageKind.EXT -> ValueLocation.Ext(scope.alias, JsonPath.of(api), ft.cast ?: ValueCast.TEXT)
                StorageKind.COLUMN -> ValueLocation.Column(scope.alias, api)
                StorageKind.N2N -> ValueLocation.Column(scope.alias, api)
            }
        return ResolvedValue(location, field.logicalType, ft)
    }

    /** 该字段的拼音检索伴生列（若可 `~`）；与 [SchemaDiff] 产列条件一致。 */
    fun pinyinColumn(
        rootObject: MdObject,
        api: String,
    ): ValueLocation.Column? {
        val field = lookup.fieldsByObject(rootObject.id).firstOrNull { it.apiName == api } ?: return null
        if (!TypeRegistry.of(field.logicalType).pinyinGeneratable) return null
        val quick = MetadataValidator.parseStringArray(rootObject.quickSearchJson)?.toSet() ?: return null
        if (api !in quick) return null
        return ValueLocation.Column(ROOT_ALIAS, SchemaDiff.pinyinColumn(api))
    }

    /** 取末端字段对象（供编译器判 has()/N2N）。仅根对象首段有效。 */
    fun headField(
        rootObject: MdObject,
        api: String,
    ): MdField? = lookup.fieldsByObject(rootObject.id).firstOrNull { it.apiName == api && it.enabled }

    /**
     * 为一个 0 跳的 N2N 字段直接构造 EXISTS 作用域（供 `has(rel)` / `has(rel, id)`）；非 N2N 或字段不存在返回 null。
     *
     * `has(rel)` 仅需关联表存在性（dst 侧不强连目标表）；`has(rel, id)` 以 dst 参数命中。targetTable/alias 供后续如需展开。
     */
    fun n2nScope(
        rootObject: MdObject,
        api: String,
    ): ExistsScope? {
        val field = headField(rootObject, api) ?: return null
        if (field.logicalType != LogicalType.N2N) return null
        val targetApi = field.refObjectId?.let { lookup.objectById(it)?.apiName }
        return ExistsScope(
            hostAlias = ROOT_ALIAS,
            relTable = SchemaDiff.relationTableLogicalName(rootObject, field),
            srcColumn = SchemaDiff.relationSourceColumn(rootObject.apiName),
            dstColumn = SchemaDiff.relationTargetColumn(targetApi),
            targetTable = targetApi ?: "",
            targetAlias = "e${existsSeq++}",
        )
    }

    private fun fieldOf(
        scope: Scope,
        api: String,
    ): MdField? = lookup.fieldsByObject(scope.objectMeta.id).firstOrNull { it.apiName == api }

    private fun ResolvedValue.n2nFieldOf(
        scope: Scope,
        api: String,
    ): MdField? = if (logicalType == LogicalType.N2N) fieldOf(scope, api) else null

    private fun refTarget(
        scope: Scope,
        head: String,
        current: ResolvedValue,
    ): MdObject {
        if (head in MetadataValidator.REFERENCE_SYSTEM_COLUMNS) {
            val api = if (head == "owner_dept") "md_dept" else "md_user"
            return lookup.objectByApi(api)
                ?: throw EqlErrors.fieldUnknown("对象 [$api] 尚未建模（引用系统列跨对象回溯需 M2 用户/部门表）")
        }
        val field = fieldOf(scope, head)
        val targetId = field?.refObjectId ?: throw EqlErrors.typeMismatch("字段 [$head] 不可作为引用回溯")
        return lookup.objectById(targetId) ?: throw EqlErrors.fieldUnknown("引用目标对象 [${field.refObjectId}] 不存在")
    }

    private fun systemColumn(
        scope: Scope,
        api: String,
    ): ResolvedValue {
        val logical =
            when {
                api in MetadataValidator.REFERENCE_SYSTEM_COLUMNS -> LogicalType.REF
                api in setOf("created_at", "updated_at", "deleted_at") -> LogicalType.DATETIME
                api == "row_version" -> LogicalType.NUMBER
                else -> LogicalType.TEXT
            }
        return ResolvedValue(
            location = ValueLocation.Column(scope.alias, api),
            logicalType = logical,
            fieldType = TypeRegistry.of(logical),
        )
    }
}
