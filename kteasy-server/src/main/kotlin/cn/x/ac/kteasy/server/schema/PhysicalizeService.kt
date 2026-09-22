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
package cn.x.ac.kteasy.server.schema

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.meta.TypeRegistry
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.server.md.MetadataRepository
import org.springframework.stereotype.Service

/**
 * 步骤卡 M1-04 块 5 · 字段类型/存储迁移编排（契约总表 §2 `POST /api/md/field/{fieldId}/type-convert`）。
 *
 * 块 5 仅实现 **EXT 标量 → COLUMN 真列** 的物理化触发（S7 档二显式作业）：解析 field→宿主对象，校验迁移边合法
 * （`FieldType.physicalizable` 且当前 `storage_kind=EXT`），由 `TypeRegistry.cast` 供给权威类型、`SchemaDiff.planPhysicalize` 产出五步，
 * 交 [SchemaJobExecutor.submitPhysicalization] 入队续跑。读切换步在执行器回填完成后翻 `storage_kind`，本服务不改元数据。
 * 非法边抛 [KnownKteasyException]（`INVALID_PARAM`），控制器层转三键契约体。SQL/DDL 全圈在 schema 模块（红线④⑤）。
 */
@Service
class PhysicalizeService(
    private val meta: MetadataRepository,
    private val executor: SchemaJobExecutor,
) {
    data class PhysicalizeResult(
        val objectId: String,
        val objectApi: String,
        val fieldId: String,
        val fieldApi: String,
        val targetStorageKind: String,
        val jobState: String,
        val consequence: String,
    )

    fun physicalize(fieldId: String): PhysicalizeResult {
        val field = meta.findFieldById(fieldId) ?: throw KnownKteasyException(ApiError.NOT_FOUND, "字段 [$fieldId] 不存在")
        val obj = meta.findObjectById(field.objectId) ?: throw KnownKteasyException(ApiError.NOT_FOUND, "字段 [$fieldId] 宿主对象不存在")
        val ft = TypeRegistry.of(field.logicalType)
        if (field.storageKind != StorageKind.EXT) {
            throw KnownKteasyException(ApiError.INVALID_PARAM, "字段 [${field.apiName}] 存储态为 ${field.storageKind}，物理化仅作用于 EXT 标量")
        }
        if (!ft.physicalizable) {
            throw KnownKteasyException(ApiError.INVALID_PARAM, "字段类型 ${field.logicalType} 不可物理化（仅 TEXT/多行文本/电话/邮箱/链接/下拉/数字/小数/日期/日期时间/时间/布尔/位置）")
        }
        val column = SchemaDiff.physicalColumnOf(field) ?: throw KnownKteasyException(ApiError.INVALID_PARAM, "字段 [${field.apiName}] 无可用真列映射")
        val cast = ft.cast ?: throw KnownKteasyException(ApiError.INVALID_PARAM, "字段类型 ${field.logicalType} 缺 ValueCast")
        // makeIndex=false（D5 定稿）：块 5 闭环只需「EXT 标量→可写真列」，物理化后 EQL 直查真列，迁移期 ext 表达式索引非必需。
        // D7 实证（PG/MySQL 取证）：ADD_INDEX_EXPR 对 temporal cast 两库皆不可用——PG text→date/timestamp cast 非 IMMUTABLE（42P17，
        // bigint/numeric/text 可）；MySQL 对 JSON 列本身禁止函数索引（3756，与 LOCK 级别无关，1846 只是次生）。在线索引策略整体归 M1-05（虚拟列桥）。
        val steps = SchemaDiff.planPhysicalize(obj.id, obj.apiName, field.id, field.apiName, column, cast, makeIndex = false)
        executor.submitPhysicalization(obj.id, steps)
        return PhysicalizeResult(obj.id, obj.apiName, field.id, field.apiName, StorageKind.COLUMN.name, "PENDING", CONSEQUENCE)
    }

    companion object {
        const val CONSEQUENCE = "物理化迁移完成前该列可能不可读写；采用分批回填，不丢数据（作业可续跑、迁移中可锁）"
    }
}
