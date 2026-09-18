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
            // 注意：必须把"内容"传给 spotless，而不是文件路径。
            // 实测 licenseHeaderFile(...) 只记路径 → 改模板文件不会让任务失效重跑，门禁假绿；
            // 配置期读成字符串后，内容进入任务状态，模板一改必然重跑。
            val licenseHeader = File(rootProject.projectDir, LICENSE_HEADER_PATH).readText()
            val headerDelimiter = File(rootProject.projectDir, HEADER_DELIMITER_PATH).readText().trim()
            val hasSources = File(projectDir, "src").isDirectory

            extensions.configure<SpotlessExtension> {
                if (hasSources) {
                    kotlin {
                        target("src/**/*.kt")
                        licenseHeader(licenseHeader, headerDelimiter)
                        ktlint(ktlintVersion)
                        endWithNewline()
                    }
                    // 实测：java{} 步骤里的 licenseHeader 是空转的——删掉 package-info.java 的版权头，
                    // spotlessJavaCheck 不报缺头（只报行尾）。改走与 *.kts 相同、语义可预期的纯文本 format 步骤。
                    format("javaFiles") {
                        target("src/**/*.java")
                        licenseHeader(licenseHeader, headerDelimiter)
                        trimTrailingWhitespace()
                        endWithNewline()
                    }
                }
                format("gradleScripts") {
                    target("*.kts")
                    licenseHeader(licenseHeader, headerDelimiter)
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }
        }

    companion object {
        private const val LICENSE_HEADER_PATH = "config/spotless/license-header.txt"

        /**
         * 版权头之后的正文起点（定位正则）。外置成数据文件而非内嵌常量：主构建、build-logic 根与
         * convention 三处都要用同一份，写在代码里必然漂移；与模板一起登记为任务输入，改哪儿都会重跑。
         */
        private const val HEADER_DELIMITER_PATH = "config/spotless/header-delimiter.txt"
    }
}
