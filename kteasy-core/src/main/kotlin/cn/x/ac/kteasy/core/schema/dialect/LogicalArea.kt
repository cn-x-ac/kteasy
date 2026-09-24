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
package cn.x.ac.kteasy.core.schema.dialect

/**
 * 逻辑存储区 → 物理命名空间的映射规则（【规格】§1.2；步骤卡 M1-02 设计要点 1）。
 *
 * PG 靠「schema 限定」隔离（引擎元数据 `md`、动态实体与 N2N `app`、迁移历史 `kteasy`），
 * MySQL 无 schema 概念（database==schema），改用「表名前缀」实现同一隔离意图。
 * 由 [NamespaceMapper] 据当前方言合成限定名，业务层只认逻辑区 + 逻辑名，禁自行拼 `md.`/`e_`。
 *
 * 注：M1-01 已建的六张元数据表在 PG 侧实际落在 `md` schema 下、物理名保留 `md_` 前缀
 * （如 `md.md_object`），故 [METADATA] 的 [mysqlPrefix] 为空——逻辑名本身即含 `md_`，
 * 只为 PG 追加 schema 限定。动态实体/N2N 表从 M1-03 起按本表前缀生成。
 *
 * @property pgSchema PG 侧限定用 schema（当前四区皆有，保留可空以防未来「同库无 schema」区）
 * @property pgPrefix PG 侧在 schema 内对表名追加的前缀（N2N 关联表用 `r_`，其余为空）
 * @property mysqlPrefix MySQL 侧表名前缀（元数据区为空，因逻辑名已带 `md_`）
 */
enum class LogicalArea(
    val pgSchema: String?,
    val pgPrefix: String,
    val mysqlPrefix: String,
) {
    /** 引擎元数据（`md` 区）；逻辑名本身已含 `md_`，两侧不再加前缀。 */
    METADATA(pgSchema = "md", pgPrefix = "", mysqlPrefix = ""),

    /** 动态实体数据表（表名＝实体标识）。 */
    ENTITY(pgSchema = "app", pgPrefix = "", mysqlPrefix = "e_"),

    /** N2N 关联表（PG `app.r_<id>`、MySQL `r_<id>`）。 */
    RELATION(pgSchema = "app", pgPrefix = "r_", mysqlPrefix = "r_"),

    /** 迁移版本表（Flyway 自身，业务运行期不直引；仅登记映射规则）。 */
    MIGRATION(pgSchema = "kteasy", pgPrefix = "", mysqlPrefix = "kteasy_"),

    /**
     * 引擎运行态表（M1-07 起）：随每次写操作变化的热数据，非声明式元数据（区别于 [METADATA]）。
     * PG 落 `kteasy` schema（与 V1 `kteasy_meta` 同区）、MySQL 平铺；逻辑名自带 `kteasy_` 前缀，两侧不加前缀。
     */
    ENGINE(pgSchema = "kteasy", pgPrefix = "", mysqlPrefix = ""),
}
