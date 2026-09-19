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
package cn.x.ac.kteasy.server.schema

import cn.x.ac.kteasy.core.schema.StepKind
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository

/** 作业账本一行（`schema_change_job`）的读取视图，供治理面 `GET /api/md/schema-jobs`。 */
data class SchemaJob(
    val id: Long,
    val objectId: String,
    val stepKind: StepKind,
    val seq: Int,
    val state: String,
    val attempts: Int,
    val checkpointJson: String?,
)

/**
 * `schema_change_job` 作业账本 DAO（V2 建表，M1-03 消费）。四态机 `PENDING→RUNNING→DONE|FAILED`
 * 的落地点：插入待发步、置态、累加重试次数、写 checkpoint 游标。
 *
 * 与 [MetadataRepository] 同一参数化口径（红线④），表名经 [SchemaProvider] 命名空间映射，无方言分支（红线⑤）。
 * 账本行是**可观测 + 断点续跑定位**用：执行器重启时据孤儿行找到受影响对象，再按元数据重算 diff 决定续做哪些步。
 */
@Repository
class SchemaJobRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    provider: SchemaProvider,
) {
    private val namespace = provider.namespace
    private val json = provider.json

    private val table: String = namespace.qualified(LogicalArea.METADATA, "md_schema_change_job")

    private val rowMapper =
        RowMapper { rs, _ ->
            SchemaJob(
                id = rs.getLong("id"),
                objectId = rs.getString("object_id"),
                stepKind = StepKind.valueOf(rs.getString("step_kind")),
                seq = rs.getInt("seq"),
                state = rs.getString("state"),
                attempts = rs.getInt("attempts"),
                checkpointJson = rs.getString("checkpoint_json"),
            )
        }

    /** 插入一个待发步，返回自增主键。 */
    fun insert(
        objectId: String,
        kind: StepKind,
        seq: Int,
    ): Long {
        val sql =
            "INSERT INTO $table (object_id, step_kind, seq, state, attempts) VALUES (:oid, :kind, :seq, 'PENDING', 0)"
        val keys = GeneratedKeyHolder()
        jdbc.update(sql, MapSqlParameterSource().addValue("oid", objectId).addValue("kind", kind.name).addValue("seq", seq), keys, arrayOf("id"))
        // 自增键：首选驱动回传；取不到时按 (object_id, seq) 最新行回查（PG/MySQL 驱动差异），绝不因此中断作业。
        val id: Number? =
            keys.key
                ?: jdbc.queryForObject(
                    "SELECT MAX(id) FROM $table WHERE object_id = :oid AND seq = :seq",
                    mapOf("oid" to objectId, "seq" to seq),
                    Number::class.java,
                )
        return id?.toLong() ?: error("schema_change_job 无法定位自增 id")
    }

    fun markState(
        id: Long,
        state: String,
    ) {
        jdbc.update("UPDATE $table SET state = :s WHERE id = :id", mapOf("s" to state, "id" to id))
    }

    fun bumpAttempts(id: Long) {
        jdbc.update("UPDATE $table SET attempts = attempts + 1 WHERE id = :id", mapOf("id" to id))
    }

    /** 写断点游标（如 BACKFILL 的 lastId）；JSON 列写入方言差异经 [SchemaProvider.bindJson]。 */
    fun writeCheckpoint(
        id: Long,
        jsonText: String,
    ) {
        jdbc.update(
            "UPDATE $table SET checkpoint_json = ${json.bindJson("cp")} WHERE id = :id",
            mapOf("cp" to jsonText, "id" to id),
        )
    }

    fun findCheckpoint(id: Long): String? = jdbc.queryForList("SELECT checkpoint_json FROM $table WHERE id = :id", mapOf("id" to id), String::class.java).firstOrNull()

    /** 孤儿：state=RUNNING 的行涉及的**去重**对象 id（进程重启时优先重跑这些）。 */
    fun findOrphanObjectIds(): List<String> = jdbc.queryForList("SELECT DISTINCT object_id FROM $table WHERE state = 'RUNNING'", emptyMap<String, Any>(), String::class.java).filterNotNull()

    fun listAll(): List<SchemaJob> = jdbc.query("SELECT * FROM $table ORDER BY id", emptyMap<String, Any>(), rowMapper)

    fun listByObject(objectId: String): List<SchemaJob> = jdbc.query("SELECT * FROM $table WHERE object_id = :oid ORDER BY id", mapOf("oid" to objectId), rowMapper)

    /** 入队一条待发步并写入其参数 JSON（物化作业据此在重启后精确续跑，不依赖元数据重推导）。 */
    fun insertPending(
        objectId: String,
        kind: StepKind,
        seq: Int,
        paramsJson: String?,
    ): Long {
        val id = insert(objectId, kind, seq)
        if (paramsJson != null) writeCheckpoint(id, paramsJson)
        return id
    }

    /** 该对象全部未到终态（state<>DONE）的步，按 (seq, id) 升序——供执行器回放未完成的物理化步。 */
    fun listUnfinishedByObject(objectId: String): List<SchemaJob> =
        jdbc.query(
            "SELECT * FROM $table WHERE object_id = :oid AND state <> 'DONE' ORDER BY seq, id",
            mapOf("oid" to objectId),
            rowMapper,
        )

    /** 该对象是否仍有未到终态（PENDING/RUNNING/FAILED）的步。 */
    fun hasUnfinished(objectId: String): Boolean =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM $table WHERE object_id = :oid AND state IN ('PENDING','RUNNING','FAILED')",
            mapOf("oid" to objectId),
            Int::class.java,
        ) ?: 0 > 0
}
