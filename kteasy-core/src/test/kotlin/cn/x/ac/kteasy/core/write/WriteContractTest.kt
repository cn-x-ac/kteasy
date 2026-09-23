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
import cn.x.ac.kteasy.core.meta.FieldWritePolicy
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.RequiredScope
import cn.x.ac.kteasy.core.meta.StorageKind
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 写入契约层单测（步骤卡 M1-06 块 1b）。
 *
 * 只测「一旦漂移就毁掉公开契约」的三件事：定位裁决（阶段 1）、只读/必填档位裁决（阶段 4）、
 * 错误契约体形状与 error_id 清单（代拍 D1 的落点）。落库/并发/事件时序一律留给块 5 的双库 IT。
 */
class WriteContractTest {
    private fun field(
        api: String,
        policy: FieldWritePolicy = FieldWritePolicy.WRITABLE,
        required: Boolean = false,
        scope: RequiredScope = RequiredScope.ALWAYS,
    ): MdField =
        MdField(
            id = "F_$api",
            objectId = "OBJ1",
            apiName = api,
            label = api,
            logicalType = LogicalType.TEXT,
            storageKind = StorageKind.EXT,
            required = required,
            writePolicy = policy,
            requiredScope = scope,
        )

    private fun ctx(
        intent: WriteIntent = WriteIntent.UPSERT,
        source: WriteSource = WriteSource.UI,
    ): WriteContext =
        WriteContext(
            objectApi = "account",
            intent = intent,
            source = source,
            actor = WriteActor("u_root", "d_root"),
            traceId = "tr-1",
            now = ZonedDateTime.of(2026, 9, 23, 10, 0, 0, 0, ZoneOffset.UTC),
        )

    // ---------- 阶段 1：定位裁决 ----------

    @Test
    fun `意图与 id 有无裁决写入性质`() {
        // UPSERT：无 id、或带 id 但行不在 → 新建；带 id 且命中 → 更新（图纸 04 阶段 1「按 id 有无」）
        assertEquals(WriteKind.CREATED, resolveKind(WriteIntent.UPSERT, hasId = false, existed = false))
        assertEquals(WriteKind.CREATED, resolveKind(WriteIntent.UPSERT, hasId = true, existed = false))
        assertEquals(WriteKind.UPDATED, resolveKind(WriteIntent.UPSERT, hasId = true, existed = true))
        // 删除/恢复的性质与 id 有无无关（两者必然带 id）
        listOf(true, false).forEach { has ->
            assertEquals(WriteKind.DELETED, resolveKind(WriteIntent.DELETE, hasId = has, existed = true))
            assertEquals(WriteKind.RESTORED, resolveKind(WriteIntent.RESTORE, hasId = has, existed = true))
        }
    }

    // ---------- 阶段 4：只读与必填档位 ----------

    @Test
    fun `写策略五档在新建与更新路径上的可写性`() {
        val expected =
            mapOf(
                FieldWritePolicy.WRITABLE to (true to true),
                FieldWritePolicy.NO_CREATE to (false to true),
                FieldWritePolicy.NO_UPDATE to (true to false),
                FieldWritePolicy.READONLY to (false to false),
                FieldWritePolicy.DERIVED to (false to false),
            )
        expected.forEach { (policy, createUpdate) ->
            val f = field("p_${policy.name.lowercase()}", policy = policy)
            assertEquals(createUpdate.first, f.writableOn(creating = true), "$policy 新建路径")
            assertEquals(createUpdate.second, f.writableOn(creating = false), "$policy 更新路径")
        }
    }

    @Test
    fun `必填作用域只在必填时生效`() {
        listOf(
            Triple(RequiredScope.ALWAYS, true, true),
            Triple(RequiredScope.ALWAYS, false, true),
            Triple(RequiredScope.CREATE, true, true),
            Triple(RequiredScope.CREATE, false, false),
            Triple(RequiredScope.UPDATE, true, false),
            Triple(RequiredScope.UPDATE, false, true),
        ).forEach { (scope, creating, want) ->
            val f = field("r_${scope.name.lowercase()}_$creating", required = true, scope = scope)
            assertEquals(want, f.isRequiredOn(creating), "$scope creating=$creating")
        }
        // required=false 时作用域恒不生效：不得凭作用域配置把字段变成必填
        RequiredScope.entries.forEach { scope ->
            val f = field("off_${scope.name.lowercase()}", required = false, scope = scope)
            assertFalse(f.isRequiredOn(creating = true), "$scope 非必填不得判定为必填")
            assertFalse(f.isRequiredOn(creating = false), "$scope 非必填不得判定为必填")
        }
    }

    // ---------- 载荷语义：未触碰 vs 显式清空 ----------

