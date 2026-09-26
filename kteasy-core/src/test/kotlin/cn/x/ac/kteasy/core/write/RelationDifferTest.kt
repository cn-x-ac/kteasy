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
import kotlin.test.assertTrue

/**
 * N2N 集合差纯函数单测（M1-07 块 3A 单元①）。锁四件事：
 * ① 加＝载荷−现存（保声明序）、删＝现存−载荷（升序）、保留＝交集（保序）；
 * ② 载荷重复去重；③ 空载荷＝清空全部现存（全删，无保留）；
 * ④ 保留行绝不出现在加/删集（＝执行层不碰它，从而 ext 附加列不丢——红线的纯函数侧）。
 */
class RelationDifferTest {
    @Test
    fun `加删保留三集正确`() {
        val d = RelationDiffer.diff(payload = listOf("x", "a", "y"), existingIds = setOf("a", "b", "c"))
        assertEquals(listOf("x", "y"), d.toAdd, "新增按载荷序")
        assertEquals(listOf("a"), d.kept, "保留＝交集")
        assertEquals(listOf("b", "c"), d.toRemove, "删除＝现存−载荷，升序")
    }

    @Test
    fun `载荷去重保序`() {
        val d = RelationDiffer.diff(payload = listOf("a", "b", "a"), existingIds = emptySet())
        assertEquals(listOf("a", "b"), d.toAdd)
    }

    @Test
    fun `空载荷即清空`() {
        val d = RelationDiffer.diff(payload = emptyList(), existingIds = setOf("a", "b"))
        assertTrue(d.toAdd.isEmpty())
        assertTrue(d.kept.isEmpty())
        assertEquals(listOf("a", "b"), d.toRemove)
    }

    @Test
    fun `保留行不进加删集`() {
        val d = RelationDiffer.diff(payload = listOf("a", "b"), existingIds = setOf("b", "c"))
        // b 两边都有 → 只在 kept
        assertEquals(listOf("b"), d.kept)
        assertTrue("b" !in d.toAdd && "b" !in d.toRemove, "保留行不得被重插或删除（否则 ext 附加列会丢）")
    }
}
