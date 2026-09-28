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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.account.DeptEdge
import cn.x.ac.kteasy.core.account.DeptTreeErrors
import cn.x.ac.kteasy.core.account.DeptTreeResolver
import cn.x.ac.kteasy.core.account.TreePaths
import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 部门树治理服务（步骤卡 M2a-01 单元③）：建节点、移动子树、子树 id 集合查询。
 *
 * **边界**（卡面要点 5／M2a-06 要点 3）：
 * - 治理面写**不进** `write()` 通道——部门是元数据治理对象，不是业务记录；本类直接走 `NamedParameterJdbcTemplate`。
 * - **不含 HTTP 端点**（归 M2a-06）；**不含用户/角色**（归 M2a-02/03）。
 * - 渲染成 SQL 的子树前缀查询归【M2b-04】，本类零 LIKE、零 `EXISTS`——`subtreeIds` 只把整树边集喂给
 *   [DeptTreeResolver] 纯函数，返回内存 id 集合。
 *
 * **path 物化物化口径**：段宽/分隔符/层级上限全部走 [TreePaths]（唯一真源），本类不出现 `"/"` 或 `padStart`。
 * 移动子树时同事务内重算该子树全部后代的 path：先把改后的边集喂进 [DeptTreeResolver.validateForest]
 * （成环/悬空父/超深即抛 420、无半棵树入库），再用 [DeptTreeResolver.subtree] 圈出受影响节点集合，
 * 逐节点调 [DeptTreeResolver.pathOf] 重算——**不递归查库**（深度 ≤4，子树规模小）。
 *
 * **留桩且不静默**：改部门后「业务记录 `owner_dept` 级联重算」归【M2d-02】异步作业，本类只在 [afterDeptChanged]
 * 处留一个钩子位，不碰任何业务记录。
 */
