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
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import java.io.File

/**
 * 合规骨架：把【规格】§1.4 的版权头与 Kotlin 代码风格变成构建门禁。
 *
 * - 版权头模板全仓唯一：config/spotless/license-header.txt（缺头 → spotlessCheck 失败并指名文件）
 * - ktlint 版本从版本目录读取，插件内不写死
 * - 挂在 check 上，因此 `./gradlew build` 天然包含文件头校验
 */
class KteasyComplianceConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("com.diffplug.spotless")

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
            val licenseHeader = File(rootProject.projectDir, LICENSE_HEADER_PATH)
            val hasSources = File(projectDir, "src").isDirectory

            extensions.configure<SpotlessExtension> {
                if (hasSources) {
                    kotlin {
                        target("src/**/*.kt")
                        licenseHeaderFile(licenseHeader, HEADER_DELIMITER)
                        ktlint(ktlintVersion)
                        endWithNewline()
                    }
                    java {
                        target("src/**/*.java")
                        licenseHeaderFile(licenseHeader, HEADER_DELIMITER)
                        trimTrailingWhitespace()
                        endWithNewline()
                    }
                }
                format("gradleScripts") {
                    target("*.kts")
                    licenseHeaderFile(licenseHeader, HEADER_DELIMITER)
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }
        }

    companion object {
        private const val LICENSE_HEADER_PATH = "config/spotless/license-header.txt"

        /**
         * 版权头之后的正文起点。必须锚定行首，且不能被版权头自身匹配到：
         * 版权头只含块注释起始符与星号对齐行，不含「块注释+星号」的 Javadoc 起始形式，
         * 故下面这些候选项（含 Javadoc 起始与行首关键词）都是安全的。
         */
        const val HEADER_DELIMITER =
            """(?m)^(package|import|@|/\*\*|class|interface|enum|fun |val |var |plugins \{|settings|rootProject|pluginManagement|dependencyResolutionManagement|enableFeaturePreview|include\(|tasks\.|group =|version =|description =|dependencies \{|java \{|spotless \{|gradlePlugin \{)"""
    }
}
