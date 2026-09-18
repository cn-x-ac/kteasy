/*
 * Copyright 2026 阿杰很厉害 <506907958@qq.com>. SPDX-License-Identifier: Apache-2.0
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
            }
        }

    private companion object {
        const val JDK_VERSION = 21
    }
}
