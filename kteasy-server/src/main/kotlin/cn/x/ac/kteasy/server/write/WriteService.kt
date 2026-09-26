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
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.core.schema.dialect.LockKey
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DetailDiffer
import cn.x.ac.kteasy.core.write.DetailRow
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.ExtCodec
import cn.x.ac.kteasy.core.write.InternalWriteSources
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.RelationDiffer
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
import cn.x.ac.kteasy.core.write.WriteSource
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

    /**
     * 单条写（新建/更新/软删/恢复共用一个入口，靠 [WriteContext.intent] 与 recordId 区分性质）。
     * 载荷带 `details` 时＝父 + 子树一并写（同事务、父锁横跨子树）；不带即普通单条。
     */
    fun write(
        ctx: WriteContext,
        draft: RecordDraft,
    ): WriteResult = writeWithDetails(ctx, draft)

    /**
     * 父 + 子项差量的一体化写（M1-07 块 2；A1 载荷）。全程**单事务**：主记录先写、拿到父 id 后
     * 逐子对象算三集并落库，任一子行失败＝整棵子树回滚（卡面 GWT1「中途注入异常→全回滚」）。
     *
     * 锁序按卡面 §1：先取**父记录写锁并横跨整棵子树持有**（串行化对同一父的并发明细写），
     * 子对象按 api_name 字典序、其内既有行（更新+软删）按 id 升序、新建其后。
     */
    fun writeWithDetails(
        ctx: WriteContext,
        draft: RecordDraft,
    ): WriteResult {
        val deleteLike = ctx.intent == WriteIntent.DELETE || ctx.intent == WriteIntent.RESTORE
        require(!deleteLike || draft.details.isEmpty()) {
            "软删/恢复不接受 details（删除就是删除，夹带改子表＝伪装成删除的顺带写）"
        }
        return tx.execute {
            val (_, pgraph) = cache.graph(ctx.objectApi)
            val pKey = ctx.recordId?.let { WriteLocks.of(pgraph.objectMeta.id, it) }
            pKey?.let { acquire(it) }
            try {
                val seeds = ArrayList<Pair<String, String>>()
                val parent = writeLocked(ctx, pgraph, draft.copy(details = emptyMap()))
                if (seedsWrite(parent)) seeds += ctx.objectApi to parent.id
                if (draft.details.isNotEmpty()) seeds += writeChildren(ctx, parent.id, draft.details)
                // recalc 只在最外层用户写触发（内部再写 recalcDepth>0 不再递归，由本迭代逐层驱动）；
                // 仍在父锁与同一事务内，保证汇总与源写原子一致。
                if (ctx.recalcDepth == 0) recalcPass(ctx, seeds)
                parent
            } finally {
                pKey?.let { release(it) }
            }
        } ?: throw KnownKteasyException(ApiError.INTERNAL, "写事务未返回结果")
    }

    /** 子对象按 api 字典序；每子对象内既有行操作按 id 升序、新建其后。返回真发生变更的 (objectApi, recordId) 供 recalc 种子。 */
    private fun writeChildren(
        ctx: WriteContext,
        parentId: String,
        details: Map<String, List<DetailRow>>,
    ): List<Pair<String, String>> {
        val changed = ArrayList<Pair<String, String>>()
        for ((childApi, rows) in details.entries.sortedBy { it.key }) {
            val existing = existingChildIds(childApi, parentId)
            val diff = DetailDiffer.diff(childApi, rows, existing)
            val updateById = diff.updates.associateBy { it.id!! }
            val deleteIds = diff.deletes.toSet()
            for (id in (updateById.keys + deleteIds).sorted()) {
                val del = id in deleteIds
                val childCtx = ctx.copy(objectApi = childApi, parentId = parentId, recordId = id, intent = if (del) WriteIntent.DELETE else WriteIntent.UPSERT, expectedVersion = null)
                val r = doWrite(childCtx, updateById[id]?.let { RecordDraft(it.fields) } ?: RecordDraft())
                if (seedsWrite(r) || del) changed += childApi to id
            }
            for (r in diff.creates) {
                val childCtx = ctx.copy(objectApi = childApi, parentId = parentId, recordId = null, intent = WriteIntent.UPSERT, expectedVersion = null)
                val res = doWrite(childCtx, RecordDraft(r.fields))
                changed += childApi to res.id
            }
        }
        return changed
    }

    /** 该写是否应作为 recalc 种子：字段有变更，或新建/软删/恢复（这些改变父聚合的存在性，diff 可能空）。 */
    private fun seedsWrite(r: WriteResult): Boolean = r.diff.isNotEmpty() || r.kind == WriteKind.CREATED || r.kind == WriteKind.DELETED || r.kind == WriteKind.RESTORED

    private fun existingChildIds(
        childApi: String,
        parentId: String,
    ): Set<String> = jdbc.queryForList(renderer.childIdsSql(childApi), MapSqlParameterSource(mapOf("__pid" to parentId)), String::class.java).map { it!! }.toSet()

    /**
     * recalc（M1-07 块4）：沿父链逐层重算汇总字段。种子＝本层"真变更"的记录 `(objectApi, recordId)`。
     *
     * 每层把各源对象的 `depIn` 边（本对象被父对象聚合）折算成"目标父记录 + 目标字段 + 聚合查询"，
     * **按目标记录 id 升序**逐条经 [doWrite]（`SYSTEM` 来源、`recalcDepth+1`）写回——写回复用整条写通道
     * （锁/row_version/diff/事件），目标字段值一致则 diff 空、自然不写也不进下一层，从而终止。
     * 深度超 [RECALC_MAX_DEPTH] 即停并告警（防脏图失控；保存期已做环检测，这里是运行期兜底）。
     */
    private fun recalcPass(
        ctx: WriteContext,
        seeds: List<Pair<String, String>>,
    ) {
        var frontier = seeds.distinct()
        var depth = 0
        while (frontier.isNotEmpty()) {
            if (depth >= RECALC_MAX_DEPTH) {
                log.warn("recalc 超深度上限 {}，中止剩余 {} 条链上溯 trace={}", RECALC_MAX_DEPTH, frontier.size, ctx.traceId)
                return
            }
            depth++
            // 收集本层待算：键 (目标对象api, 目标记录id, 目标字段api) → 聚合 SQL 描述
            val jobs = LinkedHashMap<Triple<String, String, String>, RecalcJob>()
            for ((objApi, recId) in frontier) {
                val (_, graph) = cache.graph(objApi)
                val parentApi = graph.parent?.apiName ?: continue
                val (_, pgraph) = cache.graph(parentApi)
                val targetFieldByParent = pgraph.fields.associateBy { it.id }
                val sourceFieldById = graph.fields.associateBy { it.id }
                val pid = readParentId(objApi, recId) ?: continue
                for (edge in graph.depIn) {
                    val srcField = sourceFieldById[edge.sourceFieldId] ?: continue
                    val targetField = targetFieldByParent[edge.targetFieldId] ?: continue
                    jobs[Triple(parentApi, pid, targetField.apiName)] = RecalcJob(parentApi, targetField.apiName, objApi, srcField, edge.op, pid, edge.id)
                }
            }
            if (jobs.isEmpty()) return
            val next = ArrayList<Pair<String, String>>()
            for (job in jobs.values.sortedWith(compareBy({ it.targetRecordId }, { it.targetFieldApi }))) {
                val sql = renderer.aggregateSql(job.sourceObjectApi, job.sourceField, job.op, "parent_id")
                val agg = jdbc.queryForObject(sql, MapSqlParameterSource(mapOf("__pid" to job.targetRecordId)), java.math.BigDecimal::class.java)
                val injected = if (agg == null) DraftValue.Cleared else DraftValue.Number(agg.toPlainString())
                val targetCtx =
                    ctx.copy(
                        objectApi = job.targetObjectApi,
                        recordId = job.targetRecordId,
                        intent = WriteIntent.UPSERT,
                        source = WriteSource.SYSTEM,
                        expectedVersion = null,
                        parentId = null,
                        recalcDepth = depth,
                        serverDerived = mapOf(job.targetFieldApi to injected),
                    )
                val r = doWrite(targetCtx, RecordDraft())
                if (r.diff.isNotEmpty()) next += job.targetObjectApi to job.targetRecordId
            }
            frontier = next
        }
    }

    private fun readParentId(
        objectApi: String,
        recordId: String,
    ): String? =
        runCatching {
            jdbc.queryForObject(
                "SELECT parent_id FROM ${provider.namespace.qualified(LogicalArea.ENTITY, objectApi)} WHERE id = :__id",
                MapSqlParameterSource(mapOf("__id" to recordId)),
                String::class.java,
            )
        }.getOrNull()

    private data class RecalcJob(
        val targetObjectApi: String,
        val targetFieldApi: String,
        val sourceObjectApi: String,
        val sourceField: MdField,
        val op: cn.x.ac.kteasy.core.meta.DepAggOp,
        val targetRecordId: String,
        val edgeId: String,
    )

    /**
     * N2N 关联集合差量落 `r_` 表（块 3A）。在主机记录写锁内、与主写同事务调用（`writeLocked` 落库后）。
     *
     * 现存目标集从 `r_` 表读（管道零 IO 看不到），[RelationDiffer] 算加/删/保留；
     * **保留行整行不碰**（不重插、不改）→ 其 `ext` 附加列天然存活（卡面红线：更新关联绝不丢未变行附加列）；
     * 删＝按 (源,宿) 物理删连接行（`r_` 表无 `deleted_at` 列，删即删）；加＝插新行（ext 留空）。
     */
    private fun applyRelations(
        graph: cn.x.ac.kteasy.core.meta.MetadataGraph,
        plan: WritePlan,
    ) {
        if (plan.relationTargets.isEmpty()) return
        val hostApi = graph.objectMeta.apiName
        val hostId = plan.id
        val objects = cache.snapshot().objects
        for ((fieldApi, targetIds) in plan.relationTargets) {
            val field = graph.fields.first { it.apiName == fieldApi }
            val targetApi = field.refObjectId?.let { rid -> objects.firstOrNull { it.id == rid }?.apiName }
            val relTable = provider.namespace.qualified(LogicalArea.RELATION, SchemaDiff.relationTableLogicalName(graph.objectMeta, field))
            val srcCol = SchemaDiff.relationSourceColumn(hostApi)
            val dstCol = SchemaDiff.relationTargetColumn(targetApi)
            val existing =
                jdbc
                    .queryForList(
                        renderer.relationTargetsSql(relTable, srcCol, dstCol),
                        MapSqlParameterSource(mapOf("__src" to hostId)),
                        String::class.java,
                    ).map { it!! }
                    .toSet()
            val diff = RelationDiffer.diff(targetIds, existing)
            for (dst in diff.toAdd) {
                jdbc.update(
                    renderer.relationInsertSql(relTable, srcCol, dstCol),
                    MapSqlParameterSource(mapOf("__id" to Ulid.next(), "__src" to hostId, "__dst" to dst)),
                )
            }
            if (diff.toRemove.isNotEmpty()) {
                jdbc.update(
                    renderer.relationDeleteSql(relTable, srcCol, dstCol),
                    MapSqlParameterSource(mapOf("__src" to hostId, "__dsts" to diff.toRemove)),
                )
            }
        }
    }

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
        val key = ctx.recordId?.let { WriteLocks.of(graph.objectMeta.id, it) }
        key?.let { acquire(it) }
        try {
            return writeLocked(ctx, graph, draft)
        } finally {
            key?.let { release(it) }
        }
    }

    /**
     * 写锁**已持有**前提下的单记录落库（阶段 8–12 的执行体）。
     * 由 [doWrite]（自持锁）与 [writeWithDetails]（父锁横跨子树）共用——拆出这段是为了让父锁能罩住子行写，
     * 而不是每记录各取各放（那会让同一父的并发明细写交错，见卡面 §1 锁序）。
     */
    private fun writeLocked(
        ctx: WriteContext,
        graph: cn.x.ac.kteasy.core.meta.MetadataGraph,
        draft: RecordDraft,
    ): WriteResult {
        val existing = ctx.recordId?.let { id -> readForUpdate(ctx.objectApi, id)?.let { rows.toRow(it, graph.fields) } }
        val plan = pipeline.plan(WriteInput(graph, ctx, draft, existing))

        // **无变化即不写**：不发 UPDATE、不推 row_version、不发提交事件。
        // 卡面 GWT4 的 diff 是这件事的数据源，M3「执行结果与目标一致时自动跳过（连级联一起跳过）」也挂在这里——
        // 若把空 diff 也写成一次变更，每次整单保存都会制造一条假变更与一轮级联触发。
        // 条件刻意包含 UPDATED：软删/恢复的变化落在 deleted_at、不进 diff，
        // 只按「diff 为空」判跳过会把删除误判成无变化（块 5 双库 IT 第一次跑就抓到）。
        if (plan.kind == WriteKind.UPDATED && plan.diff.isEmpty() && plan.relationTargets.isEmpty()) {
            log.debug("无变化，跳过写入 object={} id={} 版本={}", ctx.objectApi, plan.id, plan.expectedVersion)
            return WriteResult(plan.id, plan.expectedVersion, plan.kind, emptyMap(), plan.warnings)
        }

        val affected = persist(ctx, plan)
        if (plan.kind == WriteKind.UPDATED && affected == 0) {
            throw WriteErrors.conflictRetry(plan.id, plan.expectedVersion, existing?.rowVersion ?: -1L)
        }
        // 主机行已在（新建刚 INSERT、更新已 UPDATE），且本记录写锁在手 → 关联集合差量与主写同事务、同锁保护。
        applyRelations(graph, plan)
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
        const val RECALC_MAX_DEPTH = 5
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
