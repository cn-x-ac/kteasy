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
package cn.x.ac.kteasy.core.kernel

/**
 * 数据库方言。启动时由 Spring Profile 显式映射（pg / mysql），不做自动嗅探——
 * 【规格】§8-⑤ 禁止业务层 `if (isMySQL)` 式的隐式分支，方言差异只经 SchemaProvider（M1-02）。
 *
 * 本枚举只承载「与库无关」的两件事实：Profile 标识与 JDBC URL 前缀，
 * 供装配层的方言↔连接串一致性守卫使用；真正的类型/DDL 差异留给后续 capability 台账。
 *
 * @property profile 对应的 Spring Profile 名与 `kteasy.db.dialect` 取值
 * @property urlPrefix 该方言期望的 JDBC URL 前缀，用于连错库即启动失败的显式校验
 */
enum class Dialect(
    val profile: String,
    val urlPrefix: String,
) {
    POSTGRESQL(profile = "pg", urlPrefix = "jdbc:postgresql:"),
    MYSQL(profile = "mysql", urlPrefix = "jdbc:mysql:"),
    ;

    companion object {
        /**
         * 按 `kteasy.db.dialect` 的字面值解析方言；未知值直接抛错，绝不回退到默认库。
         */
        fun fromValue(value: String): Dialect =
            entries.firstOrNull { it.profile.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "未知的 kteasy.db.dialect='$value'，允许值：${entries.joinToString { it.profile }}（禁静默降级到另一库）",
                )
    }
}
