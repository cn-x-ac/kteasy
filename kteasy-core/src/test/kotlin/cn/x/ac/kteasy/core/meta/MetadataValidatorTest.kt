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
        nameField: String? = "f_name",
        quick: String? = null,
    ): MdObject =
        MdObject(
            id = "obj_$api",
            apiName = api,
            label = api,
            kind = kind,
            parentObjectId = parent,
            nameFieldId = nameField,
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
        val child = obj(api = "itm01_line", kind = ObjectKind.CHILD, nameField = "f_name")
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
    fun `名称字段缺失或资格不符被拒`() {
        // 指向不存在字段
        val v1 = MetadataValidator.checkObject(obj(nameField = "f_nope"), parentFields, null)
        assertTrue(v1.any { it.contains("缺少名称字段") }, v1.toString())
        // 资格不符：引用型不可主显
        val v2 = MetadataValidator.checkObject(obj(nameField = "f_cust"), parentFields, null)
        assertTrue(v2.any { it.contains("不能作为名称字段") && it.contains("引用") }, v2.toString())
        // 停用字段不可主显
        val disabled = listOf(field("name", LogicalType.TEXT).copy(enabled = false), parentFields[1], parentFields[2])
        val v3 = MetadataValidator.checkObject(obj(nameField = "f_name"), disabled, null)
        assertTrue(v3.any { it.contains("已停用") }, v3.toString())
    }

    @Test
    fun `名称字段资格白名单逐型断言`() {
        val allowed = MetadataValidator.NAME_FIELD_TYPES.map { it.name }.toSet()
        assertEquals(
            setOf("TEXT", "PHONE", "EMAIL", "URL", "AUTONUM", "NUMBER", "DECIMAL", "DATE", "DATETIME"),
            allowed,
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
