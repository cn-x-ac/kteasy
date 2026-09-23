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
package cn.x.ac.kteasy.server.write

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LockKey
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.ExtCodec
import cn.x.ac.kteasy.core.write.InternalWriteSources
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.WriteCommittedEvent
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteErrors
import cn.x.ac.kteasy.core.write.WriteInput
import cn.x.ac.kteasy.core.write.WriteIntent
import cn.x.ac.kteasy.core.write.WriteKind
import cn.x.ac.kteasy.core.write.WriteLocks
import cn.x.ac.kteasy.core.write.WritePipeline
import cn.x.ac.kteasy.core.write.WritePlan
import cn.x.ac.kteasy.core.write.WriteResult
import cn.x.ac.kteasy.core.write.WriteSqlRenderer
import cn.x.ac.kteasy.server.md.MetadataGraphCache
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 通用写入通道的执行半区（图纸 04 阶段 8–12）。**全系统唯一写入口**：治理面以外的任何新建/更新/
 * 软删/恢复都必须走这里，M4 导入、M4-03 转换、M5 开放接口一律复用它（三铁律① 的物理保障）。
 *
 * 分工是刻意的：业务裁决（阶段 1–7）在 [WritePipeline] 里是纯函数，本类只做四件事——
 * 拿锁、读旧行、把 [WritePlan] 绑成一条语句、提交后发事件。可写错的地方因此被压到最小。
 *
 * **锁与事务必须在同一物理连接上**：命名锁是会话级的，Spring 事务期内 [NamedParameterJdbcTemplate]
 * 复用同一连接，锁、行锁、写入才会落在同一个 session；一旦哪天有人把写入挪进另一个线程/另一个
 * DataSource，锁就形同没锁（M1-03 执行器同款约束，故在此写明而不是藏在测试里）。
 */
