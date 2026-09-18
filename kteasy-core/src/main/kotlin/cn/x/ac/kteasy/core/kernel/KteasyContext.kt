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
package cn.x.ac.kteasy.core.kernel

/**
 * 引擎启动装配骨架：一份进程级只读的运行时事实快照。
 *
 * 刻意做成不依赖 Spring 的纯数据载体（【规格】§2：core 保持纯库），
 * 由装配层（kteasy-server）在启动时构造并以 Bean 形式暴露。M0-02 只填四项，
 * capabilities 恒为空列表——方言能力位归 M1-02 的 SchemaProvider 台账。
 *
 * @property dialect 当前数据库方言（由 Profile 显式映射，禁自动嗅探）
 * @property capabilities 引擎已声明的方言/功能能力位；M0-02 恒空，M1-02 起填充
 * @property dataDir 数据目录根（附件落 `{dataDir}/_files`，见【规格】§1.1）
 * @property version 引擎版本号（取自 jar manifest 的 Implementation-Version）
 */
data class KteasyContext(
    val dialect: Dialect,
    val capabilities: List<String> = emptyList(),
    val dataDir: String,
    val version: String,
)
