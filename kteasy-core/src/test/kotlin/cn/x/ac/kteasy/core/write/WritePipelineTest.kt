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

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.meta.FieldWritePolicy
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdDictItem
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.MdOption
import cn.x.ac.kteasy.core.meta.MetadataGraph
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.RequiredScope
import cn.x.ac.kteasy.core.meta.StorageKind
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 写入管道阶段 1–7 的裁决矩阵单测（步骤卡 M1-06 块 2）。
 *
 * 全部不连库：管道是纯函数，装配层只负责把既有行读成 [WriteRow]、把 [WritePlan] 绑成参数。
 * 卡面 6 条 GWT 里能纯函数化的三条（后门反例、diff 恰一项、未知键回显）在此先落一半，
 * 另一半（并发终值、事务内抛错无半行、软删可见性闭环）留在块 5 的双库 IT。
 */
class WritePipelineTest {
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 9, 23, 10, 30, 0, 0, ZoneOffset.UTC)

    private fun field(
        api: String,
        type: LogicalType,
        required: Boolean = false,
        scope: RequiredScope = RequiredScope.ALWAYS,
        policy: FieldWritePolicy = FieldWritePolicy.WRITABLE,
        enabled: Boolean = true,
        storage: StorageKind? = null,
        optionSetId: String? = null,
        dictId: String? = null,
        defaultJson: String? = null,
    ): MdField =
        MdField(
            id = "F_$api",
            objectId = "OBJ1",
            apiName = api,
            label = api,
            logicalType = type,
            storageKind = storage ?: type.storage,
            required = required,
            requiredScope = scope,
            writePolicy = policy,
            enabled = enabled,
            optionSetId = optionSetId,
            dictId = dictId,
            defaultJson = defaultJson,
        )

    private fun graphOf(
        vararg fields: MdField,
        quick: String? = null,
        disabled: Boolean = false,
    ): MetadataGraph =
        MetadataGraph(
            objectMeta =
                MdObject(
                    id = "OBJ1",
                    apiName = "account",
                    label = "客户",
                    kind = ObjectKind.PARENT,
                    quickSearchJson = quick,
                    disabled = disabled,
                ),
            parent = null,
            fields = fields.toList(),
            dicts = emptyList(),
            dictItems = listOf(MdDictItem(id = "D1", dictId = "DT1", parentId = null, path = "001", label = "华东")),
            optionSets = emptyList(),
            options = listOf(MdOption(id = "O1", setId = "OS1", code = "hot", label = "重点"), MdOption(id = "O2", setId = "OS1", code = "normal", label = "普通")),
        )

    private fun ctxOf(
        intent: WriteIntent = WriteIntent.UPSERT,
        recordId: String? = null,
        expectedVersion: Long? = null,
        source: WriteSource = WriteSource.UI,
    ) = WriteContext("account", intent, source, WriteActor("u1", "d1"), "tr-1", now, recordId, expectedVersion)

    private fun plan(
        input: WriteInput,
        pipeline: WritePipeline = WritePipeline(newId = { "NEWID000000000000000000" }),
    ): WritePlan = pipeline.plan(input)

    private fun rejected(
        input: WriteInput,
        pipeline: WritePipeline = WritePipeline(newId = { "NEWID000000000000000000" }),
    ): KnownKteasyException = assertFailsWith(KnownKteasyException::class) { pipeline.plan(input) }

    // ---------- 阶段 1 定位 ----------

    @Test
    fun `无 id 即新建 有 id 命中即更新 打空即 404`() {
        val g = graphOf(field("name", LogicalType.TEXT))
        val created = plan(WriteInput(g, ctxOf(), RecordDraft.of("name" to DraftValue.Text("甲"))))
        assertEquals(WriteKind.CREATED, created.kind)
        assertTrue(created.creating)
        assertEquals("NEWID000000000000000000", created.id)

        val existing = WriteRow("R1", rowVersion = 4, values = mapOf("name" to DraftValue.Text("甲")))
        val updated = plan(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft.of("name" to DraftValue.Text("乙")), existing))
        assertEquals(WriteKind.UPDATED, updated.kind)
        assertEquals(4L, updated.expectedVersion)
        assertEquals(5L, updated.rowVersionNext)

        val ex404 =
            assertFailsWith(KnownKteasyException::class) {
                plan(WriteInput(g, ctxOf(recordId = "R9"), RecordDraft.of("name" to DraftValue.Text("乙")), null))
            }
        assertEquals(ApiError.NOT_FOUND, ex404.apiError)
        assertEquals(WriteErrors.ID_NOT_FOUND, (ex404.data as Map<*, *>)["error_id"])
    }

    @Test
    fun `软删与恢复按意图定性质 重复删不报错`() {
        val g = graphOf(field("name", LogicalType.TEXT))
        val live = WriteRow("R1", 1)
        val tomb = WriteRow("R1", 2, deleted = true)
        assertEquals(WriteKind.DELETED, plan(WriteInput(g, ctxOf(WriteIntent.DELETE, "R1"), RecordDraft(), live)).kind)
        assertEquals(WriteKind.DELETED, plan(WriteInput(g, ctxOf(WriteIntent.DELETE, "R1"), RecordDraft(), tomb)).kind, "已删再删＝幂等无变化")
        assertEquals(WriteKind.RESTORED, plan(WriteInput(g, ctxOf(WriteIntent.RESTORE, "R1"), RecordDraft(), tomb)).kind)
        assertNull(plan(WriteInput(g, ctxOf(WriteIntent.RESTORE, "R1"), RecordDraft(), tomb)).columnBindings["deleted_at"], "恢复＝清 deleted_at")
        assertEquals(java.time.LocalDateTime.of(2026, 9, 23, 10, 30, 0), plan(WriteInput(g, ctxOf(WriteIntent.DELETE, "R1"), RecordDraft(), live)).columnBindings["deleted_at"], "删除时刻与 updated_at 同一 UTC 墙钟、同一绑定类型")
    }

    @Test
    fun `停用对象不接受写入`() {
        val ex = rejected(WriteInput(graphOf(field("name", LogicalType.TEXT), disabled = true), ctxOf(), RecordDraft.of("name" to DraftValue.Text("x"))))
        assertEquals(WriteErrors.ID_OBJECT_DISABLED, (ex.data as Map<*, *>)["error_id"])
    }

    // ---------- 阶段 4 只读四来源（后门反例） ----------

    @Test
    fun `OPENAPI 写系统列与禁改字段被拒 且只读类走 420`() {
        val g =
            graphOf(
                field("name", LogicalType.TEXT),
                field("code", LogicalType.TEXT, policy = FieldWritePolicy.NO_UPDATE),
            )
        val ex =
            rejected(
                WriteInput(
                    g,
                    ctxOf(source = WriteSource.OPENAPI, recordId = "R1", expectedVersion = 1),
                    RecordDraft.of("owner_user" to DraftValue.Text("someone"), "updated_by" to DraftValue.Text("hacker"), "code" to DraftValue.Text("new")),
                    WriteRow("R1", 1, values = mapOf("code" to DraftValue.Text("old"))),
                ),
            )
        val fields = (ex.data as Map<*, *>)["fields"] as List<*>
        assertEquals(listOf("owner_user", "updated_by", "code"), fields.map { (it as Map<*, *>)["field"] })
        assertTrue(fields.all { (it as Map<*, *>)["error_id"] == WriteErrors.ID_SYSTEM_COLUMN_READONLY || (it as Map<*, *>)["error_id"] == WriteErrors.ID_FIELD_READONLY })
        assertEquals(ApiError.BUSINESS_RULE, ex.apiError, "只读类＝需改现场→420（P2 判据）")
    }

    @Test
    fun `未注册键回显键名且与格式类同走 410`() {
        val ex =
            rejected(
                WriteInput(
                    graphOf(field("name", LogicalType.TEXT)),
                    ctxOf(),
                    RecordDraft.of("nickname2" to DraftValue.Text("x"), "phone" to DraftValue.Text("123")),
                ),
            )
        assertEquals(ApiError.INVALID_PARAM, ex.apiError)
        val fields = (ex.data as Map<*, *>)["fields"] as List<*>
        assertEquals("nickname2", fields.map { (it as Map<*, *>)["field"] }.first())
        assertEquals(WriteErrors.ID_EXT_UNKNOWN_KEY, (fields[0] as Map<*, *>)["error_id"])
    }

    @Test
    fun `SYSTEM 来源同样没有豁免（P3 全来源硬拒）`() {
        listOf(WriteSource.SYSTEM, WriteSource.IMPORT, WriteSource.TRANSFORM, WriteSource.OPENAPI, WriteSource.UI).forEach { src ->
            val ex = rejected(WriteInput(graphOf(field("name", LogicalType.TEXT)), ctxOf(source = src), RecordDraft.of("ghost" to DraftValue.Text("x"))))
            assertEquals(WriteErrors.ID_EXT_UNKNOWN_KEY, (ex.data as Map<*, *>)["error_id"], "$src 不得成为旁路")
        }
    }

    @Test
    fun `停用字段与多引用字段分别给出可诊断的符号名`() {
        val g = graphOf(field("legacy", LogicalType.TEXT, enabled = false), field("links", LogicalType.N2N))
        val e1 = rejected(WriteInput(g, ctxOf(), RecordDraft.of("legacy" to DraftValue.Text("x"))))
        assertEquals(WriteErrors.ID_FIELD_DISABLED, ((e1.data as Map<*, *>)["fields"] as List<*>).let { (it[0] as Map<*, *>)["error_id"] })
        val e2 = rejected(WriteInput(g, ctxOf(), RecordDraft.of("links" to DraftValue.Many(listOf("R1")))))
        assertEquals(WriteErrors.ID_FIELD_READONLY, ((e2.data as Map<*, *>)["fields"] as List<*>).let { (it[0] as Map<*, *>)["error_id"] })
        assertTrue(e2.message!!.contains("M1-07"), "多引用留桩要在人话里点名归属，避免被当成 bug")
    }

    @Test
    fun `NO_UPDATE 同值提交即不触碰 改值才拒`() {
        val g = graphOf(field("code", LogicalType.TEXT, policy = FieldWritePolicy.NO_UPDATE), field("name", LogicalType.TEXT))
        val row = WriteRow("R1", 3, values = mapOf("code" to DraftValue.Text("C-1"), "name" to DraftValue.Text("甲")))
        val same = plan(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft.of("code" to DraftValue.Text("C-1"), "name" to DraftValue.Text("乙")), row))
        assertEquals(setOf("name"), same.diff.keys, "整单回传不可改字段不该报错、也不该进 diff")
        // ext 是整列覆盖写，所以未触碰的 code 旧值仍须原样带回去（缺了它就会被抹成 null）
        assertEquals(DraftValue.Text("C-1"), same.extValues["code"])
        val changed = rejected(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft.of("code" to DraftValue.Text("C-2")), row))
        assertEquals(ApiError.BUSINESS_RULE, changed.apiError)
    }

    @Test
    fun `元数据只读档位由服务端派生填值 调用方填则拒`() {
        val g = graphOf(field("name", LogicalType.TEXT), field("source", LogicalType.PICKLIST, optionSetId = "OS1", policy = FieldWritePolicy.READONLY, defaultJson = "\"hot\""))
        val p = plan(WriteInput(g, ctxOf(), RecordDraft.of("name" to DraftValue.Text("x"))))
        assertEquals(DraftValue.Text("hot"), p.extValues["source"], "READONLY 字段的值来自阶段 5 派生")
        val ex = rejected(WriteInput(g, ctxOf(), RecordDraft.of("source" to DraftValue.Text("normal"))))
        assertEquals(WriteErrors.ID_FIELD_READONLY, ((ex.data as Map<*, *>)["fields"] as List<*>).let { (it[0] as Map<*, *>)["error_id"] })
    }

    // ---------- 阶段 3 类型与域 ----------

    @Test
    fun `形状不符与格式不符都归 FIELD_TYPE`() {
        val g = graphOf(field("phone", LogicalType.PHONE), field("tags", LogicalType.TAGS), field("vip", LogicalType.BOOL))
        val ex = rejected(WriteInput(g, ctxOf(), RecordDraft.of("phone" to DraftValue.Text("abc"), "tags" to DraftValue.Text("single-ok"), "vip" to DraftValue.Number("1"))))
        val ids = ((ex.data as Map<*, *>)["fields"] as List<*>).map { (it as Map<*, *>).let { m -> "${m["field"]}:${m["error_id"]}" } }
        assertTrue("phone:FIELD_TYPE" in ids, ids.toString())
        assertTrue("vip:FIELD_TYPE" in ids, "布尔不接受裸数字形状：$ids")
    }

    @Test
    fun `候选域校验只对封闭选项集与字典生效`() {
        val g =
            graphOf(
                field("stage", LogicalType.PICKLIST, optionSetId = "OS1"),
                field("area", LogicalType.DICT, dictId = "DT1"),
                field("tags", LogicalType.TAGS),
            )
        val ok =
            plan(
                WriteInput(
                    g,
                    ctxOf(),
                    RecordDraft.of(
                        "stage" to DraftValue.Text("hot"),
                        "area" to DraftValue.Text("001"),
                        "tags" to DraftValue.Many(listOf("自由标签", "另一个")),
                    ),
                ),
            )
        assertEquals(DraftValue.Text("hot"), ok.extValues["stage"])
        assertEquals<Any?>(("001"), ok.columnBindings["area"], "分类走真列（左前缀 LIKE 可查）")
        assertEquals(2, (ok.extValues["tags"] as DraftValue.Many).items.size, "标签可选可输，不做域校验")

        val bad = rejected(WriteInput(g, ctxOf(), RecordDraft.of("stage" to DraftValue.Text("ghost"), "area" to DraftValue.Text("999"))))
        val ids = ((bad.data as Map<*, *>)["fields"] as List<*>).map { (it as Map<*, *>)["error_id"] }.toSet()
        assertEquals(setOf(WriteErrors.ID_OPTION_DOMAIN), ids)
    }

    // ---------- 必填三态 ----------

    @Test
    fun `必填看作用域 更新时库里已有值即满足`() {
        val g =
            graphOf(
                field("name", LogicalType.TEXT, required = true),
                field("no", LogicalType.TEXT, required = true, scope = RequiredScope.CREATE),
            )
        val missing = rejected(WriteInput(g, ctxOf(), RecordDraft.of("no" to DraftValue.Text("N-1"))))
        assertEquals(WriteErrors.ID_FIELD_REQUIRED, ((missing.data as Map<*, *>)["fields"] as List<*>).let { (it[0] as Map<*, *>)["error_id"] })
        assertEquals(ApiError.INVALID_PARAM, missing.apiError, "漏填＝改载荷可自救→410（P2 判据）")

        val row = WriteRow("R1", 1, values = mapOf("name" to DraftValue.Text("已有"), "no" to DraftValue.Text("N-1")))
        val patch = plan(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft.of("name" to DraftValue.Text("改名")), row))
        assertEquals(setOf("name"), patch.diff.keys, "PATCH 单字段不得被其他必填字段拦下")
    }

    @Test
    fun `派生填上的值算满足必填`() {
        val g = graphOf(field("stage", LogicalType.PICKLIST, required = true, optionSetId = "OS1", defaultJson = "\"normal\""))
        val p = plan(WriteInput(g, ctxOf(), RecordDraft()))
        assertEquals(DraftValue.Text("normal"), p.extValues["stage"])
        assertEquals(setOf("stage"), p.diff.keys, "新建时派生出的值也要进 diff（审计的地基）")
    }

    // ---------- 阶段 5/6/7 与存储切分 ----------

    @Test
    fun `系统列恒由服务端填 时间形状与查询侧同轴`() {
        val p = plan(WriteInput(graphOf(field("name", LogicalType.TEXT)), ctxOf(), RecordDraft.of("name" to DraftValue.Text("甲"))))
        val c = p.columnBindings
        assertEquals("u1", c["owner_user"])
        assertEquals("d1", c["owner_dept"])
        assertEquals("u1", c["created_by"])
        assertEquals("u1", c["updated_by"])
        assertEquals("DRAFT", c["approval_state"])
        assertEquals(1L, c["row_version"])
        assertEquals(java.time.LocalDateTime.of(2026, 9, 23, 10, 30, 0), c["updated_at"], "写入侧时间列绑 java.time（PG 赋值位不吃字符串）")
        assertEquals(c["updated_at"], c["created_at"])
    }

    /**
     * 存储归属切分（S7 档一/档二的落点）：**标量默认全部进 ext**，只有被物化成真列（或关系/分类型）
     * 才进 `columnBindings`。测试把两种形态并排放在一起，是为了让下一位读者不会以为
     * 「小数天生在列上」——那种误解会让他去 ALTER 业务表。
     */
    @Test
    fun `ext 标量与真列按 storage_kind 切分 物化列按类型落值`() {
        val g =
            graphOf(
                field("name", LogicalType.TEXT),
                field("amount", LogicalType.DECIMAL),
                field("cnt", LogicalType.NUMBER),
                field("vip", LogicalType.BOOL),
                field("amount_col", LogicalType.DECIMAL, storage = StorageKind.COLUMN),
                field("ref", LogicalType.REF),
            )
        val p =
            plan(
                WriteInput(
                    g,
                    ctxOf(),
                    RecordDraft.of(
                        "name" to DraftValue.Text("  甲  "),
                        "amount" to DraftValue.Number("12.50"),
                        "cnt" to DraftValue.Number("7"),
                        "vip" to DraftValue.Bool(true),
                        "amount_col" to DraftValue.Number("12.50"),
                        "ref" to DraftValue.Text("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                    ),
                ),
            )
        assertEquals(DraftValue.Text("甲"), p.extValues["name"], "阶段 6 净化（trim）后入库")
        assertEquals(DraftValue.Number("12.50"), p.extValues["amount"], "未物化的标量留在 ext，由 EQL 按 cast 查询")
        assertEquals(DraftValue.Number("7"), p.extValues["cnt"])
        assertEquals(DraftValue.Bool(true), p.extValues["vip"])
        assertEquals<Any?>(BigDecimal("12.50"), p.columnBindings["amount_col"], "物化列以 BigDecimal 承载，不经 Double 往返")
        assertEquals<Any?>("01ARZ3NDEKTSV4RRFFQ69G5FAV", p.columnBindings["ref"], "引用走真列（享受 FK）")
        assertFalse("ref" in p.extValues)
    }

    @Test
    fun `更新 20 字段只变 1 个时 diff 恰一项`() {
        val many = (1..20).map { field("f%02d".format(it), LogicalType.TEXT) }
        val g = graphOf(*many.toTypedArray())
        val row = WriteRow("R1", 9, values = many.associate { it.apiName to (DraftValue.Text("v${it.apiName}")) })
        val draft = RecordDraft(many.map { it.apiName to (if (it.apiName == "f07") DraftValue.Text("NEW") else DraftValue.Text("v${it.apiName}")) }.toMap())
        val p = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 9), draft, row))
        assertEquals(mapOf("f07" to FieldDiff("f07", DraftValue.Text("vf07"), DraftValue.Text("NEW"))), p.diff)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `不带版本的更新出告警 带了则按带的做条件更新`() {
        val g = graphOf(field("name", LogicalType.TEXT))
        val row = WriteRow("R1", 8, values = mapOf("name" to DraftValue.Text("旧")))
        val blind = plan(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft.of("name" to DraftValue.Text("新")), row))
        assertEquals(8L, blind.expectedVersion)
        assertEquals(listOf(WriteWarnings.OVERRIDE_WITHOUT_VERSION), blind.warnings.map { it.code })
        val guarded = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 8), RecordDraft.of("name" to DraftValue.Text("新")), row))
        assertEquals(8L, guarded.expectedVersion)
        assertTrue(guarded.warnings.isEmpty())
        val stale = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 3), RecordDraft.of("name" to DraftValue.Text("新")), row))
        assertEquals(3L, stale.expectedVersion, "过期版本交给块 3 的 WHERE 条件判定，纯函数不猜")
    }

    @Test
    fun `清空字段进 ext 图并在 diff 记旧值到空`() {
        val g = graphOf(field("memo", LogicalType.TEXTAREA))
        val row = WriteRow("R1", 2, values = mapOf("memo" to DraftValue.Text("旧备注")))
        val p = plan(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft.of("memo" to DraftValue.Cleared), row))
        assertEquals(DraftValue.Cleared, p.extValues["memo"], "清空必须进 ext 图（编码时删键），不落 JSON null")
        assertEquals(FieldDiff("memo", DraftValue.Text("旧备注"), null), p.diff["memo"])
    }

    @Test
    fun `检索码伴生列随快查字段派生`() {
        val g = graphOf(field("name", LogicalType.TEXT), quick = """["name"]""")
        val pipeline = WritePipeline(newId = { "X".repeat(26) }, searchCode = { s -> "zx:" + s })
        val p = pipeline.plan(WriteInput(g, ctxOf(), RecordDraft.of("name" to DraftValue.Text("张三"))))
        assertEquals<Any?>(("zx:张三"), p.columnBindings["name_pinyin"], "快查字段的拼音伴生列要与值同批写入")
        val untouched = pipeline.plan(WriteInput(g, ctxOf(recordId = "R1"), RecordDraft(), WriteRow("R1", 1, values = mapOf("name" to DraftValue.Text("张三")))))
        assertFalse("name_pinyin" in untouched.columnBindings, "值没变就不必重算检索码")
    }

    /**
     * ext 是整列覆盖写入：PATCH 一个字段时，未触碰的其他 ext 字段必须原样带回去。
     *
     * 块 3 实测踩过这个坑（管道只输出触碰键、装配层绑回整列，会把同列其他字段抹成 null），
     * 所以这条不是锦上添花的用例，而是防止复发的唯一屏障。
     */
    @Test
    fun `PATCH 单字段不得抹掉同一 ext 列里未触碰的字段`() {
        val g = graphOf(field("name", LogicalType.TEXT), field("memo", LogicalType.TEXTAREA), field("phone", LogicalType.PHONE))
        val row =
            WriteRow(
                "R1",
                3,
                values =
                    mapOf(
                        "name" to DraftValue.Text("甲"),
                        "memo" to DraftValue.Text("留着"),
                        "phone" to DraftValue.Text("13800001111"),
                    ),
            )
        val p = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 3), RecordDraft.of("name" to DraftValue.Text("乙")), row))
        assertEquals(setOf("name"), p.diff.keys)
        assertEquals(
            mapOf(
                "name" to DraftValue.Text("乙"),
                "memo" to DraftValue.Text("留着"),
                "phone" to DraftValue.Text("13800001111"),
            ),
            p.extValues,
            "未触碰的 memo/phone 必须留在 ext 图里",
        )
        // 显式清空以 Cleared 形式进 ext 图（编码阶段据此删键），其余键照旧保留
        val cleared = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 3), RecordDraft.of("memo" to DraftValue.Cleared), row))
        assertEquals(DraftValue.Cleared, cleared.extValues["memo"])
        assertEquals(DraftValue.Text("甲"), cleared.extValues["name"])
        assertEquals(DraftValue.Text("13800001111"), cleared.extValues["phone"])
    }

    /**
     * 数字按**值**比、不按字符串比。
     *
     * 起因是块 5 双库 IT：`12.50` 写进 ext 后 MySQL 的 JSON 规范化回读成 `12.5`，字符串等值把
     * 一次「什么都没改」的整单保存判成变更（PG 的 jsonb 保留尾零，故只在 MySQL 复现）。
     * 误判的代价不止脏 diff：M3「结果一致则跳过」会失效并连带触发级联。
     */
    @Test
    fun `数字尾零差异不算变更 文本差异仍算`() {
        val g = graphOf(field("amount", LogicalType.DECIMAL), field("memo", LogicalType.TEXT))
        val row = WriteRow("R1", 5, values = mapOf("amount" to DraftValue.Number("12.5"), "memo" to DraftValue.Text("m")))
        val same = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 5), RecordDraft.of("amount" to DraftValue.Number("12.50")), row))
        assertTrue(same.diff.isEmpty(), "12.5 与 12.50 是同一个值，不该产生 diff：${same.diff}")
        assertTrue(same.warnings.isEmpty(), "无变化也就不该有盲覆盖告警")
        val changed = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 5), RecordDraft.of("amount" to DraftValue.Number("12.6")), row))
        assertEquals(setOf("amount"), changed.diff.keys)
        // 阶段 6 净化在裁决之前：提交 "m " 会被 normalize 成 "m"，与库里现值同字 → 无变化可跳。
        // 这条记录在案，是为了让「净化先于 diff」这个顺序不被顺手调换（调换了 M3 会因空格差异白跑一轮级联）。
        val spaced = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 5), RecordDraft.of("memo" to DraftValue.Text("m ")), row))
        assertTrue(spaced.diff.isEmpty(), "净化后与现值同字，不该产生 diff：${spaced.diff}")
        val real = plan(WriteInput(g, ctxOf(recordId = "R1", expectedVersion = 5), RecordDraft.of("memo" to DraftValue.Text("m2")), row))
        assertEquals(setOf("memo"), real.diff.keys, "改了字就必须是变更")
    }

    // ---------- 阶段 2 守卫接缝 ----------

    @Test
    fun `守卫拒绝先于一切校验 证明无旁路`() {
        val reject =
            object : WriteGuard {
                override fun check(
                    req: WriteGuardRequest,
                ): Unit = throw WriteErrors.forbidden("缺少对象 UPDATE 权限", mapOf("op" to "UPDATE"))
            }
        val ex =
            rejected(
                WriteInput(graphOf(field("name", LogicalType.TEXT)), ctxOf(), RecordDraft.of("ghost" to DraftValue.Text("x"))),
                WritePipeline(newId = { "X".repeat(26) }, guard = reject),
            )
        assertEquals(ApiError.FORBIDDEN, ex.apiError, "载荷里有未注册键，但守卫在前——违规清单不得泄漏给无权调用方")
        assertEquals(WriteErrors.ID_FORBIDDEN, (ex.data as Map<*, *>)["error_id"])
    }
}
