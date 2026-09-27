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
package cn.x.ac.kteasy.core.account

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 组织树解析器与 path 形态的纯函数单测（M2a-01 单元①，卡面 GWT-1/2/5 的 L1 半区）。
 *
 * 锁六件事：① 子树含根、含全部后代、不含兄弟；② 断环/悬空父/重复节点/超深都在解析期抛，
 * 且承载码与 `error_id` 稳定（符号名清单由 [errorId清单不与冻结符号名漂移] 锁死）；
 * ③ 未知节点走 404 而非 420（"查不到"与"现场不对"是两件事）；④ 停用节点照样进子树（P23）；
 * ⑤ 输入边集乱序不影响输出（确定性——权限判定的可复现性建立在这条上）；
 * ⑥ path 段边界：`001` 不得前缀命中 `001`… 之外的任何段。
 */
class DeptTreeResolverTest {
    /** 三层树：root → {a, b}；a → {a1, a2}。id 用升序可读名，避免依赖 ULID 生成序。 */
    private val tree: List<DeptEdge> =
        listOf(
            DeptEdge("root", null),
            DeptEdge("a", "root"),
            DeptEdge("b", "root"),
            DeptEdge("a1", "a"),
            DeptEdge("a2", "a"),
        )

    private fun errorIdOf(
        e: KnownKteasyException,
    ): String? = (e.data as? Map<*, *>)?.get("error_id") as? String

    @Test
    fun `子树含根与全部后代不含兄弟`() {
        assertEquals(listOf("a", "a1", "a2"), DeptTreeResolver.subtree(tree, "a"))
        assertEquals(listOf("a1"), DeptTreeResolver.subtree(tree, "a1"), "叶子节点的子树只有自己")
        assertEquals(listOf("a", "a1", "a2", "b", "root"), DeptTreeResolver.subtree(tree, "root"))
    }

    @Test
    fun `停用节点仍计入子树 P23`() {
        // 解析器的输入只有 (id, parent_id) 边集——没有 enabled 这一维，故"停用是否计入"在结构层就不存在。
        // 这条断言锁的是签名本身：谁想在解析侧过滤停用部门，必须先改这里，评审才看得到。
        val withDisabled = tree + DeptEdge("a3", "a")
        assertTrue("a3" in DeptTreeResolver.subtree(withDisabled, "a"), "边集里存在的节点必须出现在子树里")
        assertFalse(DeptTreeResolver.subtree(tree, "a").contains("zzz"), "不在边集里的节点不得凭空出现")
    }

    @Test
    fun `成环即抛不入库`() {
        val cyclic =
            listOf(
                DeptEdge("x", "y"),
                DeptEdge("y", "x"),
            )
        val e = assertFailsWith<KnownKteasyException> { DeptTreeResolver.validateForest(cyclic) }
        assertEquals(ApiError.BUSINESS_RULE, e.apiError, "环＝改现场才能自救 → 420，不是 410")
        assertEquals("DEPT_CYCLE", errorIdOf(e))
    }

    @Test
    fun `自环也算环`() {
        val e = assertFailsWith<KnownKteasyException> { DeptTreeResolver.validateForest(listOf(DeptEdge("s", "s"))) }
        assertEquals("DEPT_CYCLE", errorIdOf(e))
    }

    @Test
    fun `悬空父与重复节点各自有稳定符号名`() {
        val dangling = assertFailsWith<KnownKteasyException> { DeptTreeResolver.validateForest(listOf(DeptEdge("c", "gone"))) }
        assertEquals("DEPT_DANGLING_PARENT", errorIdOf(dangling))

        val dup = assertFailsWith<KnownKteasyException> { DeptTreeResolver.validateForest(listOf(DeptEdge("d", "p1"), DeptEdge("d", "p2"))) }
        assertEquals("DEPT_DUPLICATE_NODE", errorIdOf(dup))
    }

    @Test
    fun `超过层级上限即抛`() {
        val chain = listOf(DeptEdge("n1", null)) + (2..MAX_DEPTH_SAMPLE).map { DeptEdge("n$it", "n${it - 1}") }
        val e = assertFailsWith<KnownKteasyException> { DeptTreeResolver.validateForest(chain) }
        assertEquals("DEPT_TOO_DEEP", errorIdOf(e))
        // 恰好到上限（4 层）必须放行——判据是 > 而非 >=，差一错误在这里最容易写反
        val atLimit = chain.take(TreePaths.MAX_DEPTH)
        DeptTreeResolver.validateForest(atLimit)
        assertEquals(TreePaths.MAX_DEPTH, DeptTreeResolver.depthOf(atLimit, "n${TreePaths.MAX_DEPTH}"))
    }

    @Test
    fun `未知节点是 404 而非 420`() {
        val e = assertFailsWith<KnownKteasyException> { DeptTreeResolver.subtree(tree, "nope") }
        assertEquals(ApiError.NOT_FOUND, e.apiError, "查不到与现场不对不得共用一个码")
        assertEquals("DEPT_UNKNOWN_NODE", errorIdOf(e))
    }

