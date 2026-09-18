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
    id("kteasy.spring.boot")
    id("kteasy.compliance")
}

description = "Kteasy 示例业务（M6 填充：管理端/用户端 SPA 与 CRM 验收剧本）"

// 空壳模块：只证明「引擎被独立依赖」这条边界成立，不含任何业务码
dependencies {
    implementation(projects.kteasyServer)
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
