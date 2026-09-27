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

/*
 * 组织树（部门）的子树解析与物化 path 形态（步骤卡 M2a-01 §设计要点 3）。
 *
 * **本文件零 SQL、零 IO、零框架**：渲染成 SQL 归【M2b-04】，落库与移动归 `server/md/DeptService`（M2a-01 单元③）。
 * 四层不同卡的纪律在这里就是"解析器里不出现一个 SQL token"。
 *
 * ### path 形态的唯一真源（为什么在这里）
 * 卡面要求"分隔符与根节点表示一律复用 dict 的实装，不另造第二套树形态"。实况是：**字典的写口尚未实现**
 * （V3 只建了 `md_dict_item.path` 列，[cn.x.ac.kteasy.server.md.MetadataRepository] 只有 `ORDER BY path` 的读侧），
 * 于是"复用实装"没有可复用的对象。故形态在此按【图纸 01 §1】的既有文字（`001/002/005`、层级 ≤4）落定，
 * 成为仓库里唯一的一份常量；**将来实现字典写口时必须复用 [TreePaths]**，否则就长出第二套树形态——那正是本卡要避免的事。
 */

/** 树的一条边：节点 id 与其父 id（根节点父为 null）。**不含** `enabled`——停用节点照样在树里，见 [DeptTreeResolver] 的 P23 口径。 */
data class DeptEdge(
    val id: String,
    val parentId: String? = null,
)

/**
 * 物化 path 的编解码（形态真源，字典与部门共用）。
 *
 * 一段＝该层 `seq` 的 [SEGMENT_WIDTH] 位零填充十进制，段间一个 [SEPARATOR]；
 * 根节点的 path 就是它自己那一段（**无前导分隔符**），故 `depth("001") == 1`。
 *
 * 为什么用定宽数字段而不是把 id 串起来：定宽段让 `ORDER BY path` 在同层内天然按 seq 有序（字典读侧
 * 现在就依赖这一点），且段长固定让"前缀匹配"可以严格按段边界判定（见 [isDescendantPath]）。
 */
object TreePaths {
    const val SEPARATOR: Char = '/'

    /** 段宽 3 位 ⇒ 同层上限 999 个节点；超出说明建树方式有问题，宁可抛错也不要静默错排序。 */
    const val SEGMENT_WIDTH: Int = 3

    /** 层级上限（照【图纸 01 §1】字典的 `层级≤4 ⟨可逆⟩`；部门同法，改判只动这一处）。 */
    const val MAX_DEPTH: Int = 4

    /** 一个 `seq` 渲染成一段。越界即抛（不静默截断、不静默变宽——变宽会毁掉 `ORDER BY path` 的同层序）。 */
    fun segment(
        seq: Int,
    ): String {
        if (seq < 1 || seq > MAX_SEQ) {
            throw DeptTreeErrors.badSegment(seq, MAX_SEQ)
        }
        return seq.toString().padStart(SEGMENT_WIDTH, '0')
    }

    /** 由根到叶的段序列拼成 path；空序列非法（每个节点至少有一段）。 */
    fun join(
        segments: List<String>,
    ): String {
        if (segments.isEmpty()) {
            throw DeptTreeErrors.emptyPath()
        }
        segments.forEach { seg ->
            if (seg.length != SEGMENT_WIDTH || seg.any { !it.isDigit() }) {
                throw DeptTreeErrors.badSegmentLiteral(seg)
            }
        }
        if (segments.size > MAX_DEPTH) {
            throw DeptTreeErrors.tooDeep(segments.size, MAX_DEPTH)
        }
        return segments.joinToString(SEPARATOR.toString())
    }

    /** 解析 path 为段序列（根到叶）。 */
    fun segmentsOf(
        path: String,
    ): List<String> {
        validate(path)
        return path.split(SEPARATOR)
    }

    fun depth(
        path: String,
    ): Int = segmentsOf(path).size

    /** 直接父的 path；根节点无父返回 null。 */
    fun parentPath(
        path: String,
    ): String? {
        val segs = segmentsOf(path)
        return if (segs.size == 1) null else segs.dropLast(1).joinToString(SEPARATOR.toString())
    }

    /**
     * [path] 是否落在 [prefix] 这棵子树内（**含自身**）。严格按段边界：`001` 不是 `0011`… 的前缀子树，
     * 因为段宽固定，比较只需"等值 或 以 `prefix + '/'` 开头"。
     */
    fun isDescendantPath(
        path: String,
        prefix: String,
    ): Boolean {
        validate(path)
        validate(prefix)
        return path == prefix || path.startsWith(prefix + SEPARATOR)
    }