    @Test
    fun `输入乱序不影响输出`() {
        val permutations =
            listOf(
                tree,
                tree.reversed(),
                tree.shuffled(kotlin.random.Random(7)),
                tree.shuffled(kotlin.random.Random(41)),
            )
        val expectedSubtree = listOf("a", "a1", "a2", "b", "root")
        permutations.forEach { edges ->
            assertEquals(expectedSubtree, DeptTreeResolver.subtree(edges, "root"), "子树输出必须是稳定升序")
            assertEquals(listOf("root"), DeptTreeResolver.ancestors(edges, "a"), "父链恒根在前")
            assertEquals(listOf("root", "a"), DeptTreeResolver.ancestors(edges, "a1"))
            assertEquals(mapOf("a" to listOf("a1", "a2"), "root" to listOf("a", "b")), DeptTreeResolver.childrenIndex(edges), "子列表恒升序，与输入序无关")
        }
    }

    @Test
    fun `符号名清单与冻结集合一致`() {
        assertEquals(
            setOf(
                "DEPT_CYCLE",
                "DEPT_DANGLING_PARENT",
                "DEPT_DUPLICATE_NODE",
                "DEPT_TOO_DEEP",
                "DEPT_UNKNOWN_NODE",
                "DEPT_BAD_SEQ",
                "DEPT_BAD_PATH",
            ),
            DeptTreeErrors.ALL_IDS,
            "新增/删除符号名必须同时改本清单（清单即评审面，防顺手加一个出口）",
        )
    }

    // ------------------------------ TreePaths ------------------------------

    @Test
    fun `段渲染与拼合`() {
        assertEquals("007", TreePaths.segment(7))
        assertEquals("001/002/005", TreePaths.join(listOf("001", "002", "005")), "形态照图纸 01 §1 的既有文字")
        assertEquals(3, TreePaths.depth("001/002/005"))
        assertEquals("001/002", TreePaths.parentPath("001/002/005"))
        assertNull(TreePaths.parentPath("001"), "根节点无父")
        assertEquals(listOf("001", "002"), TreePaths.segmentsOf("001/002"))
    }

    @Test
    fun `前缀判定按段边界不误伤`() {
        assertTrue(TreePaths.isDescendantPath("001/002", "001"))
        assertTrue(TreePaths.isDescendantPath("001", "001"), "子树含自身（DEPT_CHILD＝本部门及子）")
        assertFalse(TreePaths.isDescendantPath("002", "001"))
        assertFalse(TreePaths.isDescendantPath("001/002/003", "001/003"), "同层兄弟的子树不得互相命中")
        // 段宽固定 ⇒ "等值 或 以 `prefix/` 开头" 就是完备的段边界判据。这条锁的是 isDescendantPath 与
        // descendantLikeValue 的一致性：SQL 侧（M2b-04 与 DeptService）用后者，纯函数侧用前者，两者不得分叉。
        val paths = listOf("001", "001/002", "001/002/003", "002", "002/001", "010/001")
        val prefixes = listOf("001", "001/002", "002", "010")
        prefixes.forEach { p ->
            paths.forEach { path ->
                val like = path.startsWith(TreePaths.descendantLikeValue(p))
                assertEquals(like || path == p, TreePaths.isDescendantPath(path, p), "path=$path prefix=$p 两侧判据必须一致")
            }
        }
        assertFailsWith<KnownKteasyException> { TreePaths.validate("0010") }
    }

    @Test
    fun `非法形态一律抛而不是静默宽容`() {
        listOf("", "/001", "001/", "001//002", "01", "00a", "001/0002").forEach { bad ->
            val e = assertFailsWith<KnownKteasyException> { TreePaths.validate(bad) }
            assertEquals("DEPT_BAD_PATH", errorIdOf(e), "$bad 应被判非法 path")
        }
        assertEquals("DEPT_BAD_SEQ", errorIdOf(assertFailsWith { TreePaths.segment(0) }))
        assertEquals("DEPT_BAD_SEQ", errorIdOf(assertFailsWith { TreePaths.segment(1000) }))
        assertEquals("DEPT_TOO_DEEP", errorIdOf(assertFailsWith { TreePaths.join((1..5).map { "00$it" }) }))
    }

    @Test
    fun `path 由边集与同层序号算出`() {
        val seq = mapOf("root" to 1, "a" to 2, "a1" to 3)
        assertEquals("001", DeptTreeResolver.pathOf(tree, "root", seq))
        assertEquals("001/002", DeptTreeResolver.pathOf(tree, "a", seq))
        assertEquals("001/002/003", DeptTreeResolver.pathOf(tree, "a1", seq))
    }

    private companion object {
        /** 造一条比上限更深的链用（＝上限＋1 层）。 */
        const val MAX_DEPTH_SAMPLE: Int = TreePaths.MAX_DEPTH + 1
    }
}
