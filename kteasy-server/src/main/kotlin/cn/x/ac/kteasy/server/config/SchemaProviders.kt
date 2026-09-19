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
import cn.x.ac.kteasy.core.schema.dialect.MySqlSchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.PostgresSchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider

/**
 * 方言扩展点的装配工厂（步骤卡 M1-02 设计要点 2：`dialect=pg|mysql` 启动时定 Impl）。
 *
 * 这是**唯一**允许触碰方言具体实现类的地方——[Dialect] 到实现的一次性映射。
 * 业务层只注入 [SchemaProvider] 接口，拿不到具体方言类，也就无从写出 `if (isMySQL)`（红线⑤）。
 * ArchUnit 门禁据此把「只有装配层可选实现」钉成 CI 硬约束。
 */
object SchemaProviders {
    fun forDialect(dialect: Dialect): SchemaProvider =
        when (dialect) {
            Dialect.POSTGRESQL -> PostgresSchemaProvider()
            Dialect.MYSQL -> MySqlSchemaProvider()
        }
}
