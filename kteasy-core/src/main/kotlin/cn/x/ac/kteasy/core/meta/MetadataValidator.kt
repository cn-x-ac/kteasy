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
package cn.x.ac.kteasy.core.meta

/**
 * md 区保存校验链（模块图纸 01 §3：api_name 合法性 → 类型×存储矩阵 → 名称字段资格 →
 * 子项挂主 → 唯一性/引用完备性）。**纯函数、零依赖**：输入内存模型，输出人话违规清单
 * （带定位符），由服务层统一包装成 `420 BUSINESS_RULE`（`data.violations[]` 明细）。
 *
 * 逐条违规而非遇错即停——管理端一次暴露全部问题（表驱动单测锁矩阵）。
 */
object MetadataValidator {
    /** 对象/字段 api_name 合法性（图纸 01 §3）：小写开头 + 小写字母/数字/下划线，3~48 位。 */
    val API_NAME: Regex = Regex("^[a-z][a-z0-9_]{2,47}$")

    /**
     * 可为主显（名称字段）的类型资格（图纸 01 §3 落实项，⟨可逆⟩白名单，已随实况回写图纸）：
     * 取「字符串可渲染或可编号」的标量型——文本/电话/邮箱/链接/编号/整数/小数/日期/日期时间。
     * 不含富文本（TEXTAREA）、引用类与文件类。
     */
    val NAME_FIELD_TYPES: Set<LogicalType> =
        setOf(
            LogicalType.TEXT,
            LogicalType.PHONE,
            LogicalType.EMAIL,
            LogicalType.URL,
            LogicalType.AUTONUM,
            LogicalType.NUMBER,
            LogicalType.DECIMAL,
            LogicalType.DATE,
            LogicalType.DATETIME,
        )

    /** 字典层级上限（⟨可逆⟩，图纸 01 §1）。 */
    const val DICT_MAX_DEPTH: Int = 4

    /**
     * 对象级保存校验：[objectMeta] 为待存对象，[fields] 为其全部字段，
     * [parent] 为挂主对象（CHILD 必须非 null 且为主对象；其余必须为 null）。
     */
    fun checkObject(
        objectMeta: MdObject,
        fields: List<MdField>,
        parent: MdObject?,
    ): List<String> {
        val v = mutableListOf<String>()
        checkApiName(objectMeta.apiName)?.let { v += "对象 $it" }
        if (objectMeta.label.isBlank()) v += "对象 [${objectMeta.apiName}] 缺少显示名称（label）"

        when (objectMeta.kind) {
            ObjectKind.CHILD -> {
                if (objectMeta.parentObjectId.isNullOrBlank()) {
                    v += "对象 [${objectMeta.apiName}] 为子项对象，必须挂主（parent_object_id 缺失）"
                } else if (parent == null) {
                    v += "对象 [${objectMeta.apiName}] 挂主失败：主对象不存在（parent_object_id=${objectMeta.parentObjectId}）"
                } else if (parent.kind != ObjectKind.PARENT) {
                    v += "对象 [${objectMeta.apiName}] 挂主失败：[${parent.apiName}] 不是主对象（kind=${parent.kind}）"
                }
            }

            ObjectKind.PARENT, ObjectKind.PLAIN -> {
                if (!objectMeta.parentObjectId.isNullOrBlank()) {
                    v += "对象 [${objectMeta.apiName}] 为${if (objectMeta.kind == ObjectKind.PARENT) "主对象" else "独立对象"}，不允许挂主（parent_object_id 必须为空）"
                }
            }
        }

        if (fields.isEmpty()) v += "对象 [${objectMeta.apiName}] 至少需要一个字段"
        val nameField = fields.firstOrNull { it.id == objectMeta.nameFieldId }
        when {
            nameField == null -> {
                v += "对象 [${objectMeta.apiName}] 缺少名称字段（name_field 必须指向本对象已有字段）"
            }

            !nameField.enabled -> {
                v += "字段 [${nameField.apiName}] 已停用，不能作为名称字段"
            }

            nameField.logicalType !in NAME_FIELD_TYPES -> {
                v += "字段 [${nameField.apiName}] 类型为 ${nameField.logicalType.display}，不能作为名称字段（可选类型：${NAME_FIELD_TYPES.joinToString("/") { it.display }}）"
            }
        }

        // 快查字段必须指向本对象字段
        parseStringArray(objectMeta.quickSearchJson)?.let { quick ->
            val known = fields.map { it.apiName }.toSet()
            quick.filter { it !in known }.forEach {
                v += "对象 [${objectMeta.apiName}] 的快查字段 [$it] 不在本对象字段中"
            }
        }

        // 字段级：唯一性 + 单字段矩阵
        val seen = HashSet<String>()
        fields.forEach { f ->
            if (!seen.add(f.apiName)) v += "字段 [${f.apiName}] 在对象 [${objectMeta.apiName}] 内重复"
            v += checkField(f)
        }
        return v
    }

