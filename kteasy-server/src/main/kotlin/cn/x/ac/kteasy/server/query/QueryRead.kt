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
package cn.x.ac.kteasy.server.query

import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.query.MetadataLookup
import cn.x.ac.kteasy.server.md.MetadataSnapshot

/**
 * 一次查询的结果。显式投影行键为 `c0/c1/…`（按 select 顺序，跨方言稳定）；`SELECT *`（全业务列）按物理列名。
 *
 * @property truncated 命中强制 LIMIT 被截断（内部以 limit+1 探量后裁回）
 */
data class QueryResult(
    val rows: List<Map<String, Any?>>,
    val truncated: Boolean,
    val elapsedMs: Long,
)

/**
 * [MetadataLookup] 的 server 实现：投影一份**快照**（一次查询取一次、编译期只读，杜绝编译中版本漂移）。
 *
 * 走 [cn.x.ac.kteasy.server.md.MetadataGraphCache] 的 AsyncCache 快照，零额外打库；缓存失效语义见 M1-01（§8-⑦）。
 */
class SnapshotMetadataLookup(
    private val snapshot: MetadataSnapshot,
) : MetadataLookup {
    private val byApi: Map<String, MdObject> = snapshot.objects.associateBy { it.apiName }
    private val byId: Map<String, MdObject> = snapshot.objects.associateBy { it.id }
    private val fieldsByObj: Map<String, List<MdField>> = snapshot.fields.groupBy { it.objectId }

    override fun objectByApi(
        api: String,
    ): MdObject? = byApi[api]

    override fun objectById(
        id: String,
    ): MdObject? = byId[id]

    override fun fieldsByObject(
        objectId: String,
    ): List<MdField> = fieldsByObj[objectId].orEmpty()
}
