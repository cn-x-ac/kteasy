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
    id("kteasy.kotlin.jvm")
    id("kteasy.compliance")
}

description = "Kteasy 自动化脚本层（GraalJS，【规格】§5.6 Tier-2）：M3b 启用的可选模块"

// 本卡只立占位：不引入 GraalVM 依赖（体积敏感），也不进 kteasy-server 的默认依赖图。
// 启用方式 = kteasy.automation.js.enabled + 装配层 feature flag（M0-02 留配置位）。
dependencies {
    implementation(projects.kteasyCore)
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
