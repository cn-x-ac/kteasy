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
 * 字段写策略（步骤卡 M1-06 §设计要点 2 阶段 4「服务端硬只读」的元数据承载位）。
 *
 * 只读四来源里「禁新建 / 禁修改 / 元数据只读」三类由本列表达；「自动化下发」用 [DERIVED] 占位，
 * 实装归 M3（本卡把它按 [READONLY] 同等拒绝手填，只锁语义不锁通道）。第四类来源**系统列**不占元数据位——
 * 系统列的只读由写入管道对 [SystemColumns] 恒定强制，配置改不动它（这正是"没有仅前端只读后门"的落点）。
 *
 * **为什么是一个枚举列而不是两个布尔**（⟨可逆⟩代拍，见 `specs/evidence/M1-06.md` §2-D2）：
 * 禁新建、禁修改、整体只读在语义上是互斥档位，拆成两个布尔会造出「两个都真但档位说可写」这类漂移态；
 * 一列一值＝单一真相，也让治理面 patch 与写管道的判定都只读一处。
 */
enum class FieldWritePolicy {
    /** 缺省：新建与更新都可由调用方提供值。 */
    WRITABLE,

    /** 禁新建：CREATE 路径带非空值即拒（更新仍可写）。 */
    NO_CREATE,

    /** 禁修改：UPDATE 路径改变现值即拒（新建仍可写）——建后不可变的编码类字段用此档。 */
    NO_UPDATE,

    /** 元数据只读：任何来源都不得由调用方提供值，值只能来自服务端派生。 */
    READONLY,

    /** 自动化下发位（M3 实装）：本卡按 [READONLY] 同等拒绝手填。 */
    DERIVED,
}

/**
 * 必填作用域（卡面阶段 4「required 三态」的另一半）。与 [MdField.required] **正交组合**，不重复表达"是否必填"：
 * `required=false` 时本列无意义（保持缺省 [ALWAYS] 即可）。
 *
 * 卡面第三态「来源豁免＝系统列自动填」是**管道规则**（派生/系统列在阶段 5 已填值，天然不算漏填），
 * 故不占元数据位——写在这里是为了让下一位读者不必去代码里找这个答案。
 */
enum class RequiredScope {
    /** 新建与更新都必填（缺省）。 */
    ALWAYS,

    /** 仅新建必填（更新可不填）。 */
    CREATE,

    /** 仅更新必填（例：修改必须说明理由的备注类字段）。 */
    UPDATE,
}
