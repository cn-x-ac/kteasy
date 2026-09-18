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
plugins {
    id("kteasy.kotlin.jvm")
    id("kteasy.compliance")
}

description = "Kteasy 引擎库：对象与元数据、物化、EQL、通用写入、自动化、审批、转换（不依赖 web）"

// 引擎核心保持纯库：运行期零第三方依赖，版本对齐只在测试域引入 BOM
dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