    /** 单字段校验（api_name/存储矩阵/引用完备性）；SYSTEM 型为引擎内部注入，治理面禁止直建。 */
    fun checkField(field: MdField): List<String> {
        val v = mutableListOf<String>()
        val tag = "字段 [${field.apiName}]"
        checkApiName(field.apiName)?.let { v += "对象内 $it" }
        if (field.label.isBlank()) v += "$tag 缺少显示名称（label）"

        if (field.logicalType == LogicalType.SYSTEM) {
            v += "$tag 为系统列类型（SYSTEM），只能由引擎注入，不能通过治理面创建"
            return v
        }
        checkStorageMatrix(field.logicalType, field.storageKind)?.let { v += "$tag $it" }

        when (field.logicalType) {
            LogicalType.REF, LogicalType.N2N -> {
                if (field.refObjectId.isNullOrBlank()) v += "$tag 引用类字段缺少目标对象（ref_object_id）"
            }

            LogicalType.ANYREF -> {
                if (parseStringArray(field.refAnyObjsJson).isNullOrEmpty()) {
                    v += "$tag 任意引用字段缺少允许对象清单（ref_any_objs）"
                }
            }

            LogicalType.DICT -> {
                if (field.dictId.isNullOrBlank()) v += "$tag 分类字段缺少字典（dict_id）"
            }

            LogicalType.PICKLIST, LogicalType.MULTISELECT, LogicalType.TAGS -> {
                if (field.optionSetId.isNullOrBlank()) v += "$tag 缺少选项集（option_set_id）"
            }

            else -> {
                Unit
            }
        }
        return v
    }

    /** 类型×存储合法矩阵：storage 必须与 [LogicalType.storage] 声明完全一致（白名单外一律拒绝）。 */
    fun checkStorageMatrix(
        logicalType: LogicalType,
        storageKind: StorageKind,
    ): String? =
        if (logicalType.storage != storageKind) {
            "逻辑类型 ${logicalType.name} 只允许 ${logicalType.storage} 存储（传入 $storageKind）"
        } else {
            null
        }

    /** api_name 非法时的违规文案；合法返回 null。 */
    fun checkApiName(apiName: String): String? =
        if (!API_NAME.matches(apiName)) {
            "api_name [$apiName] 非法：须匹配 ${API_NAME.pattern}（小写字母开头，3~48 位小写字母/数字/下划线）"
        } else {
            null
        }

    /**
     * 解析字符串数组的原始 JSON 串（`["a","b"]`）。刻意不引 JSON 库：
     * 该列只允许字符串数组，容错解析足以支撑校验；结构化消费归 M1-04/M1-05 的注册表与 EQL 层。
     * 非 JSON 数组形状返回 null（视为未填）。
     */
    fun parseStringArray(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return null
        val inner = s.substring(1, s.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').map { it.trim().trim('"', '\'') }
    }
}
