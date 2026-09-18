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
pluginManagement {
    // 构建约定（约定插件）来自 build-logic，避免在各模块里复制粘贴同一套配置
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// 仓库声明集中在设置层，模块内不得再声明仓库。
// 用 PREFER_SETTINGS 而非 FAIL_ON_PROJECT_REPOS：国内开发者常以 ~/.gradle/init.d 全局脚本注入镜像源，
// 后者会把这种注入直接判为构建失败；PREFER_SETTINGS 保留「设置层优先、项目级声明忽略并告警」的单一来源语义。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kteasy"

// typesafe 项目访问器：依赖写作 projects.kteasyCore，不出现字符串路径
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    "kteasy-core", // 引擎库：纯库，不依赖 web
    "kteasy-server", // Spring Boot 装配 + REST 层
    "kteasy-demo-app", // 示例业务（M6 填充）
    "kteasy-automation-graaljs", // 可选脚本层占位模块（M3b 启用）
)
