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
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 可运行模块（Spring Boot 装配层）的共用约定。
 *
 * 两点刻意为之：
 * 1. 不依赖 io.spring.dependency-management —— Spring Boot 4 起该插件不再自动应用，
 *    版本对齐统一由模块脚本里的 platform(BOM) 完成。
 * 2. M0-01 阶段还没有启动类（属 M0-02「启动装配」卡的范围），因此这里关掉 bootJar、
 *    保留普通 jar，使 `./gradlew build` 在无主类时依然全绿；M0-02 落地启动类后按需打开。
 */
class KteasySpringBootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.springframework.boot")
            // kotlin-spring（all-open 的 Spring 预设）：@Component/@Repository/@Service 等注解类
            // 自动去 final——@Repository 会被持久化异常翻译 PostProcessor 做 CGLIB 代理，
            // final 类直接启动失败（M1-01 实测）。core 模块不走本插件，保持纯库。
            pluginManager.apply("org.jetbrains.kotlin.plugin.spring")

            pluginManager.withPlugin("org.springframework.boot") {
                tasks.matching { it.name == "bootJar" }.configureEach {
                    enabled = false
                }
                tasks.matching { it.name == "jar" }.configureEach {
                    enabled = true
                }
            }
        }
}
