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
package cn.x.ac.kteasy.server.web

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.WriteActor
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteIntent
import cn.x.ac.kteasy.core.write.WriteResult
import cn.x.ac.kteasy.core.write.WriteSource
import cn.x.ac.kteasy.server.write.DraftValues
import cn.x.ac.kteasy.server.write.RowValues
import cn.x.ac.kteasy.server.write.WriteService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 数据面写端点（API 总表 §3；步骤卡 M1-06）。**薄壳**——只做三件事：
 * 取载荷 → 装配 [WriteContext] → 调 [WriteService]，然后渲染响应。业务判断一条都不许留在这里
 * （架构纪律「api 层零业务逻辑」；留一条判据就少一处出口唯一）。
 *
 * ## 来源枚举在本卡是**定死**的，不由调用方声明
 *
 * `WriteSource` 决定审计口径与守卫分支，若允许请求自己填，任何客户端都能自称 `SYSTEM` 走特权通道——
 * 所以 `UI` 由这些端点写死，`OPENAPI` 归 M5 开放接口端点，`IMPORT`/`TRANSFORM` 归各自内部通道。
 *
 * ## M2 之前的身份口径（诚实版）
 *
 * 本卡没有鉴权（M2-01 才建用户/会话），故 actor 恒为常量 [DEV_ACTOR]：
 * **`owner_user`/`created_by` 会全部写成这个占位值**。刻意不读任何请求头来认身份——
 * 一个能被伪造的 owner 比一个难看的占位值危险得多（它会在 M2 落地当天变成权限事故）。
 * M2-01 换 [actorOf] 的实现即可，其余一行不动。
 */