@Service
class WriteService(
    private val cache: MetadataGraphCache,
    private val provider: SchemaProvider,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val pipeline: WritePipeline,
    private val renderer: WriteSqlRenderer,
    private val rows: RowValues,
    private val codec: ExtCodec,
    private val publisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 单条写（新建/更新/软删/恢复共用一个入口，靠 [WriteContext.intent] 与 recordId 区分性质）。 */
    fun write(
        ctx: WriteContext,
        draft: RecordDraft,
    ): WriteResult = tx.execute { doWrite(ctx, draft) } ?: throw KnownKteasyException(ApiError.INTERNAL, "写事务未返回结果")

    /**
     * 批量写＝循环单条、**各自事务**（卡面 §6 默认）。一条失败不回滚整批——
     * M4-02 导入的逐行状态可见性依赖这一点。
     */
    fun writeBatch(
        ctx: WriteContext,
        drafts: List<RecordDraft>,
    ): List<WriteResult> = drafts.map { write(ctx, it) }

    /** 内部 API：同事务多条（M4-03 转换的跨行原子段）。来源闸门＝[InternalWriteSources]，UI/OpenAPI 一律拒。 */
    fun writeAllInTx(
        ctx: WriteContext,
        drafts: List<RecordDraft>,
    ): List<WriteResult> {
        if (!InternalWriteSources.isAllowed(ctx.source)) {
            throw WriteErrors.forbidden("同事务批量写仅 SYSTEM/TRANSFORM/IMPORT 可用，当前来源 ${ctx.source.name}")
        }
        return drafts.map { doWrite(ctx, it) }
    }

    private fun doWrite(
        ctx: WriteContext,
        draft: RecordDraft,
    ): WriteResult {
        val (_, graph) = cache.graph(ctx.objectApi)
        val objectId = graph.objectMeta.id
        val key = ctx.recordId?.let { WriteLocks.of(objectId, it) }
        key?.let { acquire(it) }
        try {
            val existing = ctx.recordId?.let { id -> readForUpdate(ctx.objectApi, id)?.let { rows.toRow(it, graph.fields) } }
            val plan = pipeline.plan(WriteInput(graph, ctx, draft, existing))
            val affected = persist(ctx, plan)
            if (plan.kind == WriteKind.UPDATED && affected == 0) {
                throw WriteErrors.conflictRetry(plan.id, plan.expectedVersion, existing?.rowVersion ?: -1L)
            }
            publisher.publishEvent(
                WriteCommittedEvent(
                    eventId = Ulid.next(),
                    traceId = ctx.traceId,
                    objectApi = ctx.objectApi,
                    recordId = plan.id,
                    kind = plan.kind,
                    source = ctx.source,
                    actor = ctx.actor,
                    diff = plan.diff,
                ),
            )
            log.debug("写入完成 object={} id={} kind={} 变更数={} 版本={}", ctx.objectApi, plan.id, plan.kind, plan.diff.size, plan.rowVersionNext)
            return WriteResult(plan.id, plan.rowVersionNext, plan.kind, plan.diff, plan.warnings)
        } finally {
            key?.let { release(it) }
        }
    }

    private fun readForUpdate(
        objectApi: String,
        id: String,
    ): Map<String, Any?>? =
        renderer.selectForUpdate(objectApi, id).let { b ->
            jdbc.queryForList(b.sql, MapSqlParameterSource(b.params)).firstOrNull()
        }

    private fun acquire(
        key: LockKey,
    ) {
        val f = provider.lock.tryLock(key, LOCK_TIMEOUT_SECONDS)
        val got = truthy(jdbc.queryForList(f.sql, MapSqlParameterSource(f.params)))
        if (!got) throw WriteErrors.lockRetry(key.name, LOCK_TIMEOUT_SECONDS)
    }

    private fun release(
        key: LockKey,
    ) {
        runCatching {
            val f = provider.lock.unlock(key)
            jdbc.queryForList(f.sql, MapSqlParameterSource(f.params))
        }.onFailure { log.warn("写锁释放失败 key={}：{}", key.name, it.message) }
    }

    /**
     * 阶段 9 落库。新建＝INSERT（含 ext）；其余＝UPDATE + 版本条件（乐观并发）。
     *
     * 驱动异常在这里翻译成人话契约（红线④「禁裸 500」）：两库的外键/唯一违例文本完全不同，
     * 上层只认 `FK_VIOLATION` 与 `CONFLICT_RETRY` 两个符号名。翻译保守——只认已知的违例类型，
     * 其余照抛（不猜、不吞，让块 5 的 IT 与日志暴露真因）。
     */
    private fun persist(
        ctx: WriteContext,
        plan: WritePlan,
    ): Int {
        val columns = LinkedHashMap(plan.columnBindings)
        rows.extBinding(plan)?.let { columns["ext"] = it }
        val (sql, params) =
            if (plan.creating) {
                renderer.insert(ctx.objectApi, columns)
            } else {
                // 主键与创建侧字段不进 SET：created_at/created_by 一旦可变，审计就失去锚点
                val assignments = LinkedHashMap(columns).apply { listOf(SYSTEM_COL_ID, SYSTEM_COL_CREATED_AT, SYSTEM_COL_CREATED_BY).forEach { remove(it) } }
                if (assignments.isEmpty()) return 1
                renderer.update(ctx.objectApi, assignments, plan.id, plan.expectedVersion)
            }
        return try {
            jdbc.update(sql, MapSqlParameterSource(params))
        } catch (e: DuplicateKeyException) {
            // 新建撞主键＝同一 id 重复提交（开放接口重试的常见形状），语义上就是该重试/该刷新
            throw WriteErrors.conflictRetry(plan.id, plan.expectedVersion, -1L)
        } catch (e: DataIntegrityViolationException) {
            throw WriteErrors.fkViolation("引用完整性校验未通过：${e.mostSpecificCause.message?.take(200) ?: e.message}", mapOf("record_id" to plan.id))
        }
    }

    private companion object {
        const val LOCK_TIMEOUT_SECONDS = 5
        const val SYSTEM_COL_ID = "id"
        const val SYSTEM_COL_CREATED_AT = "created_at"
        const val SYSTEM_COL_CREATED_BY = "created_by"
    }
}

/** 命名锁返回值跨方言归一（PG 给 boolean、MySQL 给 1/0）。 */
internal fun truthy(
    rows: List<Map<String, Any?>>,
): Boolean {
    val v = rows.firstOrNull()?.values?.firstOrNull()
    return when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v.toString() == "1"
    }
}

/** 意图归一（REST 薄壳用：PUT/POST 都进 UPSERT，软删与恢复各自显式声明）。 */
internal fun intentOf(
    delete: Boolean,
    restore: Boolean,
): WriteIntent =
    when {
        delete -> WriteIntent.DELETE
        restore -> WriteIntent.RESTORE
        else -> WriteIntent.UPSERT
    }
