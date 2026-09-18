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
 * 每对象物理表的系统列约定（步骤卡 M1-01 §设计要点 1：落代码常量）。
 *
 * M1-03 物化引擎建表时按此清单生成固定列；写入管道（M1-06）按此识别只读来源。
 * `id` 为 26 位 ULID 字符串主键（【规格】§10 决策板：M1 内可逆技术项，已按默认拍定）。
 */
object SystemColumns {
    /** 主键列：26 位 ULID 字符串。 */
    const val ID = "id"

    /** 全部系统列（列名 = 物理列名，双库同构）。 */
    val ALL: List<String> =
        listOf(
            ID,
            "owner_user",
            "owner_dept",
            "created_at",
            "created_by",
            "updated_at",
            "updated_by",
            "deleted_at",
            "approval_state",
            "row_version",
            "ext",
        )

    /** 子项对象物理表额外系统列（parent_id + 指向主表 id 的 FK，M1-03 消费）。 */
    val CHILD_EXTRA: List<String> = listOf("parent_id")

    /** ANYREF 字段真列的伴生列后缀（存被引用对象的 api_name，禁 FK、软校验）。 */
    const val ANYREF_COMPANION_SUFFIX = "_obj"
}
