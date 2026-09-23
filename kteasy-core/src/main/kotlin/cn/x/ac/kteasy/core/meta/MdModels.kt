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

// md 区元数据模型（内存形态；列集对齐模块图纸 01 §1，JSON 列在此层保持原始 JSON 串，
// 结构化解析只发生在消费方——避免内核层引入 JSON 依赖，core 保持零第三方依赖）。
// 命名为 ⟨可逆⟩ 内部细节：Md 前缀 = Metadata 区域名（PG schema `md` / MySQL 前缀 `md_`）。

/**
 * 对象（业务实体）。`table_name` 不落库——物理表名由 api_name 经命名空间映射生成（图纸 01 §1）。
 *
 * @property displayName 显示名称模板（占位符串，如 `{no}-{name}`；单字段即"仅一个占位符"的特例，
 *   【清单】S10 由旧 `name_field_id` 单字段方案升级）。渲染（物化真列 vs 查询期现算）归 M1-05；
 *   本卡只落数据模型 + 占位符校验。
 * @property quickSearchJson 快查字段 api_name 数组的原始 JSON 串（`["name","phone"]`）
 * @property status 生命周期状态（ACTIVE/ARCHIVED，⟨可逆⟩；物理表处置归 M1-03）
 */
data class MdObject(
    val id: String,
    val apiName: String,
    val label: String,
    val kind: ObjectKind,
    val parentObjectId: String? = null,
    val displayName: String? = null,
    val quickSearchJson: String? = null,
    val status: String = "ACTIVE",
    val disabled: Boolean = false,
    val createdBy: String = "",
    val updatedBy: String = "",
)

/**
 * 字段。`storage_kind` 是唯一真列判据（图纸 01 §2）；类型语义在元数据 + 校验层（【规格】§4-3）。
 *
 * @property defaultJson 默认值定义的原始 JSON 串（结构归 M1-04 类型注册表）
 * @property validationJson 校验规则的原始 JSON 串
 * @property uiJson 控件/展示提示的原始 JSON 串
 * @property refObjectId 引用目标对象 id（REF/N2N 必填）
 * @property refAnyObjsJson ANYREF 允许对象 api_name 数组的原始 JSON 串
 * @property dictId 分类字典 id（DICT 必填）
 * @property optionSetId 选项集 id（PICKLIST/MULTISELECT/TAGS 必填）
 * @property writePolicy 写策略档位（M1-06：禁新建/禁修改/元数据只读/自动化下发位）
 * @property requiredScope 必填作用域（ALWAYS/CREATE/UPDATE），与 required 正交组合
 */
data class MdField(
    val id: String,
    val objectId: String,
    val apiName: String,
    val label: String,
    val logicalType: LogicalType,
    val storageKind: StorageKind,
    val required: Boolean = false,
    val defaultJson: String? = null,
    val validationJson: String? = null,
    val uiJson: String? = null,
    val refObjectId: String? = null,
    val refAnyObjsJson: String? = null,
    val dictId: String? = null,
    val optionSetId: String? = null,
    val seq: Int = 0,
    val enabled: Boolean = true,
    /**
     * 写策略（M1-06 服务端硬只读的元数据位；缺省 [FieldWritePolicy.WRITABLE]）。
     * 系统列的只读不在此表达——由写管道对 [SystemColumns] 恒定强制。
     */
    val writePolicy: FieldWritePolicy = FieldWritePolicy.WRITABLE,
    /** 必填作用域，仅当 [required]=true 时有意义（M1-06 阶段 4「required 三态」）。 */
    val requiredScope: RequiredScope = RequiredScope.ALWAYS,
)

/** 多级层级字典（树：[MdDictItem.path] 物化路径 `001/002`，层级 ≤4 ⟨可逆⟩）。 */
data class MdDict(
    val id: String,
    val name: String,
)

/** 字典树节点：path 为自根物化路径，parent_id 为父节点 id（根为 null）。 */
data class MdDictItem(
    val id: String,
    val dictId: String,
    val parentId: String?,
    val path: String,
    val label: String,
    val seq: Int = 0,
    val enabled: Boolean = true,
)

/** 选项集（下拉/多选/标签候选池）。closed=true 表示候选封闭（新增须走治理面）。 */
data class MdOptionSet(
    val id: String,
    val name: String,
    val closed: Boolean = false,
)

/** 选项集候选项。 */
data class MdOption(
    val id: String,
    val setId: String,
    val code: String,
    val label: String,
    val seq: Int = 0,
    val enabled: Boolean = true,
)

/**
 * 对象图谱（治理面 `GET /api/md/object/{api}/graph` 的载荷，图纸 01 §3）。
 * deps.out/in 恒空列表——recalc 依赖表 md_dep 归 M1-07 建表后填充（图纸已回写）。
 */
data class MetadataGraph(
    val objectMeta: MdObject,
    val parent: MdObject?,
    val fields: List<MdField>,
    val dicts: List<MdDict>,
    val dictItems: List<MdDictItem>,
    val optionSets: List<MdOptionSet>,
    val options: List<MdOption>,
    val depOut: List<Any> = emptyList(),
    val depIn: List<Any> = emptyList(),
)
