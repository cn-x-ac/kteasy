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

import cn.x.ac.kteasy.core.kernel.MetadataChangedEvent
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.DiffInput
import cn.x.ac.kteasy.core.schema.MaterializedState
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.core.schema.SchemaStep
import cn.x.ac.kteasy.core.schema.StepOp
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.Fragment
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.LockKey
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import cn.x.ac.kteasy.server.md.MetadataRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import javax.sql.DataSource

/**
 * 物化作业执行器（步骤卡 M1-03）。**单实例串行队列**（跨对象并行度 1）+ **同对象命名锁互斥**：
 * 命名锁（PG advisory 锁 / MySQL 会话具名锁）是**会话级**的，故整段处理（加锁 → 探测 → DDL → 解锁）钉在**同一物理连接**上，
 * 否则跨 Hikari 连接解锁会返回假失败（§E33）。DDL 一律在 autocommit 连接上执行——**禁事务包裹非事务步**
 * （MySQL DDL 隐式提交，包了反而谎报原子性；PG 的在线并发建索引语句更是硬约束）。
 *
 * 幂等双层：① diff 只算语义增量；② 每步执行前 `precheck` 探实际物理态，已生效则跳过（覆盖崩溃重入）。
 * 步失败指数退避重试 3 次后转 `FAILED`（挂起）+ 告警日志并中断该对象余下步骤；修好后经启动孤儿扫描或
 * 手工 `resume` 重算 diff 续跑（已 DONE 的步 precheck 命中即跳）。
 */
