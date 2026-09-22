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
package cn.x.ac.kteasy.server.web

import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataGraphCache
import cn.x.ac.kteasy.server.md.MetadataService
import cn.x.ac.kteasy.server.md.MetadataService.CopyCmd
import cn.x.ac.kteasy.server.md.MetadataService.FieldCmd
import cn.x.ac.kteasy.server.md.MetadataService.FieldUpdateCmd
import cn.x.ac.kteasy.server.md.MetadataService.ObjectCreateCmd
import cn.x.ac.kteasy.server.md.MetadataService.ObjectUpdateCmd
import cn.x.ac.kteasy.server.schema.PhysicalizeService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * md 治理面 REST 层（契约总表 §2：`/api/md/` 前缀，M 态鉴权由 MdBootTokenFilter 承担）。
 *
 * 载荷刻意收 `Map<String, Any?>` 手工取值：绕开 Boot 4 换 Jackson 3 后的注解/命名策略不确定性，
 * 服务层命令对象字段自决（步骤卡 ⟨可逆⟩）。响应恒三键 `{error_code, error_msg, data}`，
 * 成功 error_code=0；图谱响应带缓存版本号头 `X-MD-Ver`（模块图纸 01 §3）。
 */
@RestController
@RequestMapping("/api/md")
class MdGovernanceController(
    private val service: MetadataService,
    private val cache: MetadataGraphCache,
    private val provider: SchemaProvider,
    private val context: KteasyContext,
    private val physicalize: PhysicalizeService,
) {
    // ---------- 对象 ----------

    @PostMapping("/object")
    fun createObject(
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val fields =
            (body["fields"] as? List<*>)?.map { f ->
                requireThat(f is Map<*, *>, "fields[] 元素必须是对象")
                @Suppress("UNCHECKED_CAST")
                toFieldCmd(f as Map<String, Any?>)
            } ?: emptyList()
        val cmd =
            ObjectCreateCmd(
                apiName = requireText(body, "api_name"),
                label = requireText(body, "label"),
                kind = requireText(body, "kind"),
                parentApi = optionalText(body, "parent_object"),
                displayName = requireText(body, "display_name"),
                quickSearchFields = optionalTextList(body, "quick_search_fields") ?: emptyList(),
                fields = fields,
            )
        return ok(service.createObject(cmd))
    }

    @GetMapping("/object")
    fun listObjects(): ResponseEntity<Map<String, Any?>> {
        val snapshot = cache.snapshot()
        return ok(mapOf("objects" to snapshot.objects, "total" to snapshot.objects.size))
    }

    @GetMapping("/object/{api}")
    fun getObject(
        @PathVariable api: String,
    ): ResponseEntity<Map<String, Any?>> {
        val graph = cache.graph(api).second
        return ok(mapOf("object" to graph.objectMeta, "fields" to graph.fields))
    }

    @PatchMapping("/object/{api}")
    fun updateObject(
        @PathVariable api: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> =
        ok(
            service.updateObject(
                api,
                ObjectUpdateCmd(
                    label = optionalText(body, "label"),
                    status = optionalText(body, "status"),
                    disabled = optionalBool(body, "disabled"),
                    quickSearchFields = optionalTextList(body, "quick_search_fields"),
                    displayName = optionalText(body, "display_name"),
                ),
            ),
        )

    @DeleteMapping("/object/{api}")
    fun disableObject(
        @PathVariable api: String,
    ): ResponseEntity<Map<String, Any?>> = ok(service.disableObject(api))

    @PostMapping("/object/{api}/copy")
    fun copyObject(
        @PathVariable api: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = ok(service.copyObject(api, CopyCmd(apiName = requireText(body, "api_name"), label = requireText(body, "label"))))

    @GetMapping("/object/{api}/graph")
    fun graph(
        @PathVariable api: String,
    ): ResponseEntity<Map<String, Any?>> {
        val (version, graph) = cache.graph(api)
        return ResponseEntity
            .ok()
            .header("X-MD-Ver", version.toString())
            .body(envelope(graph))
    }

    // ---------- 字段 ----------

    @PostMapping("/object/{api}/field")
    fun createField(
        @PathVariable api: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = ok(service.createField(api, toFieldCmd(body)))

    @PatchMapping("/field/{fieldId}")
    fun updateField(
        @PathVariable fieldId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> =
        ok(
            service.updateFieldById(
                fieldId,
                FieldUpdateCmd(
                    label = optionalText(body, "label"),
                    required = optionalBool(body, "required"),
                    defaultJson = optionalText(body, "default"),
                    validationJson = optionalText(body, "validation"),
                    uiJson = optionalText(body, "ui"),
                    seq = optionalInt(body, "seq"),
                    enabled = optionalBool(body, "enabled"),
                ),
            ),
        )

    @DeleteMapping("/field/{fieldId}")
    fun disableField(
        @PathVariable fieldId: String,
    ): ResponseEntity<Map<String, Any?>> = ok(service.disableFieldById(fieldId))

    /**
     * `POST /api/md/field/{fieldId}/type-convert`（M1-04 块 5）：把字段从 EXT 标量迁移为可写真列（物理化）。
     * 块 5 仅 `physicalize=true` 一种动作；非法边由服务层抛 `INVALID_PARAM`，经全局 handler 转三键契约体。
     */
    @PostMapping("/field/{fieldId}/type-convert")
    fun typeConvert(
        @PathVariable fieldId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        requireThat(optionalBool(body, "physicalize") ?: true, "块 5 type-convert 仅支持 physicalize=true（类型间转换归 M1-06）")
        val r = physicalize.physicalize(fieldId)
        return ok(
            linkedMapOf(
                "object_id" to r.objectId,
                "object_api" to r.objectApi,
                "field_id" to r.fieldId,
                "field_api" to r.fieldApi,
                "target_storage_kind" to r.targetStorageKind,
                "job_state" to r.jobState,
                "consequence" to r.consequence,
            ),
        )
    }

    // ---------- 版本 ----------

    @GetMapping("/version")
    fun version(): ResponseEntity<Map<String, Any?>> = ok(mapOf("version" to cache.currentVersion()))

    /** 方言能力台账（含 SUPPORTS/DEGRADED/ABSENT 与说明）：供 UI 如实显示「此后端下哪些功能弱化」（图纸 02 §2）。 */
    @GetMapping("/capabilities")
    fun capabilities(): ResponseEntity<Map<String, Any?>> {
        val ledger =
            provider.ledger().map {
                mapOf(
                    "capability" to it.capability.name,
                    "level" to it.level.name,
                    "note" to it.note,
                )
            }
        return ok(mapOf("dialect" to context.dialect.profile, "capabilities" to ledger))
    }

    // ---------- 内部 ----------

    private fun toFieldCmd(m: Map<String, Any?>): FieldCmd =
        FieldCmd(
            apiName = requireText(m, "api_name"),
            label = requireText(m, "label"),
            logicalType = requireText(m, "logical_type"),
            storageKind = optionalText(m, "storage_kind"),
            required = optionalBool(m, "required") ?: false,
            defaultJson = optionalText(m, "default"),
            validationJson = optionalText(m, "validation"),
            uiJson = optionalText(m, "ui"),
            refObjectApi = optionalText(m, "ref_object"),
            refAnyObjsJson = m["ref_any_objs"]?.let { toJsonString(it) },
            dictId = optionalText(m, "dict_id"),
            optionSetId = optionalText(m, "option_set_id"),
            seq = optionalInt(m, "seq") ?: 0,
        )

    /** 把载荷里的 ref_any_objs（字符串数组或原始 JSON 串）规范成 JSON 数组串。 */
    private fun toJsonString(v: Any?): String? =
        when (v) {
            null -> null
            is List<*> -> v.joinToString(",", "[", "]") { "\"${it}\"" }
            else -> v.toString()
        }

    private fun requireText(
        m: Map<String, Any?>,
        key: String,
    ): String =
        optionalText(m, key)
            ?: throw cn.x.ac.kteasy.core.kernel.KnownKteasyException(
                cn.x.ac.kteasy.core.kernel.ApiError.INVALID_PARAM,
                "缺少必填字段 [$key]",
            )

    private fun optionalText(
        m: Map<String, Any?>,
        key: String,
    ): String? = (m[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun optionalInt(
        m: Map<String, Any?>,
        key: String,
    ): Int? = (m[key] as? Number)?.toInt()

    private fun optionalBool(
        m: Map<String, Any?>,
        key: String,
    ): Boolean? = m[key] as? Boolean

    private fun optionalTextList(
        m: Map<String, Any?>,
        key: String,
    ): List<String>? = (m[key] as? List<*>)?.map { it.toString() }

    private fun requireThat(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) {
            throw cn.x.ac.kteasy.core.kernel.KnownKteasyException(
                cn.x.ac.kteasy.core.kernel.ApiError.INVALID_PARAM,
                message,
            )
        }
    }

    private fun ok(data: Any?): ResponseEntity<Map<String, Any?>> = ResponseEntity.ok(envelope(data))

    private fun envelope(data: Any?): Map<String, Any?> = linkedMapOf("error_code" to 0, "error_msg" to "ok", "data" to data)
}
