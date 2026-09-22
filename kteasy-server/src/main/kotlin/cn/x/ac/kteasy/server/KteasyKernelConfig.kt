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

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.query.PassthroughPrivilegeInjector
import cn.x.ac.kteasy.core.query.PrivilegeInjector
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.config.DataSourceDialectGuard
import cn.x.ac.kteasy.server.config.KteasyProperties
import cn.x.ac.kteasy.server.config.SchemaProviders
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * kernel 启动装配（【规格】§2：kernel＝事务/缓存失效/配置/启动装配）。
 *
 * 做两件事：① 按方言选定 [SchemaProvider] 实现（唯一的具体方言类触碰点，红线⑤）；
 * ② 构造进程级 [KteasyContext]，把方言 capability 快照灌进去（M1-02 设计要点 2）。
 * 二者都先跑方言↔连接串守卫，因此「Profile 连错库」会在上下文启动阶段直接失败、
 * 报错含方言名——这是验收点之一。
 */
@Configuration(proxyBeanMethods = false)
class KteasyKernelConfig {
    @Bean
    fun schemaProvider(
        props: KteasyProperties,
        environment: Environment,
    ): SchemaProvider = SchemaProviders.forDialect(resolveDialect(props, environment))

    @Bean
    fun kteasyContext(
        props: KteasyProperties,
        environment: Environment,
        schemaProvider: SchemaProvider,
    ): KteasyContext =
        KteasyContext(
            dialect = resolveDialect(props, environment),
            // health 快照＝该方言原生支持的能力名（升序，供 golden-file 与 CI 稳定比对）。
            capabilities = schemaProvider.capabilities().map { it.name }.sorted(),
            dataDir = props.dataDir,
            version = engineVersion(),
        )

    /**
     * 权限注入器（M1-05 查询出口唯一化的可替换点）：本卡默认透传，M2-02 替换为真注入实现（换 bean 即可，查询层无感）。
     */
    @Bean
    fun privilegeInjector(): PrivilegeInjector = PassthroughPrivilegeInjector()

    /** 解析并守卫方言↔连接串一致性（幂等纯计算，两 bean 各自调用无副作用）。 */
    private fun resolveDialect(
        props: KteasyProperties,
        environment: Environment,
    ): Dialect =
        DataSourceDialectGuard.requireConsistent(
            dialectValue = props.db.dialect,
            jdbcUrl = environment.getProperty("spring.datasource.url"),
        )

    /** 版本取自 bootJar 写入的 manifest；以 class 直跑（测试/IDE）时回落到工程版本占位。 */
    private fun engineVersion(): String =
        KteasyApplication::class.java.`package`
            ?.implementationVersion
            ?.takeIf { it.isNotBlank() }
            ?: "0.1.0-dev"
}
