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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import org.springframework.stereotype.Component

/**
 * md 区命名空间翻译（【规格】§1.2：PG schema `md` / MySQL 同库前缀 `md_`）。
 *
 * TODO(M1-02)：本类是方言 SPI（SchemaProvider/NamespaceMapper）落地前的最小接缝——
 * 只承载「逻辑表名 → 物理表名」一件事；M1-02 收编进 capability 台账后本类删除，
 * 上层代码只改注入点。JSON 列写入的方言差异（PG jsonb 需显式 CAST）同样收口在此。
 */
@Component
class MdNamespace(
    context: KteasyContext,
) {
    val dialect: Dialect = context.dialect

    /** 逻辑名 → 物理表名。 */
    fun table(logical: String): String = if (dialect == Dialect.POSTGRESQL) "md.$logical" else logical

    /** JSON 参数占位符：PG 的 jsonb 列要求显式 CAST，MySQL 的 JSON 列直接收字符串。 */
    fun jsonPlaceholder(param: String): String = if (dialect == Dialect.POSTGRESQL) "CAST(:$param AS jsonb)" else ":$param"
}
