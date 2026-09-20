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
    // M1-01：元数据图谱缓存（Caffeine 单飞加载，AFTER_COMMIT 失效）
    implementation(libs.caffeine)
    // M1-04：名称字段拼音检索码生成器（core 保持零第三方依赖，故 TinyPinyin 落 server 编解码侧；块 3 使用）
    implementation(libs.tinyPinyin) {
        // JitPack 上两个可选词库子模块（短语/城市词典）pom 路径 404；基础单字全拼在主 jar，排除之
        exclude(group = "com.github.promeg.TinyPinyin")
    }
    // 双库驱动随包装配（M0-02 只连不改数据，SQL 归 query/schema 模块）
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector.j)
    // Flyway 10 分库支持模块：PG 与 MySQL 各一，运行期按 Profile 选中的方言生效
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.flyway.mysql)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test.classic)
    testImplementation(libs.bundles.unit.test)
    // M1-02 架构门禁（红线④⑤）：把方言隔离变成 CI 断言
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 有主类后打开 bootJar、关掉普通 jar：约定插件默认对可运行模块关闭 bootJar，
// 是为了 M0-01「无主类仍可构建」；本卡启动类落地，故在此模块级覆盖回来（后配置者生效）。
springBoot {
    mainClass.set("cn.x.ac.kteasy.server.KteasyApplication")
}

tasks.matching { it.name == "bootJar" }.configureEach { enabled = true }
tasks.matching { it.name == "jar" }.configureEach { enabled = false }

// Block F：dev/seed 造数脚本作为 test 附加源参与编译（性能集成测直接调用；dev 工具、不进 bootJar 产物）。
kotlin {
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("dev/seed"))
    }
}

// 独立造数入口：./gradlew :kteasy-server:seed50w -PseedTable=<物理限定名> -PseedRows=500000
tasks.register<JavaExec>("seed50w") {
    group = "kteasy"
    description = "向指定实体表集合式灌 N 行造数（Block F 性能证据用）"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cn.x.ac.kteasy.dev.seed.SeedRunner")
    args = listOfNotNull(
        providers.gradleProperty("seedTable").orNull,
        providers.gradleProperty("seedRows").orNull,
    )
}
