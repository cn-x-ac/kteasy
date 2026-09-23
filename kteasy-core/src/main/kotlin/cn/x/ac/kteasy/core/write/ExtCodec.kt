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
 * `ext` 列的编解码接缝（图纸 01 §2「ext 内值形态」的实现契约）。
 *
 * 为什么是 SPI 而不是 core 里直接手写 JSON：core 维持零第三方依赖（ArchUnit 禁 Spring/JDBC，
 * `libs.versions.toml` 也没有 JSON 库），而装配层本就有 Jackson；与「能力在 server、契约在 core」
 * 的既有分工一致（同 [cn.x.ac.kteasy.core.meta.MdField] 的 JSON 列保持原始串、由消费方解析）。
 */
interface ExtCodec {
    /**
     * 编码进 ext 的字段集：值形态严格照图纸 01 §2（字符串/数字/布尔/字符串数组）。
     *
     * [DraftValue.Cleared] 的键**必须被丢弃**而非写成 JSON null——查询层 M1-05 D9 已把
     * 「ext 里的 null」归一为键存在性语义，落 null 会让同一字段在两库读出不同结果。
     */
    fun encode(
        values: Map<String, DraftValue>,
    ): String

    /**
     * 从 ext 原文解回（阶段 7 算 diff 要读旧值）。null/空串/`{}` 一律给空图。
     *
     * 结构性映射：JSON string→[DraftValue.Text]、number→[DraftValue.Number]、
     * bool→[DraftValue.Bool]、array→[DraftValue.Many]；嵌套对象原样拒绝（本卡 ext 只承载一层）。
     */
    fun decode(
        json: String?,
    ): Map<String, DraftValue>
}