    @Test
    fun `草稿区分未触碰与显式清空`() {
        val draft = RecordDraft.of("a" to DraftValue.Text("x"), "b" to DraftValue.Cleared)
        assertTrue(draft.touches("a"))
        assertTrue(draft.touches("b"), "显式清空也是触碰（要进 diff）")
        assertFalse(draft.touches("ghost"), "键缺失＝本次不触碰该字段")
        assertEquals(setOf("a", "b"), draft.touchedFields)
    }

    // ---------- 错误契约（代拍 D1 的落点） ----------

    /**
     * 符号名清单**按精确集合相等**锁死：只断「无重复」等于没锁——新增码会悄悄漂出去，
     * 而这张清单要进 API 总表与图纸 04，是公开契约（含 M1-07/M3 两个预留位）。
     */
    @Test
    fun `符号名清单按精确相等冻结`() {
        assertEquals(
            setOf(
                "WRITE_NOT_FOUND",
                "WRITE_FORBIDDEN",
                "FIELD_TYPE",
                "FIELD_REQUIRED",
                "OPTION_DOMAIN",
                "FIELD_READONLY",
                "SYSTEM_COLUMN_READONLY",
                "EXT_UNKNOWN_KEY",
                "FIELD_DISABLED",
                "OBJECT_DISABLED",
                "CONFLICT_RETRY",
                "LOCK_RETRY",
                "FK_VIOLATION",
                "IN_USE",
                "AUTONUM_FAILED",
                "AUTOMATION_REJECTED",
            ),
            WriteErrors.ALL_IDS.toSet(),
            "新增/删除符号名必须同步图纸 04、API 总表与本断言",
        )
        assertEquals(ApiError.FORBIDDEN, WriteErrors.forbidden("x").apiError)
    }

    @Test
    fun `符号名清单冻结且格式统一`() {
        val ids = WriteErrors.ALL_IDS
        assertEquals(ids.size, ids.toSet().size, "error_id 不得重复")
        assertTrue(ids.all { Regex("^[A-Z][A-Z0-9_]*$").matches(it) }, "error_id 一律大写下划线：$ids")
        // 契约码只发基座段（D1）：任何新增都不得绕过这条断言去动 ApiError 枚举
        assertTrue(WriteErrors.forbidden("x").apiError == ApiError.FORBIDDEN)
    }

    @Test
    fun `定位失败的契约体三键与键序`() {
        val ex = WriteErrors.notFound("account", "01ABCDEFGHIJ0123456789")
        val body = ex.apiError.toBody(ex.message!!, ex.data)
        assertEquals(listOf("error_code", "error_msg", "data"), body.keys.toList(), "键序冻结")
        assertEquals(404, body["error_code"])
        val data = body["data"] as Map<*, *>
        assertEquals(WriteErrors.ID_NOT_FOUND, data["error_id"])
        assertEquals("account", data["object"])
        assertEquals("01ABCDEFGHIJ0123456789", data["record_id"])
    }

    @Test
    fun `承载码由符号名派生 改载荷可自救的一律 410`() {
        // 判据（P2，适用面＝写通道）：改载荷能重发 → 410；得先改现场状态 → 420
        val loadFixable =
            listOf(
                WriteErrors.ID_FIELD_TYPE,
                WriteErrors.ID_FIELD_REQUIRED,
                WriteErrors.ID_OPTION_DOMAIN,
                WriteErrors.ID_EXT_UNKNOWN_KEY,
                WriteErrors.ID_FIELD_DISABLED,
            )
        loadFixable.forEach { id ->
            assertEquals(ApiError.INVALID_PARAM, WriteErrors.violation("f", id, "x").contract, "$id 属改载荷可自救")
        }
        (WriteErrors.ALL_IDS - WriteErrors.LOAD_FIXABLE_IDS - setOf(WriteErrors.ID_NOT_FOUND, WriteErrors.ID_FORBIDDEN)).forEach { id ->
            assertEquals(ApiError.BUSINESS_RULE, WriteErrors.violation("f", id, "x").contract, "$id 属现场状态类")
        }
        // 判据表本身不得漂出未登记符号名
        assertTrue(WriteErrors.LOAD_FIXABLE_IDS.all { it in WriteErrors.ALL_IDS }, "LOAD_FIXABLE_IDS 必须是已登记符号名的子集")
        // 混合违规集整体走 420（现场状态优先），全部可自救才走 410；明细一项不丢、error_id 取首条
        val mixed = WriteErrors.rejected(listOf(WriteErrors.violation("phone", WriteErrors.ID_FIELD_TYPE, "格式不符"), WriteErrors.violation("owner", WriteErrors.ID_SYSTEM_COLUMN_READONLY, "系统列不可写")))
        assertEquals(ApiError.BUSINESS_RULE, mixed.apiError)
        val data = mixed.data as Map<*, *>
        val fields = data["fields"] as List<*>
        assertEquals(2, fields.size, "明细不得丢项")
        assertEquals(WriteErrors.ID_FIELD_TYPE, data["error_id"], "error_id 取首条违规")
        assertEquals("phone", (fields.first() as Map<*, *>)["field"], "未注册键/违规字段名要能回显")
        val allLoad = WriteErrors.rejected(listOf(WriteErrors.violation("phone", WriteErrors.ID_FIELD_TYPE, "格式不符"), WriteErrors.violation("stage", WriteErrors.ID_OPTION_DOMAIN, "不在候选")))
        assertEquals(ApiError.INVALID_PARAM, allLoad.apiError)
    }

