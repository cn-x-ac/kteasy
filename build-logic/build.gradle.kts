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
    // 覆盖本目录的 *.kts（含 settings.gradle.kts）；convention 子项目另有一套
    alias(libs.plugins.spotless)
}

// 与主构建共用同一份模板与定位正则；传"内容"而非路径，模板变更才会让任务失效重跑
val licenseHeader = file("../config/spotless/license-header.txt").readText()
val headerDelimiter = file("../config/spotless/header-delimiter.txt").readText().trim()

spotless {
    format("gradleScripts") {
        target("*.kts")
        licenseHeader(licenseHeader, headerDelimiter)
        trimTrailingWhitespace()
        endWithNewline()
    }
}
