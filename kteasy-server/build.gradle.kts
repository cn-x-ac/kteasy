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
    id("kteasy.spring.boot")
    id("kteasy.compliance")
}

description = "Kteasy 服务端：Spring Boot 装配 + REST 层（唯一常驻进程）"

// 启动类与双数据源装配属 M0-02；本卡只保证可构建，故 bootJar 由约定插件暂时关闭
dependencies {
    implementation(projects.kteasyCore)
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