    @Test
    fun `空违规集聚合即编程错误`() = assertFailsWith<IllegalArgumentException> { WriteErrors.rejected(emptyList()) }

    @Test
    fun `同事务批量写的特权来源清单冻结`() {
        // 清单只能变宽得看得见：UI/OPENAPI 永远不得进内部批量通道（卡面 §6）
        assertEquals(setOf(WriteSource.SYSTEM, WriteSource.TRANSFORM, WriteSource.IMPORT), InternalWriteSources.ALLOWED)
        assertFalse(InternalWriteSources.isAllowed(WriteSource.UI))
        assertFalse(InternalWriteSources.isAllowed(WriteSource.OPENAPI))
        assertTrue(InternalWriteSources.isAllowed(WriteSource.SYSTEM))
    }

    @Test
    fun `并发与锁两类可重试错误都走 420 且带定位符`() {
        val conflict = WriteErrors.conflictRetry("01ABC", expected = 3, actual = 5)
        assertEquals(ApiError.BUSINESS_RULE, conflict.apiError)
        val cd = conflict.data as Map<*, *>
        assertEquals(WriteErrors.ID_CONFLICT_RETRY, cd["error_id"])
        assertEquals(3L, cd["expected_version"])
        assertEquals(5L, cd["actual_version"])

        val lock = WriteErrors.lockRetry("KTEASY:W:OBJ1:01ABC", timeoutSeconds = 5)
        assertEquals(ApiError.BUSINESS_RULE, lock.apiError, "现场状态类走 420（是否另立 429 承载位＝待决 P4）")
        assertEquals(WriteErrors.ID_LOCK_RETRY, (lock.data as Map<*, *>)["error_id"])
    }

    @Test
    fun `上下文只带事实不带判断`() {
        // 守卫接缝默认透传（M2-02 换 bean），此处锁住「不抛即放行」的形状
        val guard = PassthroughWriteGuard()
        guard.check(WriteGuardRequest(ctx(), setOf("name"), recordId = null))
        // 缺省期望版本为 null＝不强校验；来源与意图必须由调用方显式带入，管道不自取
        assertEquals(null, ctx().expectedVersion)
        assertEquals(WriteSource.UI, ctx().source)
    }

    // ---------- 告警码（P6）与写锁键位 ----------

    @Test
    fun `告警码清单冻结且载荷形状稳定`() {
        assertEquals(
            setOf("OVERRIDE_WITHOUT_VERSION", "FIELD_DEPRECATED", "AUTONUM_SKIPPED"),
            WriteWarnings.ALL_CODES.toSet(),
            "新增告警码须同步图纸 04 与本断言",
        )
        val withField = WriteWarning(WriteWarnings.OVERRIDE_WITHOUT_VERSION, "name", "未带版本却覆盖了当前值").toWire()
        assertEquals(listOf("code", "field", "msg"), withField.keys.toList(), "键序冻结")
        val noField = WriteWarning(WriteWarnings.AUTONUM_SKIPPED, message = "导入不推进编号").toWire()
        assertEquals(listOf("code", "msg"), noField.keys.toList(), "无字段时不写显式 null（与 M1-05 D9 的键存在性口径一致）")
        assertFailsWith<IllegalArgumentException> { WriteWarning("NOT_REGISTERED", message = "x") }
    }

    @Test
    fun `写锁键恒定不越 MySQL 锁名上限`() {
        // 记录级锁 9+26+1+26=62；若按 api_name 拼最长可达 74，会被 LockKey 的 64 上限拦成运行期异常
        val obj = "0".repeat(26)
        val rec = "1".repeat(26)
        val key = WriteLocks.of(obj, rec)
        assertEquals(WriteLocks.RECORD_PREFIX + obj + ':' + rec, key.name)
        assertEquals(62, key.name.length)
        assertEquals(WriteLocks.of(obj, rec).id, key.id, "两库同一逻辑键须由同一锁名派生")
        assertTrue(WriteLocks.ofObject(obj).name.length <= 64)
        assertFalse(key.name.startsWith("KTEASY:SCHEMA:"), "记录写锁与元数据变更锁须不同命名空间")
    }
}