@RestController
class RecordController(
    private val writes: WriteService,
    private val rows: RowValues,
) {
    /** 新建（无 id 即新建；带 id 走同一条 UPSERT 语义，由写通道裁决）。 */
    @PostMapping("/api/data/record/{object}")
    fun create(
        @PathVariable `object`: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = respond(`object`, WriteIntent.UPSERT, null, body)

    /** 更新（PATCH＝只碰提交的键；带 `expected_version` 则校验盲覆盖）。 */
    @PatchMapping("/api/data/record/{object}/{id}")
    fun update(
        @PathVariable `object`: String,
        @PathVariable id: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = respond(`object`, WriteIntent.UPSERT, id, body)

    /** 软删（进回收站；查询层默认不可见，恢复走 [restore]）。 */
    @DeleteMapping("/api/data/record/{object}/{id}")
    fun delete(
        @PathVariable `object`: String,
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> = respond(`object`, WriteIntent.DELETE, id, emptyMap())

    /** 恢复（清 `deleted_at`）。 */
    @PostMapping("/api/action/restore/{object}/{id}")
    fun restore(
        @PathVariable `object`: String,
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> = respond(`object`, WriteIntent.RESTORE, id, emptyMap())

    /**
     * 批量写（卡面 §6：循环单条、**各自事务**）。
     *
     * 逐条回状态、一条失败不回滚全批——M4-02 导入的「每行状态可见」依赖这个形状。
     * 需要跨条原子的语义走 `writeAllInTx`（仅 SYSTEM/TRANSFORM/IMPORT），不在 HTTP 上开放。
     */
    @PostMapping("/api/data/records/batch")
    fun batch(
        @PathVariableUnused marker: Unit = Unit,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val items = body["items"] as? List<*> ?: throw TransportErrors.draftShape(listOf("批量载荷须为 items: [{id?, fields}]"))
        val results =
            items.map { item ->
                val m = item as? Map<*, *> ?: throw TransportErrors.draftShape(listOf("批量条目必须是对象"))
                val objectApi = (m["object"] ?: body["object"]) as? String ?: throw TransportErrors.draftShape(listOf("批量条目缺 object"))
                runCatching {
                    val ctx = contextOf(objectApi, WriteIntent.UPSERT, m["id"] as String?, m)
                    ok(writes.write(ctx, DraftValues.toDraft(m["fields"] as? Map<*, *>)))
                }.getOrElse { e ->
                    failed(objectApi, e)
                }
            }
        return ResponseEntity.ok(linkedMapOf("error_code" to 0, "error_msg" to "ok", "data" to linkedMapOf("results" to results)))
    }

    private fun respond(
        objectApi: String,
        intent: WriteIntent,
        id: String?,
        body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val ctx = contextOf(objectApi, intent, id, body)
        return ResponseEntity.ok(ok(writes.write(ctx, DraftValues.toDraft(fieldsOf(body, intent), detailsOf(body, intent)))))
    }

    /** 子项差量（M1-07 块2）：仅 UPSERT 携带；DELETE/RESTORE 带 details 与带 fields 同罪——伪装成删除的顺带改。 */
    private fun detailsOf(
        body: Map<String, Any?>,
        intent: WriteIntent,
    ): Map<*, *>? {
        val details = body["details"] as? Map<*, *>
        if (intent != WriteIntent.UPSERT && !details.isNullOrEmpty()) {
            throw TransportErrors.draftShape(listOf("${intent.name} 不接受 details 载荷"))
        }
        return details
    }

    /** DELETE/RESTORE 不接受 `fields`（有即拒——防「顺手改两个字段再删」这种伪装请求悄悄通过）。 */
    private fun fieldsOf(
        body: Map<String, Any?>,
        intent: WriteIntent,
    ): Map<*, *>? {
        val fields = body["fields"] as? Map<*, *>
        if (intent != WriteIntent.UPSERT && !fields.isNullOrEmpty()) {
            throw TransportErrors.draftShape(listOf("${intent.name} 不接受 fields 载荷"))
        }
        return fields
    }

    /** [body] 收宽松映射：批量条目反序列化出来本来就是 `Map<*, *>`，在边界层做强转会逼出一串 cast。 */
    private fun contextOf(
        objectApi: String,
        intent: WriteIntent,
        id: String?,
        body: Map<*, *>,
    ): WriteContext =
        WriteContext(
            objectApi = objectApi,
            intent = intent,
            source = WriteSource.UI,
            actor = actorOf(),
            traceId = MDC.get(TraceIdFilter.MDC_KEY) ?: Ulid.next(),
            now = ZonedDateTime.now(ZoneId.systemDefault()).withNano(0),
            recordId = id,
            expectedVersion = (body["expected_version"] as? Number)?.toLong(),
        )

    private fun ok(
        r: WriteResult,
    ): Map<String, Any?> =
        linkedMapOf(
            "error_code" to 0,
            "error_msg" to "ok",
            "data" to
                linkedMapOf(
                    "id" to r.id,
                    "version" to r.version,
                    "kind" to r.kind.name,
                    "diff" to rows.diffWire(r.diff),
                    "warnings" to r.warnings.map { it.toWire() },
                ),
        )

    /** 批量里的单条失败：渲染成契约形状放进 results，不整批 500。 */
    private fun failed(
        objectApi: String,
        e: Throwable,
    ): Map<String, Any?> {
        val known = e as? KnownKteasyException
        return linkedMapOf(
            "object" to objectApi,
            "ok" to false,
            "error_code" to (known?.apiError?.code ?: ApiError.INTERNAL.code),
            "error_msg" to (known?.message ?: "服务端内部错误"),
            "data" to (known?.data ?: emptyMap<String, Any?>()),
        )
    }

    private companion object {
        /** M2-01 之前的占位身份：所有归属都落在它身上，M2 换 [actorOf] 一处即可。 */
        const val DEV_ACTOR = "unauthenticated"

        fun actorOf(): WriteActor = WriteActor(DEV_ACTOR, null)
    }
}

/** 占位：让 batch 端点签名保持「无路径变量」的显式形状（见 [RecordController.batch] 的 marker 参数）。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PathVariableUnused

/** 载荷里的 `fields` → 记录草稿（未注册键/停用字段一律留给写通道拒，边界层不重复判）。 */
internal fun draftOf(
    fields: Map<String, DraftValue>,
): RecordDraft = RecordDraft(fields)
