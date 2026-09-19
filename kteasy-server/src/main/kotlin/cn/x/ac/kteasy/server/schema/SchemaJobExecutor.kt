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
import cn.x.ac.kteasy.core.schema.dialect.Fragment
import cn.x.ac.kteasy.core.schema.dialect.LockKey
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
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

    /** 执行一步；返回 false 表示该步重试耗尽挂起，调用方应中断本对象余下步骤。 */
    private fun runStep(
        template: NamedParameterJdbcTemplate,
        objectId: String,
        step: SchemaStep,
    ): Boolean {
        val jobId = jobRepo.insert(objectId, step.kind, step.seq)
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
                doStep(template, step)
                if (!postcheck(template, step)) {
                    throw IllegalStateException("postcheck 未确认：kind=${step.kind} seq=${step.seq}")
                }
                jobRepo.markState(jobId, "DONE")
                log.info("完成 job={} kind={} seq={} 尝试={} 耗时ms={}", jobId, step.kind, step.seq, attempt, (System.nanoTime() - t0) / 1_000_000)
                return true
            } catch (e: Exception) {
                log.warn("步骤失败待重试 job={} kind={} seq={} 第{}次：{}", jobId, step.kind, step.seq, attempt, e.message)
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
            else -> false // 其余步型（物理化四步）Block E 接入，暂不做幂等跳过
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
            else -> true
        }

    /** 发射一步的全部 DDL。MySQL 加列候选在此逐条探测回退（INSTANT→INPLACE），并日志走了哪条。 */
    private fun doStep(
        template: NamedParameterJdbcTemplate,
        step: SchemaStep,
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

            else -> {
                error("未在本块接入的步型：${step.kind}（物理化四步归 Block E）")
            }
        }
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

    companion object {
        private const val MAX_RETRY = 3
        private const val BACKOFF_BASE_MS = 50L
    }
}
