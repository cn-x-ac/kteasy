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
package cn.x.ac.kteasy.core.meta

/**
 * 字段物理存储形态（模块图纸 01 §2：**storage_kind 是唯一的真列判据**，M1-03 物化引擎消费）。
 *
 * - [EXT]：值整体存进 `ext` JSON 列（标量自定义字段的缺省形态，零 DDL）。
 * - [COLUMN]：物理真列（关系字段/分类路径/系统列），享受 FK/索引/类型约束。
 * - [N2N]：多引用既无真列也不进 ext——落 `r_<n2n标识>` 关联表（M1-07 建表）。
 *   步骤卡 M1-01 卡面只写 EXT|COLUMN，此处按图纸「COLUMN 不存在→r_* 表」扩第三值，
 *   已随实况回写图纸 01；COLUMN 仍是唯一真列判据，N2N 不产生任何列。
 */
enum class StorageKind {
    EXT,
    COLUMN,
    N2N,
}
