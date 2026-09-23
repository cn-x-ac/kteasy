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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 保存校验矩阵 L1 表驱动单测（模块图纸 01 §3 校验链逐条 + 反例）。 */
class MetadataValidatorTest {
    // ---------- 类型×存储矩阵（27 值全枚举） ----------

    @Test
    fun `存储矩阵 - 每个逻辑类型与其声明存储完全一致时合法`() {
        LogicalType.entries.forEach { t ->
            assertEquals(null, MetadataValidator.checkStorageMatrix(t, t.storage), "${t.name} 应允许 ${t.storage}")
        }
    }

    @Test
    fun `存储矩阵 - 26 型计数与三段存储归属`() {
        assertEquals(27, LogicalType.entries.size, "26 型 + SYSTEM")
        assertEquals(22, LogicalType.entries.count { it.storage == StorageKind.EXT })
        assertEquals(4, LogicalType.entries.count { it.storage == StorageKind.COLUMN })
        assertEquals(listOf(LogicalType.N2N), LogicalType.entries.filter { it.storage == StorageKind.N2N })
        // 白名单：只有关系/路径/系统列才可 COLUMN
        assertEquals(
            setOf("DICT", "REF", "ANYREF", "SYSTEM"),
            LogicalType.entries
                .filter { it.storage == StorageKind.COLUMN }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `存储矩阵 - 标量型冒充真列被拒`() {
        val msg = MetadataValidator.checkStorageMatrix(LogicalType.TEXT, StorageKind.COLUMN)
        assertTrue(msg!!.contains("只允许 EXT"), msg)
    }

    // ---------- api_name ----------

    @Test
    fun `api_name - 合法样本全过`() {
        listOf("account", "itm01_parent", "a1_2b").forEach {
            assertEquals(null, MetadataValidator.checkApiName(it), it)
        }
    }

    @Test
    fun `api_name - 非法样本全拒`() {
        listOf(
            "Account", // 大写
            "1abc", // 数字开头
            "_abc", // 下划线开头
            "ab", // 少于 3 位
            "a".repeat(49), // 超长
            "has-dash",
            "has space",
            "",
        ).forEach { assertTrue(MetadataValidator.checkApiName(it) != null, "应拒: '$it'") }
    }

    // ---------- 对象级 ----------

    private fun field(
        api: String,
        type: LogicalType,
        id: String = "f_$api",
        refObject: String? = null,
        dictId: String? = null,
        optionSetId: String? = null,
    ): MdField =
        MdField(
            id = id,
            objectId = "obj1",
            apiName = api,
            label = api,
            logicalType = type,
            storageKind = type.storage,
            refObjectId = refObject,
            dictId = dictId,
            optionSetId = optionSetId,
        )

    private fun obj(
        api: String = "account",
        kind: ObjectKind = ObjectKind.PARENT,
        parent: String? = null,
        displayName: String? = "{name}",
        quick: String? = null,
    ): MdObject =
        MdObject(
            id = "obj_$api",
            apiName = api,
            label = api,
            kind = kind,
            parentObjectId = parent,
            displayName = displayName,
            quickSearchJson = quick,
        )

    private val parentObj = obj()
    private val parentFields =
        listOf(
            field("name", LogicalType.TEXT, refObject = null),
            field("amount", LogicalType.NUMBER),
            field("cust", LogicalType.REF, refObject = "obj_x"),
        )

    @Test
    fun `合法主对象零违规`() {
        val o = obj(quick = "[\"name\",\"amount\"]")
        assertEquals(emptyList(), MetadataValidator.checkObject(o, parentFields, parent = null))
    }

    @Test
    fun `子项不挂主被拒且含人话定位`() {
        val child = obj(api = "itm01_line", kind = ObjectKind.CHILD)
        val v = MetadataValidator.checkObject(child, listOf(field("name", LogicalType.TEXT)), null)
        assertTrue(v.any { it.contains("必须挂主") && it.contains("itm01_line") }, v.toString())
    }

    @Test
    fun `挂到非主对象被拒`() {
        val child = obj(api = "itm01_line", kind = ObjectKind.CHILD, parent = "obj_plain")
        val plain = obj(api = "plain", kind = ObjectKind.PLAIN)
        val v = MetadataValidator.checkObject(child, listOf(field("name", LogicalType.TEXT)), plain)
        assertTrue(v.any { it.contains("不是主对象") }, v.toString())
    }

    @Test
    fun `主对象带 parent_object_id 被拒`() {
        val v = MetadataValidator.checkObject(obj(parent = "obj_x"), parentFields, null)
        assertTrue(v.any { it.contains("不允许挂主") }, v.toString())
    }

    @Test
    fun `显示名称模板 - 缺模板与占位符违规逐条命中`() {
        // 缺模板
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = ""), parentFields, null)
                .any { it.contains("缺少显示名称模板") },
        )
        // 占位符首跳指向不存在字段/列
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{ghost}"), parentFields, null)
                .any { it.contains("首跳 [ghost]") },
        )
        // 停用字段不可作片段
        val disabled = listOf(field("name", LogicalType.TEXT).copy(enabled = false), parentFields[1], parentFields[2])
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{name}"), disabled, null)
                .any { it.contains("非本对象启用字段或系统列") },
        )
        // 花括号不配平
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{name"), parentFields, null)
                .any { it.contains("花括号不配平") },
        )
    }

    @Test
    fun `显示名称模板 - 引用型可作片段 类型白名单已作废 多占位符合法`() {
        // 引用字段 cust 作片段不再被拒（S10：任何字段可作片段）
        assertEquals(emptyList(), MetadataValidator.checkObject(obj(displayName = "{cust}"), parentFields, null))
        // 多占位符 + 文本混排合法
        assertEquals(emptyList(), MetadataValidator.checkObject(obj(displayName = "{name}-{amount}"), parentFields, null))
        // 纯静态文本（无占位符）亦合法
        assertEquals(emptyList(), MetadataValidator.checkObject(obj(displayName = "客户"), parentFields, null))
    }

    @Test
    fun `显示名称模板 - 级联点链`() {
        // 经引用字段级联（首跳 cust=REF；深跳金额由 M1-05 点链解析，本卡不校验其存在性）
        assertEquals(emptyList(), MetadataValidator.checkObject(obj(displayName = "{cust.amount}"), parentFields, null))
        // 经引用型系统列级联：owner_dept → 目标对象字段（如部门负责人 {owner_dept.leader}）
        assertEquals(emptyList(), MetadataValidator.checkObject(obj(displayName = "{owner_dept.leader}"), parentFields, null))
        // 首跳是标量字段（name=TEXT）不可点下去
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{name.x}"), parentFields, null)
                .any { it.contains("不是引用/关联型") },
        )
        // 超过 3 跳（cust.a.b.c.d = 4 跳）被拒
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{cust.a.b.c.d}"), parentFields, null)
                .any { it.contains("级联超过 3 跳") },
        )
        // 空路径段
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{name..x}"), parentFields, null)
                .any { it.contains("空路径段") },
        )
        // 未知首跳（既非字段亦非系统列）
        assertTrue(
            MetadataValidator
                .checkObject(obj(displayName = "{ghost.x}"), parentFields, null)
                .any { it.contains("首跳 [ghost]") },
        )
    }

    @Test
    fun `快查字段指向不存在字段被拒`() {
        val v = MetadataValidator.checkObject(obj(quick = "[\"name\",\"ghost\"]"), parentFields, null)
        assertTrue(v.any { it.contains("快查字段 [ghost]") }, v.toString())
    }

    // ---------- 字段级 ----------

    @Test
    fun `引用完备性矩阵表驱动`() {
        fun violations(f: MdField) = MetadataValidator.checkField(f)

        val rows =
            listOf(
                // (字段, 期望命中的关键词; null=应零违规)
                Triple(field("ref1", LogicalType.REF), "ref_object_id", null),
                Triple(field("n2n1", LogicalType.N2N), "ref_object_id", null),
                Triple(field("dict1", LogicalType.DICT, dictId = "dict1"), null as String?, null),
                Triple(field("dict2", LogicalType.DICT), "dict_id", null),
                Triple(field("pick1", LogicalType.PICKLIST, optionSetId = "os1"), null as String?, null),
                Triple(field("multi1", LogicalType.MULTISELECT), "option_set_id", null),
                Triple(field("tags1", LogicalType.TAGS), "option_set_id", null),
                Triple(field("anyr1", LogicalType.ANYREF, refObject = "[]"), "ref_any_objs", null),
                Triple(field("sys1", LogicalType.SYSTEM), "SYSTEM", null),
                Triple(field("bool1", LogicalType.BOOL), null as String?, null),
            )
        rows.forEach { (f, keyword, _) ->
            val v = violations(f)
            if (keyword == null) {
                assertTrue(v.isEmpty(), "${f.apiName} 应零违规: $v")
            } else {
                assertTrue(v.any { it.contains(keyword) }, "${f.apiName} 应命中 $keyword: $v")
            }
        }
    }

    @Test
    fun `字段重复 api_name 被拒`() {
        val v = MetadataValidator.checkObject(obj(), parentFields + field("name", LogicalType.PHONE), null)
        assertTrue(v.any { it.contains("重复") && it.contains("name") }, v.toString())
    }

    /**
     * 写策略可达性（M1-06）：只读档位（元数据只读/自动化下发）+ 必填 + 无兜底 ＝ 任何来源都填不进却又必填，
     * 配置期就该拒；有默认值、自动编号、或压根不必填都放行。NO_CREATE/NO_UPDATE 不在此列——它们各留了一条可写路径。
     */
    @Test
    fun `写策略可达性 - 只读且必填且无兜底即拒`() {
        val deadEnd = field("code", LogicalType.TEXT).copy(required = true, writePolicy = FieldWritePolicy.READONLY)
        assertTrue(MetadataValidator.checkField(deadEnd).any { it.contains("填不进") }, "只读+必填+无默认应拒: $deadEnd")

        val withDefault = deadEnd.copy(defaultJson = "\"x\"")
        assertTrue(MetadataValidator.checkField(withDefault).isEmpty(), "有默认值应放行: ${MetadataValidator.checkField(withDefault)}")

        val autonum = field("autonum_no", LogicalType.AUTONUM).copy(required = true, writePolicy = FieldWritePolicy.DERIVED)
        assertTrue(MetadataValidator.checkField(autonum).isEmpty(), "自动编号由服务端派生，应放行: ${MetadataValidator.checkField(autonum)}")

        val optionalReadonly = field("readonly_note", LogicalType.TEXT).copy(writePolicy = FieldWritePolicy.READONLY)
        assertTrue(MetadataValidator.checkField(optionalReadonly).isEmpty())

        listOf(FieldWritePolicy.NO_CREATE, FieldWritePolicy.NO_UPDATE).forEach { p ->
            val f = field("t_${p.name.lowercase()}", LogicalType.TEXT).copy(required = true, writePolicy = p)
            assertTrue(MetadataValidator.checkField(f).isEmpty(), "$p 仍有一条可写路径，应放行")
        }

        // requiredScope 与 required 正交：required=false 时作用域只是惰性配置，不触发任何拒绝
        val scopeOnly = field("scope_only", LogicalType.TEXT).copy(requiredScope = RequiredScope.UPDATE)
        assertTrue(MetadataValidator.checkField(scopeOnly).isEmpty())
    }

    // ---------- parseStringArray ----------

    @Test
    fun `parseStringArray 容错解析`() {
        assertEquals(listOf("a", "b"), MetadataValidator.parseStringArray("[\"a\",\"b\"]"))
        assertEquals(emptyList(), MetadataValidator.parseStringArray("[]"))
        assertEquals(null, MetadataValidator.parseStringArray("{\"a\":1}"))
        assertEquals(null, MetadataValidator.parseStringArray(null))
        assertEquals(null, MetadataValidator.parseStringArray(""))
    }
}
