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
 * 单个子对象表的一次差量结果（卡面 M1-07 §设计要点 1 的三集）。
 *
 * 顺序确定：[creates]/[updates] 保持载荷声明序（写回时按此序，锁序另在装配层按 id 升序处理），
 * [deletes] 按 id 升序（喂「现存−载荷＝软删」且与加锁顺序一致，消除并发下的删除序抖动）。
 */
data class DetailDiff(
    val creates: List<DetailRow>,
    val updates: List<DetailRow>,
    val deletes: List<String>,
) {
    val isEmpty: Boolean get() = creates.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

/**
 * 子项三集纯函数（块 2 单元①）：无 IO、不含 SQL，可脱离 Spring/DB 单测。
 *
 * 裁决规则（A1 定稿）：
 * - 无 `id` → 新建；
 * - 带 `id` 且命中现存集 → 更新；
 * - 带 `id` 但**不在**现存集 → 定位失败 404 `WRITE_NOT_FOUND`（复用阶段 1 语义：那行不属于本父/已删，
 *   改载荷或重取列表可自救，不新造符号名——守 P6「ALL_IDS 冻结」）；
 * - 现存 − 载荷里带的那些 id → 软删。
 *
 * 载荷缺某子对象键根本不会走到这里（[RecordDraft.details] 里就没有该键＝不碰整表）；
 * 空数组会走到、`existingIds` 非空则全部落 [deletes]（＝清空该子表），与「空数组＝清空」口径一致。
 */
object DetailDiffer {
    fun diff(
        childObjectApi: String,
        payload: List<DetailRow>,
        existingIds: Set<String>,
    ): DetailDiff {
        val creates = ArrayList<DetailRow>()
        val updates = ArrayList<DetailRow>()
        val kept = HashSet<String>()
        for (row in payload) {
            val id = row.id
            if (id == null) {
                creates += row
            } else {
                if (id !in existingIds) throw WriteErrors.notFound(childObjectApi, id)
                updates += row
                kept += id
            }
        }
        val deletes = (existingIds - kept).sorted()
        return DetailDiff(creates.toList(), updates.toList(), deletes)
    }
}