@Service
class DeptService(
    private val jdbc: NamedParameterJdbcTemplate,
    provider: SchemaProvider,
    private val tx: TransactionTemplate,
) {
    private val table = provider.namespace.qualified(LogicalArea.METADATA, "md_dept")

    // ---------- 命令载体 / 视图 ----------

    data class DeptCreateCmd(
        val parentId: String? = null,
        val code: String? = null,
        val name: String,
        val seq: Int = 1,
        val leaderUserId: String? = null,
        val enabled: Boolean = true,
    )

    data class DeptRow(
        val id: String,
        val parentId: String?,
        val code: String?,
        val name: String,
        val path: String,
        val seq: Int,
        val leaderUserId: String?,
        val enabled: Boolean,
    )

    private val rowMapper =
        RowMapper { rs, _ ->
            DeptRow(
                id = rs.getString("id"),
                parentId = rs.getString("parent_id"),
                code = rs.getString("code"),
                name = rs.getString("name"),
                path = rs.getString("path"),
                seq = rs.getInt("seq"),
                leaderUserId = rs.getString("leader_user_id"),
                enabled = rs.getBoolean("enabled"),
            )
        }

    // ---------- 读侧 ----------

    fun findById(id: String): DeptRow? = jdbc.query("SELECT * FROM $table WHERE id = :id", mapOf("id" to id), rowMapper).firstOrNull()

    fun requireById(id: String): DeptRow = findById(id) ?: throw DeptTreeErrors.unknownNode(id)

    /**
     * 取 [rootId] 的子树 id 集合（**含根自身**、id 升序、停用节点也计入——P23）。
     *
     * 本方法**零 SQL 渲染**：把整树边集从库里拉出来喂给 [DeptTreeResolver]。子树前缀 SQL 归【M2b-04】。
     */
    fun subtreeIds(rootId: String): List<String> {
        val edges = loadEdges()
        return DeptTreeResolver.subtree(edges, rootId)
    }

    // ---------- 写侧（全部包在事务里） ----------

    fun create(cmd: DeptCreateCmd): DeptRow =
        tx.execute {
            val id = Ulid.next()
            // 1. 父存在性（parentId 为空＝根节点，不必查）
            val parentPath =
                cmd.parentId?.let { pid ->
                    requireById(pid).path
                }
            // 2. 整树边集 + 新节点边，结构校验（环/悬空/超深）
            val edges = loadEdges() + DeptEdge(id = id, parentId = cmd.parentId)
            DeptTreeResolver.validateForest(edges)
            // 3. 物化 path：根＝单段；非根＝父 path + 本段
            val path =
                if (cmd.parentId == null) {
                    TreePaths.segment(cmd.seq)
                } else {
                    parentPath + TreePaths.SEPARATOR + TreePaths.segment(cmd.seq)
                }
            // 4. code 唯一预检（DB 约束兜底；先抛人话 420，不让调用方看 DuplicateKey）
            cmd.code?.let { c ->
                val dup =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $table WHERE code = :c",
                        mapOf("c" to c),
                        Int::class.java,
                    ) ?: 0
                if (dup > 0) {
                    throw KnownKteasyException(
                        ApiError.BUSINESS_RULE,
                        "部门编码 [$c] 已存在",
                        mapOf("error_id" to "DEPT_CODE_DUPLICATED"),
                    )
                }
            }
            jdbc.update(
                """
                INSERT INTO $table (id, parent_id, code, name, path, seq, leader_user_id, enabled)
                VALUES (:id, :parent_id, :code, :name, :path, :seq, :leader_user_id, :enabled)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("parent_id", cmd.parentId)
                    .addValue("code", cmd.code)
                    .addValue("name", cmd.name)
                    .addValue("path", path)
                    .addValue("seq", cmd.seq)
                    .addValue("leader_user_id", cmd.leaderUserId)
                    .addValue("enabled", cmd.enabled),
            )
            afterDeptChanged(setOf(id))
            requireById(id)
        } ?: throw KnownKteasyException(ApiError.INTERNAL, "create 部门事务未返回结果")

    /**
     * 移动节点 [id] 到新父 [newParentId]（null＝挂到根）。同事务内：
     * 1. 改后边集喂 [DeptTreeResolver.validateForest]——把节点挂到自己后代下成环即 420，无半棵树入库；
     * 2. [DeptTreeResolver.subtree] 圈出受影响节点；
     * 3. 逐节点 [DeptTreeResolver.pathOf] 重算 path，批量 UPDATE。
     */
    fun move(
        id: String,
        newParentId: String?,
    ): DeptRow =
        tx.execute {
            val rows = loadRows()
            val existing = rows.firstOrNull { it.id == id } ?: throw DeptTreeErrors.unknownNode(id)
            if (existing.parentId == newParentId) {
                // 幂等：父未变，直接返回当前行，不重算 path（避免无意义写）
                return@execute requireById(existing.id)
            }
            // 1. 改后边集：把 id 的 parent 换成 newParentId
            val edges =
                rows.map { DeptEdge(it.id, it.parentId) }.map { e ->
                    if (e.id == id) e.copy(parentId = newParentId) else e
                }
            DeptTreeResolver.validateForest(edges)
            // 2. 受影响子树（含 id 自身）
            val subtree = DeptTreeResolver.subtree(edges, id)
            // 3. seq 全表建索引（pathOf 要走祖先链；祖先可能在子树外）
            val seqMap = rows.associate { it.id to it.seq }
            // 4. 逐节点算新 path，批量回写
            for (nodeId in subtree) {
                val newPath = DeptTreeResolver.pathOf(edges, nodeId, seqMap)
                jdbc.update(
                    "UPDATE $table SET path = :p WHERE id = :id",
                    mapOf("p" to newPath, "id" to nodeId),
                )
            }
            // 5. 父指针本身更新（上面只改了 path，parent_id 要单独 UPDATE）
            jdbc.update(
                "UPDATE $table SET parent_id = :pid WHERE id = :id",
                mapOf("pid" to newParentId, "id" to id),
            )
            afterDeptChanged(subtree.toSet())
            requireById(id)
        } ?: throw KnownKteasyException(ApiError.INTERNAL, "move 部门事务未返回结果")

    // ---------- 内部 ----------

    private data class Snapshot(
        val id: String,
        val parentId: String?,
        val seq: Int,
    )

    private fun loadEdges(): List<DeptEdge> =
        jdbc.query(
            "SELECT id, parent_id FROM $table",
            emptyMap<String, Any>(),
            RowMapper { rs, _ -> DeptEdge(rs.getString("id"), rs.getString("parent_id")) },
        )

    private fun loadRows(): List<Snapshot> =
        jdbc.query(
            "SELECT id, parent_id, seq FROM $table",
            emptyMap<String, Any>(),
            RowMapper { rs, _ -> Snapshot(rs.getString("id"), rs.getString("parent_id"), rs.getInt("seq")) },
        )

    /**
     * 部门变更后的钩子位。
     *
     * **【M2d-02】改部门级联重算作业**：业务记录的 `owner_dept` 物化列要随部门移动/重建异步重算，
     * 复用 `schema_change_job` 形态。本卡只留钩子，不 enqueue 任何任务——M2d-02 开工时在这里挂 job 入队。
     *
     * 缓存失效（树读多写少、走 AsyncCache）也在这个钩子位消费——卡面要点 4 ⟨可逆⟩ 本卡先不接，
     * 只留一个失效回调形参位。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun afterDeptChanged(affectedIds: Set<String>) {
        // M2d-02：enqueue owner_dept 级联重算作业；缓存失效位也在此。
    }
}
