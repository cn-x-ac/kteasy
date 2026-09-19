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
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdDict
import cn.x.ac.kteasy.core.meta.MdDictItem
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.MdOption
import cn.x.ac.kteasy.core.meta.MdOptionSet
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * md 区元数据 DAO（V3 六表）。**全部参数化**（【规格】§8-④ 的 md 区延伸口径：
 * 引擎元数据读写也只准占位符，动态实体数据的 SQL 归 query/schema 两模块——M1-05/06 落地）。
 *
 * 表名的「PG schema 限定 vs MySQL 前缀」、JSON 列写入的「PG 需显式 jsonb 类型转换 vs MySQL 直接收字符串」
 * 两处方言差异，M1-02 起一律经注入的 [SchemaProvider]（[LogicalArea.METADATA] + [JsonOps.bindJson]），
 * M1-01 遗留的 `MdNamespace` 过渡债已收编删除——本类不出现任何 `if (isMySQL)`（红线⑤）。
 */
@Repository
class MetadataRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    provider: SchemaProvider,
) {
    private val namespace = provider.namespace
    private val json = provider.json

    /** 逻辑表名 → 该方言物理限定名（元数据区；方言差异只在 SchemaProvider 内）。 */
    private fun table(logical: String): String = namespace.qualified(LogicalArea.METADATA, logical)

    /** JSON 写入占位符（PG 显式 CAST、MySQL 直取）。 */
    private fun jsonPh(param: String): String = json.bindJson(param)

    // ---------- RowMapper ----------

    private val objectMapper =
        RowMapper { rs, _ ->
            MdObject(
                id = rs.getString("id"),
                apiName = rs.getString("api_name"),
                label = rs.getString("label"),
                kind = ObjectKind.valueOf(rs.getString("kind")),
                parentObjectId = rs.getString("parent_object_id"),
                nameFieldId = rs.getString("name_field_id"),
                quickSearchJson = rs.getString("quick_search_json"),
                status = rs.getString("status"),
                disabled = rs.getBoolean("disabled"),
                createdBy = rs.getString("created_by"),
                updatedBy = rs.getString("updated_by"),
            )
        }

    private val fieldMapper =
        RowMapper { rs, _ ->
            MdField(
                id = rs.getString("id"),
                objectId = rs.getString("object_id"),
                apiName = rs.getString("api_name"),
                label = rs.getString("label"),
                logicalType = LogicalType.fromValue(rs.getString("logical_type")),
                storageKind = StorageKind.valueOf(rs.getString("storage_kind")),
                required = rs.getBoolean("required"),
                defaultJson = rs.getString("default_json"),
                validationJson = rs.getString("validation_json"),
                uiJson = rs.getString("ui_json"),
                refObjectId = rs.getString("ref_object_id"),
                refAnyObjsJson = rs.getString("ref_any_objs_json"),
                dictId = rs.getString("dict_id"),
                optionSetId = rs.getString("option_set_id"),
                seq = rs.getInt("seq"),
                enabled = rs.getBoolean("enabled"),
            )
        }

    private fun <T> query(
        sql: String,
        params: Map<String, Any?>,
        mapper: RowMapper<T>,
    ): List<T> = jdbc.query(sql, MapSqlParameterSource(params), mapper)

    private fun <T> queryOne(
        sql: String,
        params: Map<String, Any?>,
        mapper: RowMapper<T>,
    ): T? = query(sql, params, mapper).firstOrNull()

    // ---------- md_object ----------

    fun insertObject(o: MdObject) {
        jdbc.update(
            """
            INSERT INTO ${table("md_object")}
                (id, api_name, label, kind, parent_object_id, name_field_id,
                 quick_search_json, status, disabled, created_by, updated_by)
            VALUES
                (:id, :api_name, :label, :kind, :parent_object_id, :name_field_id,
                 ${jsonPh("quick_search_json")}, :status, :disabled, :created_by, :updated_by)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", o.id)
                .addValue("api_name", o.apiName)
                .addValue("label", o.label)
                .addValue("kind", o.kind.name)
                .addValue("parent_object_id", o.parentObjectId)
                .addValue("name_field_id", o.nameFieldId)
                .addValue("quick_search_json", o.quickSearchJson)
                .addValue("status", o.status)
                .addValue("disabled", o.disabled)
                .addValue("created_by", o.createdBy)
                .addValue("updated_by", o.updatedBy),
        )
    }

    fun findObjectByApi(api: String): MdObject? =
        queryOne(
            "SELECT * FROM ${table("md_object")} WHERE api_name = :api",
            mapOf("api" to api),
            objectMapper,
        )

    fun findObjectById(id: String): MdObject? =
        queryOne(
            "SELECT * FROM ${table("md_object")} WHERE id = :id",
            mapOf("id" to id),
            objectMapper,
        )

    fun listObjects(): List<MdObject> = query("SELECT * FROM ${table("md_object")} ORDER BY created_at", emptyMap(), objectMapper)

    /** 对象核心列更新（api_name/kind/parent 不可变：变更＝删了重建，防图谱悬空）。 */
    fun updateObjectCore(
        id: String,
        label: String?,
        status: String?,
        disabled: Boolean?,
        nameFieldId: String?,
        quickSearchJson: String?,
        updatedBy: String,
    ) {
        jdbc.update(
            """
            UPDATE ${table("md_object")}
               SET label = COALESCE(:label, label),
                   status = COALESCE(:status, status),
                   disabled = COALESCE(:disabled, disabled),
                   name_field_id = COALESCE(:name_field_id, name_field_id),
                   quick_search_json = COALESCE(${jsonPh("quick_search_json")}, quick_search_json),
                   updated_by = :updated_by
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("label", label)
                .addValue("status", status)
                .addValue("disabled", disabled)
                .addValue("name_field_id", nameFieldId)
                .addValue("quick_search_json", quickSearchJson)
                .addValue("updated_by", updatedBy)
                .addValue("id", id),
        )
    }

    fun setObjectDisabled(
        id: String,
        disabled: Boolean,
        updatedBy: String,
    ) {
        jdbc.update(
            "UPDATE ${table("md_object")} SET disabled = :disabled, updated_by = :updated_by WHERE id = :id",
            mapOf("disabled" to disabled, "updated_by" to updatedBy, "id" to id),
        )
    }

    // ---------- md_field ----------

    fun insertField(f: MdField) {
        jdbc.update(
            """
            INSERT INTO ${table("md_field")}
                (id, object_id, api_name, label, logical_type, storage_kind, required,
                 default_json, validation_json, ui_json, ref_object_id, ref_any_objs_json,
                 dict_id, option_set_id, seq, enabled)
            VALUES
                (:id, :object_id, :api_name, :label, :logical_type, :storage_kind, :required,
                 ${jsonPh("default_json")}, ${jsonPh("validation_json")},
                 ${jsonPh("ui_json")}, :ref_object_id, ${jsonPh("ref_any_objs_json")},
                 :dict_id, :option_set_id, :seq, :enabled)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", f.id)
                .addValue("object_id", f.objectId)
                .addValue("api_name", f.apiName)
                .addValue("label", f.label)
                .addValue("logical_type", f.logicalType.name)
                .addValue("storage_kind", f.storageKind.name)
                .addValue("required", f.required)
                .addValue("default_json", f.defaultJson)
                .addValue("validation_json", f.validationJson)
                .addValue("ui_json", f.uiJson)
                .addValue("ref_object_id", f.refObjectId)
                .addValue("ref_any_objs_json", f.refAnyObjsJson)
                .addValue("dict_id", f.dictId)
                .addValue("option_set_id", f.optionSetId)
                .addValue("seq", f.seq)
                .addValue("enabled", f.enabled),
        )
    }

    fun listFieldsByObjectId(objectId: String): List<MdField> =
        query(
            "SELECT * FROM ${table("md_field")} WHERE object_id = :oid ORDER BY seq, created_at",
            mapOf("oid" to objectId),
            fieldMapper,
        )

    fun listAllFields(): List<MdField> = query("SELECT * FROM ${table("md_field")} ORDER BY object_id, seq", emptyMap(), fieldMapper)

    fun findFieldByApi(
        objectId: String,
        api: String,
    ): MdField? =
        queryOne(
            "SELECT * FROM ${table("md_field")} WHERE object_id = :oid AND api_name = :api",
            mapOf("oid" to objectId, "api" to api),
            fieldMapper,
        )

    fun findFieldById(id: String): MdField? =
        queryOne(
            "SELECT * FROM ${table("md_field")} WHERE id = :id",
            mapOf("id" to id),
            fieldMapper,
        )

    /** 字段可编辑列（逻辑类型/storage 不可变：转换走 M1-04 受控 type-convert）。 */
    fun updateFieldEditable(
        id: String,
        label: String?,
        required: Boolean?,
        defaultJson: String?,
        validationJson: String?,
        uiJson: String?,
        seq: Int?,
        enabled: Boolean?,
    ) {
        jdbc.update(
            """
            UPDATE ${table("md_field")}
               SET label = COALESCE(:label, label),
                   required = COALESCE(:required, required),
                   default_json = COALESCE(${jsonPh("default_json")}, default_json),
                   validation_json = COALESCE(${jsonPh("validation_json")}, validation_json),
                   ui_json = COALESCE(${jsonPh("ui_json")}, ui_json),
                   seq = COALESCE(:seq, seq),
                   enabled = COALESCE(:enabled, enabled)
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("label", label)
                .addValue("required", required)
                .addValue("default_json", defaultJson)
                .addValue("validation_json", validationJson)
                .addValue("ui_json", uiJson)
                .addValue("seq", seq)
                .addValue("enabled", enabled)
                .addValue("id", id),
        )
    }

    // ---------- 字典 / 选项集（快照全量加载 + 图谱引用） ----------

    fun listAllDicts(): List<MdDict> = query("SELECT id, name FROM ${table("md_dict")} ORDER BY created_at", emptyMap(), dictMapper)

    fun listAllDictItems(): List<MdDictItem> =
        query(
            "SELECT * FROM ${table("md_dict_item")} ORDER BY dict_id, path, seq",
            emptyMap(),
            dictItemMapper,
        )

    fun listAllOptionSets(): List<MdOptionSet> =
        query(
            "SELECT id, name, closed FROM ${table("md_option_set")} ORDER BY created_at",
            emptyMap(),
            optionSetMapper,
        )

    fun listAllOptions(): List<MdOption> = query("SELECT * FROM ${table("md_option")} ORDER BY set_id, seq", emptyMap(), optionMapper)

    fun findDictById(id: String): MdDict? =
        queryOne(
            "SELECT id, name FROM ${table("md_dict")} WHERE id = :id",
            mapOf("id" to id),
            dictMapper,
        )

    fun listDictsByIds(ids: Collection<String>): List<MdDict> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            query(
                "SELECT id, name FROM ${table("md_dict")} WHERE id IN (:ids)",
                mapOf("ids" to ids),
                dictMapper,
            )
        }

    fun listDictItemsByDictIds(ids: Collection<String>): List<MdDictItem> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            query(
                "SELECT * FROM ${table("md_dict_item")} WHERE dict_id IN (:ids) ORDER BY path, seq",
                mapOf("ids" to ids),
                dictItemMapper,
            )
        }

    fun findOptionSetById(id: String): MdOptionSet? =
        queryOne(
            "SELECT id, name, closed FROM ${table("md_option_set")} WHERE id = :id",
            mapOf("id" to id),
            optionSetMapper,
        )

    fun listOptionSetsByIds(ids: Collection<String>): List<MdOptionSet> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            query(
                "SELECT id, name, closed FROM ${table("md_option_set")} WHERE id IN (:ids)",
                mapOf("ids" to ids),
                optionSetMapper,
            )
        }

    fun listOptionsBySetIds(ids: Collection<String>): List<MdOption> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            query(
                "SELECT * FROM ${table("md_option")} WHERE set_id IN (:ids) ORDER BY seq",
                mapOf("ids" to ids),
                optionMapper,
            )
        }

    private val dictMapper =
        RowMapper { rs, _ -> MdDict(id = rs.getString("id"), name = rs.getString("name")) }

    private val dictItemMapper =
        RowMapper { rs, _ ->
            MdDictItem(
                id = rs.getString("id"),
                dictId = rs.getString("dict_id"),
                parentId = rs.getString("parent_id"),
                path = rs.getString("path"),
                label = rs.getString("p_label"),
                seq = rs.getInt("seq"),
                enabled = rs.getBoolean("enabled"),
            )
        }

    private val optionSetMapper =
        RowMapper { rs, _ ->
            MdOptionSet(id = rs.getString("id"), name = rs.getString("name"), closed = rs.getBoolean("closed"))
        }

    private val optionMapper =
        RowMapper { rs, _ ->
            MdOption(
                id = rs.getString("id"),
                setId = rs.getString("set_id"),
                code = rs.getString("code"),
                label = rs.getString("label"),
                seq = rs.getInt("seq"),
                enabled = rs.getBoolean("enabled"),
            )
        }

    // ---------- 唯一性预检（依赖 DB 唯一约束兜底） ----------

    fun assertObjectApiAvailable(api: String) {
        if (findObjectByApi(api) != null) {
            throw KnownKteasyException(
                ApiError.BUSINESS_RULE,
                "对象 api_name [$api] 已存在（对象名全局唯一）",
                mapOf("violations" to listOf("对象 api_name [$api] 已存在（对象名全局唯一）")),
            )
        }
    }
}
