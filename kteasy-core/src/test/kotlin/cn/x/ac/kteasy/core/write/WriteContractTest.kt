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
    fun `聚合拒绝取最重承载码并保留全部明细`() {
        val mixed =
            WriteErrors.rejected(
                listOf(
                    WriteErrors.FieldViolation("phone", WriteErrors.ID_FIELD_TYPE, "格式不符", contract = ApiError.INVALID_PARAM),
                    WriteErrors.FieldViolation("owner", WriteErrors.ID_SYSTEM_COLUMN_READONLY, "系统列不可写"),
                ),
            )
        assertEquals(ApiError.BUSINESS_RULE, mixed.apiError, "含 420 违规则整体走 420")
        val data = mixed.data as Map<*, *>
        val fields = data["fields"] as List<*>
        assertEquals(2, fields.size, "明细不得丢项")
        assertEquals(WriteErrors.ID_FIELD_TYPE, data["error_id"], "error_id 取首条违规")

        val only410 =
            WriteErrors.rejected(
                listOf(WriteErrors.FieldViolation("nickname2", WriteErrors.ID_EXT_UNKNOWN_KEY, "未注册键", contract = ApiError.INVALID_PARAM)),
            )
        assertEquals(ApiError.INVALID_PARAM, only410.apiError, "卡面 GWT3：未知 ext key 出 410 并回显 key 名")
        val d410 = only410.data as Map<*, *>
        assertEquals("nickname2", (d410["fields"] as List<*>).let { (it.first() as Map<*, *>)["field"] })
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
        assertEquals(ApiError.BUSINESS_RULE, lock.apiError, "卡面 §5 锁超时走 420，不占 429 节段（代拍 D6）")
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
}
