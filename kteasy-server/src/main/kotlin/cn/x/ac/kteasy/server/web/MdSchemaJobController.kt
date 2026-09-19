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
import cn.x.ac.kteasy.server.md.MetadataRepository
import cn.x.ac.kteasy.server.schema.SchemaJob
import cn.x.ac.kteasy.server.schema.SchemaJobExecutor
import cn.x.ac.kteasy.server.schema.SchemaJobRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 物化作业账本观测与手工续跑 REST 层（步骤卡 M1-03 Block D，契约总表 §2）。
 *
 * 只读投影：把 [SchemaJobRepository] 的四态机账本渲染成三键契约体，供治理面看「某对象当前物化到哪一步、
 * 是否有挂起步」。**唯一写侧**是手工续跑——把对象重新入队交异步执行器重算 diff（precheck 命中已 DONE 步
 * 即幂等跳过），控制器本身不直接下 DDL（红线④：SQL 全圈在 schema 模块）。路径挂 `/api/md` 前缀，鉴权由
 * [cn.x.ac.kteasy.server.md.MdBootTokenFilter] 承担（M 态）。`{id}` 一律取对象 api_name，服务端解析为
 * object_id 后再查账本，与其余 md 端点同口径。
 */
@RestController
@RequestMapping("/api/md")
class MdSchemaJobController(
    private val jobRepo: SchemaJobRepository,
    private val meta: MetadataRepository,
    private val executor: SchemaJobExecutor,
) {
    /** 全量作业账本（按自增 id 升序，含全部对象），每条附 object_api 便于人读。 */
    @GetMapping("/schema-jobs")
    fun listJobs(): ResponseEntity<Map<String, Any?>> {
        val jobs = jobRepo.listAll()
        val apiById = meta.listObjects().associate { it.id to it.apiName }
        return ok(mapOf("jobs" to jobs.map { it.toView(apiById) }, "total" to jobs.size))
    }

    /** 列某对象的全部步；对象不存在 → 404 契约体（拒绝静默忽略）。 */
    @GetMapping("/schema-jobs/{id}")
    fun listJobsByObject(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> {
        val obj = requireObject(id)
        val jobs = jobRepo.listByObject(obj.id)
        val apiById = mapOf(obj.id to obj.apiName)
        return ok(
            mapOf(
                "object_api" to obj.apiName,
                "object_id" to obj.id,
                "jobs" to jobs.map { it.toView(apiById) },
                "total" to jobs.size,
            ),
        )
    }

    /** 手工续跑：把对象重新入队交执行器重算 diff。precheck 幂等，故对无待办 / 挂起对象重试皆安全无副作用。 */
    @PostMapping("/schema-jobs/{id}/retry")
    fun retryObject(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> {
        val obj = requireObject(id)
        executor.submit(obj.id)
        return ok(mapOf("object_api" to obj.apiName, "object_id" to obj.id, "queued" to true))
    }

    // ---------- 内部 ----------

    private fun requireObject(api: String) = meta.findObjectByApi(api) ?: throw KnownKteasyException(ApiError.NOT_FOUND, "对象不存在：$api")

    private fun SchemaJob.toView(apiById: Map<String, String>): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "object_id" to objectId,
            "object_api" to apiById[objectId],
            "step_kind" to stepKind.name,
            "seq" to seq,
            "state" to state,
            "attempts" to attempts,
            "checkpoint" to checkpointJson,
        )

    private fun ok(data: Any?): ResponseEntity<Map<String, Any?>> = ResponseEntity.ok(envelope(data))

    private fun envelope(data: Any?): Map<String, Any?> = linkedMapOf("error_code" to 0, "error_msg" to "ok", "data" to data)
}
