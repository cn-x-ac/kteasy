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
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Kotlin/JVM 模块的共用约定：JDK 21 toolchain 锁定 + UTF-8 + JUnit 平台。
 *
 * 版本一律来自 gradle/libs.versions.toml，本插件与模块脚本都不写死版本号。
 */
class KteasyKotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.gradle.java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(JDK_VERSION))
                }
            }

            tasks.withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
                // Spring 的参数名发现依赖 -parameters（避免运行期回退到调试信息）
                options.compilerArgs.add("-parameters")
            }

            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                // 失败时打全量异常（含 AssertJ 的 expected/but was 差异），便于在 CI 日志直接定位断言真值；
                // 刻意不开 showStandardStreams——否则每个 context 测试的 Spring 启动日志会灌满控制台。
                testLogging {
                    events(TestLogEvent.FAILED)
                    exceptionFormat = TestExceptionFormat.FULL
                }
            }
        }

    private companion object {
        const val JDK_VERSION = 21
    }
}
