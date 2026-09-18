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
package cn.x.ac.kteasy.server

import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.server.config.DataSourceDialectGuard
import cn.x.ac.kteasy.server.config.KteasyProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * kernel 启动装配（【规格】§2：kernel＝事务/缓存失效/配置/启动装配）。
 *
 * 本卡只做一件事：构造进程级 [KteasyContext]。构造即触发方言↔连接串守卫，
 * 因此「Profile 连错库」会在上下文启动阶段直接失败，报错含方言名——这是验收点之一。
 */
@Configuration(proxyBeanMethods = false)
class KteasyKernelConfig {
    @Bean
    fun kteasyContext(
        props: KteasyProperties,
        environment: Environment,
    ): KteasyContext {
        val dialect =
            DataSourceDialectGuard.requireConsistent(
                dialectValue = props.db.dialect,
                jdbcUrl = environment.getProperty("spring.datasource.url"),
            )
        return KteasyContext(
            dialect = dialect,
            capabilities = emptyList(), // M1-02 的 SchemaProvider capability 台账填充
            dataDir = props.dataDir,
            version = engineVersion(),
        )
    }

    /** 版本取自 bootJar 写入的 manifest；以 class 直跑（测试/IDE）时回落到工程版本占位。 */
    private fun engineVersion(): String =
        KteasyApplication::class.java.`package`
            ?.implementationVersion
            ?.takeIf { it.isNotBlank() }
            ?: "0.1.0-dev"
}
