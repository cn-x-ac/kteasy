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
package cn.x.ac.kteasy.core.write

/**
 * 多对多关联（`r_` 表）的一次集合差量（卡面 M1-07 §设计要点 2）。
 *
 * 与子项三集的**关键差异**：关联行是连接表行、无 `deleted_at` 列，故「删」＝**物理 DELETE 该行**（不是软删）；
 * 「保留」＝**一行都不碰**——不重插、不改，从而保住该行 `ext` 里的附加列（红线：更新关联集合绝不丢未变行的附加列）。
 *
 * 顺序确定：[toAdd]/[kept] 保持载荷声明序（去重后），[toRemove] 按目标 id 升序（删除序稳定，防并发下抖动）。
 */
data class RelationDiff(
    val toAdd: List<String>,
    val toRemove: List<String>,
    val kept: List<String>,
)

/**
 * N2N 集合差纯函数（块 3A 单元①）：无 IO，可脱离 DB 单测。现存 `to_id` 集由执行层从 `r_` 表读入
 * （管道是零 IO 的纯函数，拿不到连接表现状，故三集裁决落在 `writeLocked` 持主机行锁时调本函数）。
 *
 * @param payload 载荷里的目标 id 列表（可含重复，内部去重）
 * @param existingIds 该主机记录当前在 `r_` 表里关联的目标 id 集
 */
object RelationDiffer {
    fun diff(
        payload: List<String>,
        existingIds: Set<String>,
    ): RelationDiff {
        val ordered = LinkedHashSet(payload) // 去重且保声明序
        val toAdd = ordered.filter { it !in existingIds }
        val kept = ordered.filter { it in existingIds }
        val toRemove = existingIds.filter { it !in ordered }.sorted()
        return RelationDiff(toAdd, toRemove, kept)
    }
}