    /**
     * 给 SQL 侧用的**前缀值**（等值 + 该前缀 LIKE 值两条，供调用方一条 OR）。返回值的 `%` 通配由渲染层加，
     * 这里只给"以 `prefix/` 开头"的字面量——把段边界留在真源里，避免每张卡各写一次 LIKE 拼法。
     */
    fun descendantLikeValue(
        prefix: String,
    ): String {
        validate(prefix)
        return prefix + SEPARATOR
    }

    /** 结构合法性校验（段数、段宽、全数字、无空段/前导尾随分隔符）。 */
    fun validate(
        path: String,
    ) {
        if (path.isEmpty() || path.startsWith(SEPARATOR) || path.endsWith(SEPARATOR) || path.contains("$SEPARATOR$SEPARATOR")) {
            throw DeptTreeErrors.malformedPath(path)
        }
        path.split(SEPARATOR).forEach { seg ->
            if (seg.length != SEGMENT_WIDTH || seg.any { !it.isDigit() }) {
                throw DeptTreeErrors.badSegmentLiteral(seg)
            }
        }
        if (path.count { it == SEPARATOR } + 1 > MAX_DEPTH) {
            throw DeptTreeErrors.tooDeep(path.count { it == SEPARATOR } + 1, MAX_DEPTH)
        }
    }

    private const val MAX_SEQ: Int = 999 // = 10^SEGMENT_WIDTH - 1
}

/** 树结构错误的符号名与承载码清单（承图纸 04 §2：承载码只用基座段，符号名进 `data.error_id`）。 */
object DeptTreeErrors {
    /** 结构违例都是"改载荷没用、得先改现场"，故一律 420；未知节点是找不到，404。 */
    val ALL_IDS: Set<String> =
        setOf(
            "DEPT_CYCLE",
            "DEPT_DANGLING_PARENT",
            "DEPT_DUPLICATE_NODE",
            "DEPT_TOO_DEEP",
            "DEPT_UNKNOWN_NODE",
            "DEPT_BAD_SEQ",
            "DEPT_BAD_PATH",
        )

    fun cycle(
        nodeIds: List<String>,
    ): KnownKteasyException = structural("DEPT_CYCLE", "部门树存在环：${nodeIds.joinToString(" → ")}")

    fun danglingParent(
        id: String,
        parentId: String,
    ): KnownKteasyException = structural("DEPT_DANGLING_PARENT", "部门 $id 的父部门 $parentId 不存在")

    fun duplicateNode(
        id: String,
    ): KnownKteasyException = structural("DEPT_DUPLICATE_NODE", "部门 $id 出现两条边（一个节点不得有两个父）")

    fun tooDeep(
        depth: Int,
        max: Int,
    ): KnownKteasyException = structural("DEPT_TOO_DEEP", "部门树层级 $depth 超上限 $max")

    fun unknownNode(
        id: String,
    ): KnownKteasyException = KnownKteasyException(ApiError.NOT_FOUND, "部门 $id 不在树中", mapOf("error_id" to "DEPT_UNKNOWN_NODE", "node" to id))

    fun badSegment(
        seq: Int,
        max: Int,
    ): KnownKteasyException = structural("DEPT_BAD_SEQ", "同层序号 $seq 越界（须在 1..$max）")

    fun badSegmentLiteral(
        seg: String,
    ): KnownKteasyException = structural("DEPT_BAD_PATH", "path 段 \"$seg\" 非法（须为 ${TreePaths.SEGMENT_WIDTH} 位数字）")

    fun malformedPath(
        path: String,
    ): KnownKteasyException = structural("DEPT_BAD_PATH", "path \"$path\" 结构非法")

    fun emptyPath(): KnownKteasyException = structural("DEPT_BAD_PATH", "path 至少含一段")

    private fun structural(
        errorId: String,
        message: String,
    ): KnownKteasyException = KnownKteasyException(ApiError.BUSINESS_RULE, message, mapOf("error_id" to errorId))
}

/**
 * 子树解析器（纯函数）。
 *
 * **停用节点计入子树**（P23）：解析器只看 `(id, parent_id)` 边集、根本不看 `enabled`——`enabled` 只表示组织停用，
 * 不表示记录消失。若在解析侧排除停用部门，停用部门下**仍有归属的记录**就会对普通用户集体失控（漏判即越权）。
 * 要按停用过滤，请在渲染侧（【M2b-04】）追加条件，不动本解析器。
 *
 * **确定性**：输入边集的迭代序不影响输出——`subtree` 恒返回 id 升序（ULID 钉 binary 后＝时间序），`ancestors` 恒根在前。
 */
