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

description = "Kteasy 服务端：Spring Boot 装配 + REST 层（唯一常驻进程）"

// M0-02：本模块落地启动类与双数据源装配。
// Boot 4 起不再自动应用 io.spring.dependency-management，运行期版本对齐同样靠 platform(BOM)。
dependencies {
    implementation(projects.kteasyCore)
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    // M0-03：Flyway 迁移引擎自身库（只管 kteasy/md 区，不碰租户动态表）
    implementation(libs.spring.boot.starter.flyway)
    // 双库驱动随包装配（M0-02 只连不改数据，SQL 归 query/schema 模块）
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector.j)
    // Flyway 10 分库支持模块：PG 与 MySQL 各一，运行期按 Profile 选中的方言生效
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.flyway.mysql)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test.classic)
    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 有主类后打开 bootJar、关掉普通 jar：约定插件默认对可运行模块关闭 bootJar，
// 是为了 M0-01「无主类仍可构建」；本卡启动类落地，故在此模块级覆盖回来（后配置者生效）。
springBoot {
    mainClass.set("cn.x.ac.kteasy.server.KteasyApplication")
}

tasks.matching { it.name == "bootJar" }.configureEach { enabled = true }
tasks.matching { it.name == "jar" }.configureEach { enabled = false }
