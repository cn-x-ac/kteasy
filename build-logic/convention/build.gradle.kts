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
plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

group = "cn.x.ac.kteasy.build"

// 约定插件本身也按 JDK 21 编译，与主构建 toolchain 一致
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // 约定插件在运行期需要这些插件的实现类，故用 implementation（不是 compileOnly）
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spotless.plugin.gradle)
}

gradlePlugin {
    plugins {
        register("kotlinJvm") {
            id = "kteasy.kotlin.jvm"
            implementationClass = "KteasyKotlinJvmConventionPlugin"
        }
        register("springBoot") {
            id = "kteasy.spring.boot"
            implementationClass = "KteasySpringBootConventionPlugin"
        }
        register("compliance") {
            id = "kteasy.compliance"
            implementationClass = "KteasyComplianceConventionPlugin"
        }
    }
}

// build-logic 是独立构建，主构建的 spotless 覆盖不到它，故在此自带合规校验。
// CI 以 ./gradlew -p build-logic spotlessCheck 把关；版权头模板与定位正则与主构建共用同一份文件。
// 传"内容"而非路径：路径形式不会让任务在模板变更时失效重跑（实测门禁假绿）。
val licenseHeader = file("../../config/spotless/license-header.txt").readText()
val headerDelimiter = file("../../config/spotless/header-delimiter.txt").readText().trim()

spotless {
    kotlin {
        target("src/**/*.kt")
        licenseHeader(licenseHeader, headerDelimiter)
        ktlint(libs.versions.ktlint.get())
        endWithNewline()
    }
    format("gradleScripts") {
        target("*.kts")
        licenseHeader(licenseHeader, headerDelimiter)
        trimTrailingWhitespace()
        endWithNewline()
    }
}