object DeptTreeResolver {
    /** 边集 → `id → parentId`。同 id 两条边即抛（一个节点不得有两个父）。 */
    fun parentIndex(
        edges: List<DeptEdge>,
    ): Map<String, String?> {
        val index = LinkedHashMap<String, String?>()
        for (e in edges) {
            if (index.put(e.id, e.parentId) != null) {
                throw DeptTreeErrors.duplicateNode(e.id)
            }
        }
        return index
    }

    /**
     * 结构校验：悬空父、自环/成环、深度越上限。落库前的闸门（卡面 GWT-2「成环即抛，不入库」）。
     *
     * 深度按**父链**算（不按 path 字符串），这样校验与 path 物化解耦：移动子树时不必先重算 path 就能判合法。
     */
    fun validateForest(
        edges: List<DeptEdge>,
    ) {
        val parents = parentIndex(edges)
        for ((id, parentId) in parents) {
            if (parentId != null && !parents.containsKey(parentId)) {
                throw DeptTreeErrors.danglingParent(id, parentId)
            }
        }
        // 逐节点向上爬：走过的节点入 seen，撞上即环；爬满 parents.size 步仍没到根也判环（保守兜底）
        for (id in parents.keys) {
            val seen = LinkedHashSet<String>()
            var cur: String? = id
            var depth = 0
            while (cur != null) {
                if (!seen.add(cur)) {
                    throw DeptTreeErrors.cycle(seen.toList() + cur)
                }
                if (++depth > parents.size) {
                    throw DeptTreeErrors.cycle(seen.toList())
                }
                if (depth > TreePaths.MAX_DEPTH) {
                    throw DeptTreeErrors.tooDeep(depth, TreePaths.MAX_DEPTH)
                }
                cur = parents[cur]
            }
        }
    }

    /** 父 → 子节点列表（每个列表 id 升序，保证遍历确定性）。 */
    fun childrenIndex(
        edges: List<DeptEdge>,
    ): Map<String, List<String>> {
        val children = LinkedHashMap<String, MutableList<String>>()
        for (e in edges) {
            val parent = e.parentId ?: continue
            children.getOrPut(parent) { mutableListOf() }.add(e.id)
        }
        return children.mapValues { (_, kids) -> kids.sorted() }
    }

    /**
     * 取 [rootId] 的子树（**含根自身**）。`DEPT_CHILD` 的语义是"本部门及子部门"，不含根就少一档。
     *
     * @throws KnownKteasyException 根不存在（404）或结构非法（先跑校验）
     */
    fun subtree(
        edges: List<DeptEdge>,
        rootId: String,
    ): List<String> {
        val parents = parentIndex(edges)
        if (!parents.containsKey(rootId)) {
            throw DeptTreeErrors.unknownNode(rootId)
        }
        validateForest(edges)
        val children = childrenIndex(edges)
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!out.add(node)) {
                continue
            }
            children[node]?.let { queue.addAll(it) }
        }
        return out.sorted()
    }

    /** 父链（根在前、不含自身）。根节点 → 空列表。 */
    fun ancestors(
        edges: List<DeptEdge>,
        id: String,
    ): List<String> {
        val parents = parentIndex(edges)
        if (!parents.containsKey(id)) {
            throw DeptTreeErrors.unknownNode(id)
        }
        val chain = ArrayList<String>()
        var cur = parents[id]
        while (cur != null) {
            if (cur in chain) {
                throw DeptTreeErrors.cycle(chain + cur)
            }
            chain.add(0, cur)
            cur = parents[cur]
        }
        return chain
    }

    /** 该节点的深度（根＝1）。 */
    fun depthOf(
        edges: List<DeptEdge>,
        id: String,
    ): Int = ancestors(edges, id).size + 1

    /**
     * 由边集与"节点 → 同层序号"算出某节点的物化 path（根到该节点各段拼接）。
     *
     * 移动子树时对每个后代各调一次即可（成本 O(深度)，深度有上限）；批量重算在 DeptService 里做，本函数保持纯。
     */
    fun pathOf(
        edges: List<DeptEdge>,
        id: String,
        seqOf: Map<String, Int>,
    ): String {
        val segments =
            (ancestors(edges, id) + id).map { node ->
                TreePaths.segment(seqOf[node] ?: throw DeptTreeErrors.unknownNode(node))
            }
        return TreePaths.join(segments)
    }
}
