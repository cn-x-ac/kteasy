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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 子项三集纯函数单测（M1-07 块 2 单元①，A1 定稿口径）。锁四件事：
 * ① 无 id=新建、带 id∩现存=更新、现存−载荷=软删；② 空数组=清空整表；
 * ③ 带 id 但不在现存=404 `WRITE_NOT_FOUND`（复用阶段 1，不新造码）；④ deletes 升序、creates/updates 保序。
 */
class DetailDifferTest {
    private fun row(
        id: String?,
        qty: String,
    ) = DetailRow(id, mapOf("qty" to DraftValue.Number(qty)))

    @Test
    fun `三集分类正确`() {
        val payload = listOf(row(null, "5"), row("B", "9"), row(null, "2"), row("A", "7"))
        val diff = DetailDiffer.diff("order_line", payload, existingIds = setOf("A", "B", "C"))
        // 新建按载荷序（两条无 id）
        assertEquals(listOf("5", "2"), diff.creates.map { (it.fields["qty"] as DraftValue.Number).literal })
        // 更新按载荷序（B 先于 A）
        assertEquals(listOf("B", "A"), diff.updates.map { it.id })
        // 现存 C 未被载荷保留 → 软删；升序
        assertEquals(listOf("C"), diff.deletes)
    }

    @Test
    fun `空数组即清空整表`() {
        val diff = DetailDiffer.diff("order_line", emptyList(), existingIds = setOf("X", "Y"))
        assertTrue(diff.creates.isEmpty())
        assertTrue(diff.updates.isEmpty())
        assertEquals(listOf("X", "Y"), diff.deletes)
    }

    @Test
    fun `现存为空则全是新建`() {
        val diff = DetailDiffer.diff("order_line", listOf(row(null, "1"), row(null, "2")), existingIds = emptySet())
        assertEquals(2, diff.creates.size)
        assertTrue(diff.deletes.isEmpty())
    }

    @Test
    fun `载荷与现存一一对应则无新建无删除`() {
        // 三集只看 id：带 id∩现存一律算 update（值是否真变是 per-row 管道阶段的"无变化跳过"，不在这里判）。
        val diff = DetailDiffer.diff("order_line", listOf(row("A", "1")), existingIds = setOf("A"))
        assertTrue(diff.creates.isEmpty())
        assertTrue(diff.deletes.isEmpty())
        assertEquals(listOf("A"), diff.updates.map { it.id })
    }

    @Test
    fun `带 id 不在现存出 404 复用定位失败语义`() {
        val ex =
            assertFailsWith(cn.x.ac.kteasy.core.kernel.KnownKteasyException::class) {
                DetailDiffer.diff("order_line", listOf(row("GHOST", "1")), existingIds = setOf("A"))
            }
        assertEquals(cn.x.ac.kteasy.core.kernel.ApiError.NOT_FOUND, ex.apiError)
        val data = ex.data as Map<*, *>
        assertEquals("WRITE_NOT_FOUND", data["error_id"])
        assertEquals("order_line", data["object"])
        assertEquals("GHOST", data["record_id"])
    }

    @Test
    fun `载荷缺子对象键即不碰 由 RecordDraft 层表达`() {
        // 缺键不落到 diff：details 里没这个子对象键＝整表不碰（与字段级 PATCH 同轴）。
        val draft = RecordDraft(values = mapOf("no" to DraftValue.Text("P1")), details = emptyMap())
        assertTrue(draft.touchedDetails.isEmpty())
    }
}
