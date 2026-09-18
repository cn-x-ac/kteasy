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
package cn.x.ac.kteasy.server.config

import cn.x.ac.kteasy.core.kernel.Dialect

/**
 * 方言 ↔ 连接串一致性守卫（【规格】§8-⑤：禁隐式方言分支）。
 *
 * 独立成纯对象是为了能被 L1 单测直接调用，无需拉起 Spring 上下文。
 * 语义：先把 `kteasy.db.dialect` 解析为方言（未知值即抛，绝不套默认库），
 * 再强制 `spring.datasource.url` 的前缀与该方言匹配——不匹配就拒绝启动，
 * 报错信息里带上方言名与期望前缀，杜绝「pg Profile 却悄悄连上 mysql」这类静默降级。
 */
object DataSourceDialectGuard {
    fun requireConsistent(
        dialectValue: String,
        jdbcUrl: String?,
    ): Dialect {
        val dialect = Dialect.fromValue(dialectValue)
        require(!jdbcUrl.isNullOrBlank()) {
            "未配置 spring.datasource.url；方言 '${dialect.profile}' 需要以 '${dialect.urlPrefix}' 开头的连接串（禁静默降级到另一库）"
        }
        require(jdbcUrl.startsWith(dialect.urlPrefix)) {
            "spring.datasource.url=$jdbcUrl 与方言 '${dialect.profile}' 期望前缀 '${dialect.urlPrefix}' 不符：" +
                "Profile 与连接串指向了不同库，拒绝启动（禁静默降级到另一库）"
        }
        return dialect
    }
}
