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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.MetadataAction
import cn.x.ac.kteasy.core.kernel.MetadataChangedEvent
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.FieldWritePolicy
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdDep
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.MetadataGraph
import cn.x.ac.kteasy.core.meta.MetadataValidator
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.RequiredScope
import cn.x.ac.kteasy.core.meta.StorageKind
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * md 区治理服务（M1-01）：保存校验编排、复制对象、mdWrite 事务包装与失效事件发布。
 *
 * - 全部写路径经 [mdWrite]：单事务 + 事务内发布 [MetadataChangedEvent]——
 *   事务回滚则 AFTER_COMMIT 监听不触发，缓存版本号不动（【规格】§8-⑦ 防脏读）。
 * - 违规统一抛 [KnownKteasyException]（420 BUSINESS_RULE，`data.violations[]` 人话明细）。
 * - 审计钩子：M5 审计在 [mdWrite] 的提交后段插桩（本卡只留包装点）。
 */
@Service
class MetadataService(
    private val repository: MetadataRepository,
    private val tx: TransactionTemplate,
    private val publisher: ApplicationEventPublisher,
) {
    /** M2 权限实装前的操作者占位：治理面暂由 boot token 单人操作。 */
    private val systemActor = "system"

    // ---------- 命令载体 ----------

    data class FieldCmd(
        val apiName: String,
        val label: String,
        val logicalType: String,
        val storageKind: String? = null,
        val required: Boolean = false,
        val defaultJson: String? = null,
        val validationJson: String? = null,
        val uiJson: String? = null,
        val refObjectApi: String? = null,
        val refAnyObjsJson: String? = null,
        val dictId: String? = null,
        val optionSetId: String? = null,
        val seq: Int = 0,
        /**
         * 写策略档位（M1-06）。与 [logicalType]/[storageKind] 同风格用字符串承载：
         * 非法值在服务层统一折成 `violations` 契约体，不在控制器猜枚举名。null＝缺省 WRITABLE。
         */
        val writePolicy: String? = null,
        /** 必填作用域（ALWAYS/CREATE/UPDATE），仅当 [required]=true 有意义。null＝缺省 ALWAYS。 */
        val requiredScope: String? = null,
    )

    data class ObjectCreateCmd(
        val apiName: String,
        val label: String,
        val kind: String,
        val parentApi: String? = null,
        val displayName: String,
        val quickSearchFields: List<String> = emptyList(),
        val fields: List<FieldCmd> = emptyList(),
    )

    data class ObjectUpdateCmd(
        val label: String? = null,
        val status: String? = null,
        val disabled: Boolean? = null,
        val quickSearchFields: List<String>? = null,
        val displayName: String? = null,
    )

    data class FieldUpdateCmd(
        val label: String? = null,
        val required: Boolean? = null,
        val defaultJson: String? = null,
        val validationJson: String? = null,
        val uiJson: String? = null,
        val seq: Int? = null,
        val enabled: Boolean? = null,
        val writePolicy: String? = null,
        val requiredScope: String? = null,
    )

    data class CopyCmd(
        val apiName: String,
        val label: String,
    )

    // ---------- 对象 ----------

    fun createObject(cmd: ObjectCreateCmd): MdObject =
        mdWrite(MetadataAction.OBJECT_CREATED, cmd.apiName) {
            repository.assertObjectApiAvailable(cmd.apiName)
            val kind =
                runCatching { ObjectKind.valueOf(cmd.kind.uppercase()) }.getOrElse {
                    throw badRequest(listOf("对象 kind [${cmd.kind}] 非法（允许 PARENT/CHILD/PLAIN）"))
                }
            val parent = cmd.parentApi?.let { requireObject(it) }
            // 对象与字段的 objectId 必须同源：先生成对象 id，再逐字段绑定（禁每字段各取新 ULID）
            val objectId = Ulid.next()
            val fields = cmd.fields.map { buildField(objectId, it, fields = emptyList()) }
            val obj =
                MdObject(
                    id = objectId,
                    apiName = cmd.apiName,
                    label = cmd.label,
                    kind = kind,
                    parentObjectId = parent?.id,
                    displayName = cmd.displayName,
                    quickSearchJson = toJsonArray(cmd.quickSearchFields),
                    createdBy = systemActor,
                    updatedBy = systemActor,
                )
            val violations = MetadataValidator.checkObject(obj, fields, parent)
            throwOnViolations(violations)
            repository.insertObject(obj)
            fields.forEach { repository.insertField(it) }
            repository.findObjectByApi(cmd.apiName)!!
        }

    fun updateObject(
        api: String,
        cmd: ObjectUpdateCmd,
    ): MdObject =
        mdWrite(MetadataAction.OBJECT_UPDATED, api) {
            val existing = requireObject(api)
            val fields = repository.listFieldsByObjectId(existing.id)
            val updated =
                existing.copy(
                    label = cmd.label ?: existing.label,
                    status = cmd.status ?: existing.status,
                    disabled = cmd.disabled ?: existing.disabled,
                    displayName = cmd.displayName ?: existing.displayName,
                    quickSearchJson =
                        cmd.quickSearchFields?.let { toJsonArray(it) } ?: existing.quickSearchJson,
                )
            val parent = existing.parentObjectId?.let { repository.findObjectById(it) }
            throwOnViolations(MetadataValidator.checkObject(updated, fields, parent))
            repository.updateObjectCore(
                id = existing.id,
                label = cmd.label,
                status = cmd.status,
                disabled = cmd.disabled,
                displayName = cmd.displayName,
                quickSearchJson = cmd.quickSearchFields?.let { toJsonArray(it) },
                updatedBy = systemActor,
            )
            repository.findObjectByApi(api)!!
        }

    /** DELETE 语义 = 停用（物理表处置归 M1-03 schema_change_job，本卡不做破坏性删除）。 */
    fun disableObject(api: String): MdObject =
        mdWrite(MetadataAction.OBJECT_UPDATED, api) {
            val existing = requireObject(api)
            repository.setObjectDisabled(existing.id, disabled = true, updatedBy = systemActor)
            repository.findObjectByApi(api)!!
        }

    fun copyObject(
        sourceApi: String,
        cmd: CopyCmd,
    ): MdObject =
        mdWrite(MetadataAction.OBJECT_COPIED, cmd.apiName) {
            repository.assertObjectApiAvailable(cmd.apiName)
            val source = requireObject(sourceApi)
            val sourceFields = repository.listFieldsByObjectId(source.id).filter { it.enabled }
            val idMap = sourceFields.associate { it.id to Ulid.next() }
            val newObjectId = Ulid.next()
            val copied =
                sourceFields.map {
                    it.copy(id = idMap.getValue(it.id), objectId = newObjectId)
                }
            val obj =
                source.copy(
                    id = newObjectId,
                    apiName = cmd.apiName,
                    label = cmd.label,
                    displayName = source.displayName,
                    status = "ACTIVE",
                    disabled = false,
                    createdBy = systemActor,
                    updatedBy = systemActor,
                )
            val boundFields = copied.map { it.copy(objectId = obj.id) }
            val parent = obj.parentObjectId?.let { repository.findObjectById(it) }
            throwOnViolations(MetadataValidator.checkObject(obj, boundFields, parent))
            repository.insertObject(obj)
            boundFields.forEach { repository.insertField(it) }
            repository.findObjectByApi(cmd.apiName)!!
        }

    // ---------- 字段 ----------

    fun createField(
        objectApi: String,
        cmd: FieldCmd,
    ): MdField =
        mdWrite(MetadataAction.FIELD_CHANGED, objectApi) {
            val obj = requireObject(objectApi)
            repository.findFieldByApi(obj.id, cmd.apiName)?.let {
                throw badRequest(listOf("字段 [${cmd.apiName}] 在对象 [$objectApi] 内已存在"))
            }
            val field = buildField(obj.id, cmd, fields = repository.listFieldsByObjectId(obj.id))
            throwOnViolations(MetadataValidator.checkField(field))
            repository.insertField(field)
            repository.findFieldByApi(obj.id, cmd.apiName)!!
        }

    fun updateField(
        objectApi: String,
        fieldApi: String,
        cmd: FieldUpdateCmd,
    ): MdField =
        mdWrite(MetadataAction.FIELD_CHANGED, objectApi) {
            val obj = requireObject(objectApi)
            val existing =
                repository.findFieldByApi(obj.id, fieldApi)
                    ?: throw notFound("字段 [$fieldApi] 不存在于对象 [$objectApi]")
            val merged =
                existing.copy(
                    label = cmd.label ?: existing.label,
                    required = cmd.required ?: existing.required,
                    defaultJson = cmd.defaultJson ?: existing.defaultJson,
                    validationJson = cmd.validationJson ?: existing.validationJson,
                    uiJson = cmd.uiJson ?: existing.uiJson,
                    seq = cmd.seq ?: existing.seq,
                    enabled = cmd.enabled ?: existing.enabled,
                    writePolicy = cmd.writePolicy?.let { parseWritePolicy(fieldApi, it) } ?: existing.writePolicy,
                    requiredScope = cmd.requiredScope?.let { parseRequiredScope(fieldApi, it) } ?: existing.requiredScope,
                )
            throwOnViolations(MetadataValidator.checkField(merged))
            repository.updateFieldEditable(
                id = existing.id,
                label = cmd.label,
                required = cmd.required,
                defaultJson = cmd.defaultJson,
                validationJson = cmd.validationJson,
                uiJson = cmd.uiJson,
                seq = cmd.seq,
                enabled = cmd.enabled,
                writePolicy = if (cmd.writePolicy != null) merged.writePolicy else null,
                requiredScope = if (cmd.requiredScope != null) merged.requiredScope else null,
            )
            repository.findFieldByApi(obj.id, fieldApi)!!
        }

    /** DELETE 语义 = 逻辑停用；EXT 字段的历史数据留在 ext 内（M1-06 写路径拒收）。 */
    fun disableField(
        objectApi: String,
        fieldApi: String,
    ): MdField = updateField(objectApi, fieldApi, FieldUpdateCmd(enabled = false))

    /** 契约面 `PATCH /api/md/field/{id}`：按字段 id 定位（字段 id 全局唯一）。 */
    fun updateFieldById(
        fieldId: String,
        cmd: FieldUpdateCmd,
    ): MdField {
        val existing =
            repository.findFieldById(fieldId)
                ?: throw notFound("字段 id [$fieldId] 不存在")
        val obj = repository.findObjectById(existing.objectId) ?: throw notFound("字段宿主对象不存在")
        return updateField(obj.apiName, existing.apiName, cmd)
    }

    /** 契约面 `DELETE /api/md/field/{id}`：逻辑停用（EXT 历史数据留 ext，M1-06 写路径拒收）。 */
    fun disableFieldById(fieldId: String): MdField {
        val existing =
            repository.findFieldById(fieldId)
                ?: throw notFound("字段 id [$fieldId] 不存在")
        val obj = repository.findObjectById(existing.objectId) ?: throw notFound("字段宿主对象不存在")
        return disableField(obj.apiName, existing.apiName)
    }

    // ---------- 快照 / 图谱 ----------

    fun loadSnapshot(): MetadataSnapshot =
        MetadataSnapshot(
            objects = repository.listObjects(),
            fields = repository.listAllFields(),
            dicts = repository.listAllDicts(),
            dictItems = repository.listAllDictItems(),
            optionSets = repository.listAllOptionSets(),
            options = repository.listAllOptions(),
            deps = repository.listAllDeps(),
        )

    fun buildGraph(
        snapshot: MetadataSnapshot,
        api: String,
    ): MetadataGraph {
        val obj =
            snapshot.objects.firstOrNull { it.apiName == api }
                ?: throw notFound("对象 [$api] 不存在")
        val fields = snapshot.fields.filter { it.objectId == obj.id }
        val dictIds = fields.mapNotNull { it.dictId }.toSet()
        val setIds = fields.mapNotNull { it.optionSetId }.toSet()
        val fieldIds = fields.map { it.id }.toSet()
        return MetadataGraph(
            objectMeta = obj,
            parent = obj.parentObjectId?.let { pid -> snapshot.objects.firstOrNull { it.id == pid } },
            fields = fields,
            dicts = snapshot.dicts.filter { it.id in dictIds },
            dictItems = snapshot.dictItems.filter { it.dictId in dictIds },
            optionSets = snapshot.optionSets.filter { it.id in setIds },
            options = snapshot.options.filter { it.setId in setIds },
            depOut = snapshot.deps.filter { it.targetFieldId in fieldIds },
            depIn = snapshot.deps.filter { it.sourceObjectId == obj.id },
        )
    }

    // ---------- mdWrite：事务包装 + 失效事件（§8-⑦ 唯一发布点） ----------

    private fun <T> mdWrite(
        action: MetadataAction,
        objectApi: String?,
        fn: () -> T,
    ): T =
        tx.execute {
            val result = fn()
            publisher.publishEvent(MetadataChangedEvent(action, objectApi))
            result
        } ?: throw KnownKteasyException(ApiError.INTERNAL, "md 写事务未返回结果")

    // ---------- 内部 ----------

    private fun buildField(
        objectId: String,
        cmd: FieldCmd,
        fields: List<MdField>,
    ): MdField {
        val type =
            runCatching { LogicalType.fromValue(cmd.logicalType) }.getOrElse {
                throw badRequest(
                    listOf("字段 [${cmd.apiName}] 逻辑类型 [${cmd.logicalType}] 非法"),
                )
            }
        val storage =
            cmd.storageKind?.let {
                runCatching { StorageKind.valueOf(it.uppercase()) }.getOrElse {
                    throw badRequest(listOf("字段 [${cmd.apiName}] storage_kind [$it] 非法（EXT/COLUMN/N2N）"))
                }
            } ?: type.storage
        val policy = parseWritePolicy(cmd.apiName, cmd.writePolicy)
        val scope = parseRequiredScope(cmd.apiName, cmd.requiredScope)
        val refObjectId =
            cmd.refObjectApi?.let { api ->
                repository.findObjectByApi(api)?.id
                    ?: throw badRequest(listOf("字段 [${cmd.apiName}] 引用目标对象 [$api] 不存在"))
            }
        val field =
            MdField(
                id = Ulid.next(),
                objectId = objectId,
                apiName = cmd.apiName,
                label = cmd.label,
                logicalType = type,
                storageKind = storage,
                required = cmd.required,
                defaultJson = cmd.defaultJson,
                validationJson = cmd.validationJson,
                uiJson = cmd.uiJson,
                refObjectId = refObjectId,
                refAnyObjsJson = cmd.refAnyObjsJson,
                dictId = cmd.dictId,
                optionSetId = cmd.optionSetId,
                seq = cmd.seq,
                writePolicy = policy,
                requiredScope = scope,
            )
        if (fields.any { it.id != field.id && it.apiName == field.apiName }) {
            throw badRequest(listOf("字段 [${field.apiName}] 在对象内重复"))
        }
        return field
    }

    private fun requireObject(api: String): MdObject = repository.findObjectByApi(api) ?: throw notFound("对象 [$api] 不存在")

    /**
     * 写策略档位解析（M1-06）：null＝缺省 [FieldWritePolicy.WRITABLE]。
     * 非法值折成 violations（与 logical_type/storage_kind 同口径），不让控制器自行猜枚举名。
     */
    private fun parseWritePolicy(
        fieldApi: String,
        raw: String?,
    ): FieldWritePolicy =
        raw?.let {
            runCatching { FieldWritePolicy.valueOf(it.uppercase()) }.getOrElse {
                throw badRequest(
                    listOf("字段 [$fieldApi] write_policy [$raw] 非法（WRITABLE/NO_CREATE/NO_UPDATE/READONLY/DERIVED）"),
                )
            }
        } ?: FieldWritePolicy.WRITABLE

    /** 必填作用域解析（M1-06）：null＝缺省 [RequiredScope.ALWAYS]；仅当 required=true 有实际效果。 */
    private fun parseRequiredScope(
        fieldApi: String,
        raw: String?,
    ): RequiredScope =
        raw?.let {
            runCatching { RequiredScope.valueOf(it.uppercase()) }.getOrElse {
                throw badRequest(listOf("字段 [$fieldApi] required_scope [$raw] 非法（ALWAYS/CREATE/UPDATE）"))
            }
        } ?: RequiredScope.ALWAYS

    private fun toJsonArray(items: List<String>): String? = if (items.isEmpty()) null else items.joinToString(",", "[", "]") { "\"${it}\"" }

    private fun badRequest(violations: List<String>) = KnownKteasyException(ApiError.BUSINESS_RULE, violations.first(), mapOf("violations" to violations))

    private fun notFound(message: String) = KnownKteasyException(ApiError.NOT_FOUND, message)

    private fun throwOnViolations(violations: List<String>) {
        if (violations.isNotEmpty()) throw badRequest(violations)
    }
}

/** 缓存快照：一次单飞加载的全量 md 区图谱原料（对象→字段→字典树→选项集）。 */
data class MetadataSnapshot(
    val objects: List<MdObject>,
    val fields: List<MdField>,
    val dicts: List<cn.x.ac.kteasy.core.meta.MdDict>,
    val dictItems: List<cn.x.ac.kteasy.core.meta.MdDictItem>,
    val optionSets: List<cn.x.ac.kteasy.core.meta.MdOptionSet>,
    val options: List<cn.x.ac.kteasy.core.meta.MdOption>,
    val deps: List<MdDep> = emptyList(),
)