@Service
class SchemaJobExecutor(
    private val dataSource: DataSource,
    private val provider: SchemaProvider,
    private val jobRepo: SchemaJobRepository,
    private val meta: MetadataRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val queue = LinkedBlockingQueue<String>()
    private val queued = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    @PostConstruct
    fun start() {
        running = true
        worker =
            Thread({ drain() }, "kteasy-schema-executor").apply {
                isDaemon = true
                start()
            }
        // 断点续跑：重启时把仍有 RUNNING 孤儿的对象重新入队（kill -9 后从 checkpoint/precheck 续）。
        runCatching { jobRepo.findOrphanObjectIds().forEach { submit(it) } }
    }

    @PreDestroy
    fun stop() {
        running = false
        worker?.interrupt()
    }

    /** 订阅元数据变更（**事务提交后**才入队，§8-⑦ 防脏读；回滚不触发）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMetadataChanged(event: MetadataChangedEvent) {
        val api = event.objectApi ?: return
        runCatching { meta.findObjectByApi(api)?.let { submit(it.id) } }
    }

    /** 提交一个对象的物化请求（同对象在队列内合并去重）。 */
    fun submit(objectId: String) {
        if (queued.putIfAbsent(objectId, true) == null) {
            queue.offer(objectId)
        }
    }

    private fun drain() {
        while (running) {
            val objectId =
                try {
                    queue.take()
                } catch (e: InterruptedException) {
                    if (!running) return else continue
                }
            try {
                process(objectId)
            } catch (e: Exception) {
                log.error("物化作业处理对象异常 objectId={}", objectId, e)
            } finally {
                queued.remove(objectId)
            }
        }
    }

    /** 处理一个对象：单连接上加锁→探测物理态→重算 diff→逐步执行→解锁。 */
    private fun process(objectId: String) {
        val obj = meta.findObjectById(objectId) ?: return
        val fields = meta.listFieldsByObjectId(obj.id)
        val apiById = meta.listObjects().associate { it.id to it.apiName }
        val parentApi = obj.parentObjectId?.let { apiById[it] }

        dataSource.connection.use { conn ->
            conn.autoCommit = true
            val template = NamedParameterJdbcTemplate(SingleConnectionDataSource(conn, true))
            val lockKey = LockKey("KTEASY:SCHEMA:${obj.apiName}")
            runQueryOn(template, provider.lock.lock(lockKey)) // 阻塞式命名锁，与会话同生命周期
            try {
                val actual = probeActual(template, obj, fields)
                val steps = SchemaDiff.diff(DiffInput(obj, fields, apiById, parentApi, actual))
                log.info("物化开始 object={} 步数={}", obj.apiName, steps.size)
                for (step in steps) {
                    if (!runStep(template, obj.id, step)) {
                        log.warn("对象 {} 物化在 seq={} 挂起，中断余下步骤", obj.apiName, step.seq)
                        break
                    }
                }
                // 物理化回放：只跑「已显式提交且未到终态」的账本步（参数存于 checkpoint_json），与结构 diff 正交；
                // kill -9 重启后由 @PostConstruct 孤儿扫描重新入队，从这里按 checkpoint 续跑到终态。
                runPersistedPhysicalize(template, obj)
            } finally {
                runQueryOn(template, provider.lock.unlock(lockKey))
            }
        }
    }

    private fun probeActual(
        template: NamedParameterJdbcTemplate,
        obj: MdObject,
        fields: List<cn.x.ac.kteasy.core.meta.MdField>,
    ): MaterializedState {
        val exists = countOn(template, provider.introspection.tableExists(LogicalArea.ENTITY, obj.apiName)) > 0
        val columns =
            if (exists) {
                val frag = provider.introspection.listColumns(LogicalArea.ENTITY, obj.apiName)
                template.queryForList(frag.sql, MapSqlParameterSource(frag.params), String::class.java).filterNotNull().toSet()
            } else {
                emptySet()
            }
        val relations =
            fields
                .filter { it.enabled && it.storageKind == StorageKind.N2N }
                .map { SchemaDiff.relationTableLogicalName(obj, it) }
                .filter { countOn(template, provider.introspection.tableExists(LogicalArea.RELATION, it)) > 0 }
                .toSet()
        return MaterializedState(exists, columns, relations)
    }

    /** 提交一条物理化作业：把 planPhysicalize 产出的有序步连同其参数落账本（PENDING），再入队由 worker 执行/续跑。 */
    fun submitPhysicalization(
        objectId: String,
        steps: List<SchemaStep>,
    ) {
        steps.forEach { s -> jobRepo.insertPending(objectId, s.kind, s.seq, jsonFromMap(encodeParams(s.op))) }
        submit(objectId)
    }

    /** 执行一步（diff 路径）：插入新账本行后交 [attempt]。返回 false＝该步挂起。 */
    private fun runStep(
        template: NamedParameterJdbcTemplate,
        objectId: String,
        step: SchemaStep,
    ): Boolean {
        val jobId = jobRepo.insert(objectId, step.kind, step.seq)
        return attempt(template, jobId, step)
    }

    /**
     * 回放该对象**已提交但未到终态**的物理化步（参数存于 checkpoint_json）：按 (seq,id) 顺序执行，
     * 任一步挂起即中断余下步。kill -9 重启后由 @PostConstruct 孤儿扫描重新入队，从这里从 checkpoint 续跑。
     */
    private fun runPersistedPhysicalize(
        template: NamedParameterJdbcTemplate,
        obj: MdObject,
    ) {
        for (row in jobRepo.listUnfinishedByObject(obj.id)) {
            if (row.stepKind !in PHYSICALIZE_KINDS) continue
            val op = decodeParams(row.stepKind, parseFlat(row.checkpointJson)) ?: continue
            val step = SchemaStep(obj.id, row.stepKind, row.seq, op)
            if (!attempt(template, row.id, step)) {
                log.warn("对象 {} 物理化在 seq={} 挂起，中断余下步", obj.apiName, row.seq)
                break
            }
        }
    }

    /**
     * 单步执行核心：precheck 命中即幂等跳过置 DONE；否则重试至多 [MAX_RETRY] 次（指数退避），
     * 每失败把异常并合进 checkpoint（不覆参数）、置 FAILED；成功置 DONE。返回 false＝挂起。
     */
    private fun attempt(
        template: NamedParameterJdbcTemplate,
        jobId: Long,
        step: SchemaStep,
    ): Boolean {
        if (precheck(template, step)) {
            jobRepo.markState(jobId, "DONE")
            log.info("跳过（已生效）job={} kind={} seq={}", jobId, step.kind, step.seq)
            return true
        }
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            jobRepo.bumpAttempts(jobId)
            jobRepo.markState(jobId, "RUNNING")
            val t0 = System.nanoTime()
            try {
                doStep(template, step, jobId)
                if (!postcheck(template, step)) {
                    throw IllegalStateException("postcheck 未确认：kind=${step.kind} seq=${step.seq}")
                }
                jobRepo.markState(jobId, "DONE")
                log.info("完成 job={} kind={} seq={} 尝试={} 耗时ms={}", jobId, step.kind, step.seq, attempt, (System.nanoTime() - t0) / 1_000_000)
                return true
            } catch (e: Exception) {
                log.warn("步骤失败待重试 job={} kind={} seq={} 第{}次：{}", jobId, step.kind, step.seq, attempt, e.message)
                mergeCheckpointField(jobId, "error", e.message?.take(400)?.replace("\"", "'") ?: "")
                jobRepo.markState(jobId, "FAILED")
                if (attempt < MAX_RETRY) {
                    runCatching { Thread.sleep(BACKOFF_BASE_MS * (1L shl (attempt - 1))) }
                }
            }
        }
        log.error("步骤挂起 job={} kind={} seq={}（重试 {} 次仍失败，转人工位）", jobId, step.kind, step.seq, MAX_RETRY)
        return false
    }

    private fun precheck(
        template: NamedParameterJdbcTemplate,
        step: SchemaStep,
    ): Boolean =
        when (val op = step.op) {
            is StepOp.CreateTable -> existsOn(template, provider.introspection.tableExists(LogicalArea.ENTITY, op.spec.name))

            is StepOp.CreateRelationTable -> existsOn(template, provider.introspection.tableExists(LogicalArea.RELATION, op.spec.name))

            is StepOp.AddFkColumn -> existsOn(template, provider.introspection.columnExists(op.hostArea, op.hostTable, op.column.name))

            is StepOp.DropColumn -> !existsOn(template, provider.introspection.columnExists(op.hostArea, op.hostTable, op.column))

            is StepOp.DropTable -> !existsOn(template, provider.introspection.tableExists(op.area, op.name))

            is StepOp.AddVirtualColumn -> existsOn(template, provider.introspection.columnExists(op.hostArea, op.hostTable, op.column.name))

            is StepOp.AddIndexExpr -> existsOn(template, provider.introspection.indexExists(op.hostArea, op.hostTable, op.indexName))

            is StepOp.DropIndex -> !existsOn(template, provider.introspection.indexExists(op.hostArea, op.hostTable, op.indexName))

            // 回填/清 key 依 last_id 游标天然幂等；读切换每次核验，不做结构跳过。
            else -> false
        }

    private fun postcheck(
        template: NamedParameterJdbcTemplate,
        step: SchemaStep,
    ): Boolean =
        when (val op = step.op) {
            is StepOp.CreateTable -> existsOn(template, provider.introspection.tableExists(LogicalArea.ENTITY, op.spec.name))
            is StepOp.CreateRelationTable -> existsOn(template, provider.introspection.tableExists(LogicalArea.RELATION, op.spec.name))
            is StepOp.AddFkColumn -> existsOn(template, provider.introspection.columnExists(op.hostArea, op.hostTable, op.column.name))
            is StepOp.DropColumn -> !existsOn(template, provider.introspection.columnExists(op.hostArea, op.hostTable, op.column))
            is StepOp.DropTable -> !existsOn(template, provider.introspection.tableExists(op.area, op.name))
            is StepOp.AddVirtualColumn -> existsOn(template, provider.introspection.columnExists(op.hostArea, op.hostTable, op.column.name))
            is StepOp.AddIndexExpr -> existsOn(template, provider.introspection.indexExists(op.hostArea, op.hostTable, op.indexName))
            is StepOp.DropIndex -> !existsOn(template, provider.introspection.indexExists(op.hostArea, op.hostTable, op.indexName))
            is StepOp.BackfillBatch -> !anyExtKeyUnmigrated(template, op.hostArea, op.hostTable, op.targetColumn, op.expressionSourceColumn)
            is StepOp.SwitchRead -> true
            is StepOp.CleanExtKey -> !anyExtKeyRemaining(template, op.hostArea, op.hostTable, op.targetColumn, op.extColumn, JsonPath(op.keyPath))
        }

    /** 发射一步的全部 DDL/DML。MySQL 加列候选在此逐条探测回退（INSTANT→INPLACE），并日志走了哪条。 */
    private fun doStep(
        template: NamedParameterJdbcTemplate,
        step: SchemaStep,
        jobId: Long,
    ) {
        when (val op = step.op) {
            is StepOp.CreateTable -> {
                provider.table.createTable(op.spec).forEach { execDdl(template, it.sql) }
            }

            is StepOp.CreateRelationTable -> {
                provider.table.createTable(op.spec).forEach { execDdl(template, it.sql) }
            }

            is StepOp.AddFkColumn -> {
                val host = provider.namespace.qualified(op.hostArea, op.hostTable)
                addColumnWithFallback(template, provider.table.addColumn(host, op.column))
                op.companion?.let { addColumnWithFallback(template, provider.table.addColumn(host, it)) }
                op.index?.let { provider.index.createIndex(host, it.columns, it.name, it.unique, online = false).forEach { s -> execDdl(template, s.sql) } }
                op.foreignKey?.let { execDdl(template, provider.table.addForeignKey(it).sql) }
            }

            is StepOp.DropColumn -> {
                execDdl(template, provider.column.dropColumn(provider.namespace.qualified(op.hostArea, op.hostTable), op.column).sql)
            }

            is StepOp.DropTable -> {
                execDdl(template, provider.table.dropTable(op.area, op.name).sql)
            }

            is StepOp.AddVirtualColumn -> {
                val host = provider.namespace.qualified(op.hostArea, op.hostTable)
                addColumnWithFallback(template, provider.column.addNullableColumn(host, op.column.name, valueCastOf(op.column.type)))
            }

            is StepOp.BackfillBatch -> {
                backfillBatch(template, jobId, op)
            }

            is StepOp.AddIndexExpr -> {
                val host = provider.namespace.qualified(op.hostArea, op.hostTable)
                val expr = provider.json.extractTyped(op.extColumn, JsonPath(op.keyPath), op.cast).sql
                provider.index.createExpressionIndex(host, expr, op.indexName, op.online).forEach { execDdl(template, it.sql) }
            }

            is StepOp.DropIndex -> {
                execDdl(template, provider.index.dropIndex(provider.namespace.qualified(op.hostArea, op.hostTable), op.indexName, op.online).sql)
            }

            is StepOp.SwitchRead -> {
                meta.setFieldStorageKind(op.fieldId, StorageKind.COLUMN)
            }

            is StepOp.CleanExtKey -> {
                cleanExtKey(template, jobId, op)
            }
        }
    }

    // ---------- 物理化：分批回填 / 清 ext key（按 id 游标 + last_id 断点，全方言经 SchemaProvider） ----------

    private fun backfillBatch(
        template: NamedParameterJdbcTemplate,
        jobId: Long,
        op: StepOp.BackfillBatch,
    ) {
        val host = provider.namespace.qualified(op.hostArea, op.hostTable)
        val keyPath = JsonPath(listOf(op.expressionSourceColumn))
        val exists = provider.json.predicateExists("ext", keyPath).sql
        val extract = provider.json.extractTyped("ext", keyPath, op.cast).sql
        var lastId = readLastIdStr(jobId)
        while (true) {
            val ids = queryIdsAfter(template, host, "$exists AND id > :lastId", lastId, op.batchSize)
            if (ids.isEmpty()) break
            template.update(
                "UPDATE $host SET ${op.targetColumn} = $extract WHERE id IN (:ids)",
                mapOf("ids" to ids),
            )
            lastId = ids.last()
            mergeCheckpointField(jobId, "lastId", lastId)
        }
    }

    private fun cleanExtKey(
        template: NamedParameterJdbcTemplate,
        jobId: Long,
        op: StepOp.CleanExtKey,
    ) {
        val host = provider.namespace.qualified(op.hostArea, op.hostTable)
        val keyPath = JsonPath(op.keyPath)
        val exists = provider.json.predicateExists(op.extColumn, keyPath).sql
        val remove = provider.json.removeKey(op.extColumn, keyPath).sql
        // 数据不丢护栏：仅清「目标列已回填非空」的行——绝不删仍只在 ext、列未落值的数据。
        val where = "$exists AND ${op.targetColumn} IS NOT NULL AND id > :lastId"
        var lastId = readLastIdStr(jobId)
        while (true) {
            val ids = queryIdsAfter(template, host, where, lastId, op.batchSize)
            if (ids.isEmpty()) break
            template.update("UPDATE $host SET ${op.extColumn} = $remove WHERE id IN (:ids)", mapOf("ids" to ids))
            lastId = ids.last()
            mergeCheckpointField(jobId, "lastId", lastId)
        }
    }

    private fun queryIdsAfter(
        template: NamedParameterJdbcTemplate,
        host: String,
        where: String,
        lastId: String,
        limit: Int,
    ): List<String> =
        template
            .queryForList(
                "SELECT id FROM $host WHERE $where ORDER BY id LIMIT :limit",
                mapOf("lastId" to lastId, "limit" to limit),
                String::class.java,
            ).filterNotNull()

    /** 是否仍有「ext 带该键但目标列尚空」的行（回填未完成信号）。 */
    private fun anyExtKeyUnmigrated(
        template: NamedParameterJdbcTemplate,
        area: LogicalArea,
        hostTable: String,
        targetColumn: String,
        sourceColumn: String,
    ): Boolean {
        val host = provider.namespace.qualified(area, hostTable)
        val exists = provider.json.predicateExists("ext", JsonPath(listOf(sourceColumn))).sql
        val frag = Fragment("SELECT COUNT(*) FROM $host WHERE $exists AND $targetColumn IS NULL")
        return countOn(template, frag) > 0
    }

    /** 是否仍有「ext 带该键」的行（清 key 未完成信号；已回填后按键存在性判定）。 */
    private fun anyExtKeyRemaining(
        template: NamedParameterJdbcTemplate,
        area: LogicalArea,
        hostTable: String,
        @Suppress("UNUSED_PARAMETER") targetColumn: String,
        extColumn: String,
        keyPath: JsonPath,
    ): Boolean {
        val host = provider.namespace.qualified(area, hostTable)
        val exists = provider.json.predicateExists(extColumn, keyPath).sql
        return countOn(template, Fragment("SELECT COUNT(*) FROM $host WHERE $exists")) > 0
    }

    /**
     * 加列候选逐条探测回退：PG 单条即成；MySQL 首选 INSTANT、失败退 INPLACE，并 INFO 记录实际走了哪条
     * （M1-03 GWT「capability 判定证据」）。全部候选失败才抛（计一次重试）。
     */
    private fun addColumnWithFallback(
        template: NamedParameterJdbcTemplate,
        candidates: List<cn.x.ac.kteasy.core.schema.dialect.DdlStatement>,
    ) {
        if (candidates.isEmpty()) return
        var lastError: Exception? = null
        for (c in candidates) {
            try {
                execDdl(template, c.sql)
                log.info("加列实际走：{}", c.sql)
                return
            } catch (e: Exception) {
                lastError = e
                log.info("加列候选失败（{}），试下一条", e.message)
            }
        }
        throw lastError!!
    }

    private fun execDdl(
        template: NamedParameterJdbcTemplate,
        sql: String,
    ) {
        template.update(sql, emptyMap<String, Any>())
    }

    // ---------- 小工具（单连接模板上的探测/执行） ----------

    private fun runQueryOn(
        template: NamedParameterJdbcTemplate,
        frag: Fragment,
    ) {
        template.queryForList(frag.sql, MapSqlParameterSource(frag.params))
    }

    private fun countOn(
        template: NamedParameterJdbcTemplate,
        frag: Fragment,
    ): Long = template.queryForObject(frag.sql, MapSqlParameterSource(frag.params), Long::class.java) ?: 0L

    private fun existsOn(
        template: NamedParameterJdbcTemplate,
        frag: Fragment,
    ): Boolean = countOn(template, frag) > 0

    // ---------- checkpoint 扁平参数编解码（受控字符串字段，无 Jackson、不覆已存参数） ----------

    private fun encodeParams(op: StepOp): Map<String, String> =
        when (op) {
            is StepOp.AddVirtualColumn -> {
                linkedMapOf(
                    "area" to op.hostArea.name,
                    "host" to op.hostTable,
                    "colName" to op.column.name,
                    "colType" to op.column.type.name,
                    "colLen" to (op.column.length?.toString() ?: ""),
                )
            }

            is StepOp.BackfillBatch -> {
                linkedMapOf(
                    "area" to op.hostArea.name,
                    "host" to op.hostTable,
                    "target" to op.targetColumn,
                    "src" to op.expressionSourceColumn,
                    "cast" to op.cast.name,
                    "batch" to op.batchSize.toString(),
                    "lastId" to "",
                )
            }

            is StepOp.AddIndexExpr -> {
                linkedMapOf(
                    "area" to op.hostArea.name,
                    "host" to op.hostTable,
                    "idx" to op.indexName,
                    "ext" to op.extColumn,
                    "key" to op.keyPath.joinToString("."),
                    "cast" to op.cast.name,
                    "online" to op.online.toString(),
                )
            }

            is StepOp.DropIndex -> {
                linkedMapOf("area" to op.hostArea.name, "host" to op.hostTable, "idx" to op.indexName, "online" to op.online.toString())
            }

            is StepOp.SwitchRead -> {
                linkedMapOf("fieldId" to op.fieldId, "host" to op.hostTable, "column" to op.column)
            }

            is StepOp.CleanExtKey -> {
                linkedMapOf(
                    "area" to op.hostArea.name,
                    "host" to op.hostTable,
                    "target" to op.targetColumn,
                    "ext" to op.extColumn,
                    "key" to op.keyPath.joinToString("."),
                    "batch" to op.batchSize.toString(),
                    "lastId" to "",
                )
            }

            else -> {
                emptyMap()
            }
        }

    private fun decodeParams(
        kind: cn.x.ac.kteasy.core.schema.StepKind,
        m: Map<String, String>,
    ): StepOp? {
        if (kind == cn.x.ac.kteasy.core.schema.StepKind.SWITCH_READ) {
            return StepOp.SwitchRead(m.getValue("fieldId"), m.getValue("host"), m.getValue("column"))
        }
        val area = m["area"]?.let { runCatching { LogicalArea.valueOf(it) }.getOrNull() } ?: return null
        return when (kind) {
            cn.x.ac.kteasy.core.schema.StepKind.ADD_VIRTUAL_COLUMN -> {
                val len = m["colLen"]?.toIntOrNull()
                StepOp.AddVirtualColumn(
                    area,
                    m.getValue("host"),
                    PhysicalColumn(m.getValue("colName"), ColumnType.valueOf(m.getValue("colType")), len),
                    m.getValue("colName"),
                    false,
                )
            }

            cn.x.ac.kteasy.core.schema.StepKind.BACKFILL_BATCH -> {
                StepOp.BackfillBatch(area, m.getValue("host"), m.getValue("target"), m.getValue("src"), ValueCast.valueOf(m.getValue("cast")), m.getValue("batch").toInt())
            }

            cn.x.ac.kteasy.core.schema.StepKind.ADD_INDEX_EXPR -> {
                StepOp.AddIndexExpr(area, m.getValue("host"), m.getValue("idx"), m.getValue("ext"), m.getValue("key").keySegments(), ValueCast.valueOf(m.getValue("cast")), m.getValue("online").toBoolean())
            }

            cn.x.ac.kteasy.core.schema.StepKind.DROP_INDEX -> {
                StepOp.DropIndex(area, m.getValue("host"), m.getValue("idx"), m.getValue("online").toBoolean())
            }

            cn.x.ac.kteasy.core.schema.StepKind.CLEAN_EXT_KEY -> {
                StepOp.CleanExtKey(area, m.getValue("host"), m.getValue("target"), m.getValue("ext"), m.getValue("key").keySegments(), m.getValue("batch").toInt())
            }

            else -> {
                null
            }
        }
    }

    private fun String.keySegments(): List<String> = if (isBlank()) emptyList() else split(".")

    private fun jsonFromMap(m: Map<String, String>): String = m.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${sanitize(it.value)}\"" }

    private fun sanitize(v: String): String = v.replace("\\", "").replace("\"", "'")

    private val pairRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")

    private fun parseFlat(json: String?): Map<String, String> =
        if (json.isNullOrBlank()) {
            mutableMapOf()
        } else {
            pairRegex.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()
        }

    private fun readLastIdStr(jobId: Long): String = parseFlat(jobRepo.findCheckpoint(jobId))["lastId"] ?: ""

    /** 把单字段并合进既有 checkpoint（读-改-写），绝不覆掉已存的步参数/进度。 */
    private fun mergeCheckpointField(
        jobId: Long,
        key: String,
        value: String,
    ) {
        val m = parseFlat(jobRepo.findCheckpoint(jobId)).toMutableMap()
        m[key] = sanitize(value)
        jobRepo.writeCheckpoint(jobId, jsonFromMap(m))
    }

    private fun valueCastOf(type: ColumnType): ValueCast =
        when (type) {
            ColumnType.VARCHAR, ColumnType.TEXT, ColumnType.JSON -> ValueCast.TEXT
            ColumnType.BIGINT, ColumnType.INTEGER -> ValueCast.LONG
            ColumnType.BOOLEAN -> ValueCast.BOOL
            ColumnType.TIMESTAMP -> ValueCast.TIMESTAMP
        }

    companion object {
        private const val MAX_RETRY = 3
        private const val BACKOFF_BASE_MS = 50L
        private val PHYSICALIZE_KINDS =
            setOf(
                cn.x.ac.kteasy.core.schema.StepKind.ADD_VIRTUAL_COLUMN,
                cn.x.ac.kteasy.core.schema.StepKind.BACKFILL_BATCH,
                cn.x.ac.kteasy.core.schema.StepKind.ADD_INDEX_EXPR,
                cn.x.ac.kteasy.core.schema.StepKind.DROP_INDEX,
                cn.x.ac.kteasy.core.schema.StepKind.SWITCH_READ,
                cn.x.ac.kteasy.core.schema.StepKind.CLEAN_EXT_KEY,
            )
    }
}
