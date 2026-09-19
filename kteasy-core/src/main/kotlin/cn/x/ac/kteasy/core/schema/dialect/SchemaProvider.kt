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

/*
 * 方言 SPI 接口面（步骤卡 M1-02 / 模块图纸 02 §1）。**契约冻结件**：改任一签名＝触发停止点上报。
 *
 * 目标是把「两库差异」全部圈进这一处扩展点——此后任何业务代码（meta/data/query/automation）
 * 都不得出现方言分支（红线⑤），也不得自行拼 SQL 字面量（红线④）。所有方法只**产出** [Fragment]
 * /[DdlStatement]/限定名/子句文本，绝不执行、绝不 import JDBC 驱动（core 保持纯库）。
 * 业务层注入聚合口 [SchemaProvider]，只经各子接口取方言产物。
 */

/** 逻辑区 + 逻辑名 → 物理限定名（PG schema 限定 / MySQL 前缀）。 */
interface NamespaceMapper {
    /**
     * @param area 逻辑存储区
     * @param name 逻辑表名（元数据区沿用 M1-01 物理基名如 `md_object`；动态表传实体标识）
     * @return 该方言下可直接进 SQL 的限定表名
     */
    fun qualified(
        area: LogicalArea,
        name: String,
    ): String
}

/** JSON（`ext`）读写与判定的方言封装。 */
interface JsonOps {
    /** 取路径处的文本（PG `#>>` / MySQL `->>`+`JSON_UNQUOTE`）；无绑定参数。 */
    fun extractText(
        column: String,
        path: JsonPath,
    ): Fragment

    /** 取值并按 [cast] 解释类型（两库据此对齐「JSON 数字 vs 字符串」语义）。 */
    fun extractTyped(
        column: String,
        path: JsonPath,
        cast: ValueCast,
    ): Fragment

    /** 路径存在性谓词（PG `#>` IS NOT NULL / MySQL `JSON_CONTAINS_PATH`）。 */
    fun predicateExists(
        column: String,
        path: JsonPath,
    ): Fragment

    /**
     * 数组成员判定（可作 WHERE 条件）。
     *
     * PG 走 `col @> <包含文档>`（承载于 GIN jsonb_path_ops）；MySQL 走 `:v MEMBER OF (JSON_EXTRACT(...))`
     * （承载于多值索引）。[value] 为被查找的标量；两库结果等价但执行计划各异，命中性由集成测试 EXPLAIN 断言。
     */
    fun arrayContains(
        column: String,
        path: JsonPath,
        value: String,
    ): Fragment

    /**
     * 删除 [path] 处键后的**新值表达式**（无绑定参数；路径按字面量内联，段值来源受控元数据 api_name）。
     * 供物理化 `CLEAN_EXT_KEY` 步回填后清理旧 ext key（PG `col #- '{a,b}'::text[]`、MySQL `JSON_REMOVE`）。
     * 调用方负责仅对「目标列已回填非空」的行施加，避免误删未迁移数据。
     */
    fun removeKey(
        column: String,
        path: JsonPath,
    ): Fragment

    /** 写入 JSON 值的占位符：PG 需 `CAST(:param AS jsonb)`，MySQL 直接 `:param`（收编 M1-01 [MdNamespace] 过渡债）。 */
    fun bindJson(param: String): String
}

/** 索引创建的方言封装（在线变更 + JSON 加速）。 */
interface IndexOps {
    /**
     * 表达式索引（热字段加速 S7 档一）。PG 在线＝`CREATE INDEX CONCURRENTLY`（返回语句强制
     * [DdlStatement.runOutsideTransaction]=true）；MySQL 在线=`ALGORITHM=INPLACE, LOCK=NONE`。
     *
     * @param expressionSql 已被命名空间/JSON 层限定、不含占位符的索引表达式（调用方经 [JsonOps] 取得 `.sql`）
     */
    fun createExpressionIndex(
        table: String,
        expressionSql: String,
        name: String,
        online: Boolean,
    ): List<DdlStatement>

    /**
     * JSON 数组索引：PG `USING gin (col jsonb_path_ops)`；MySQL 多值索引
     * `((CAST(col->'$.path' AS CHAR(n) ARRAY)))`。承载 [JsonOps.arrayContains] 的命中路径。
     */
    fun createJsonArrayIndex(
        table: String,
        column: String,
        path: JsonPath,
        name: String,
        online: Boolean,
    ): List<DdlStatement>

    /**
     * 普通/唯一索引（真列加速，如 DICT 路径列的左前缀 btree）。
     *
     * PG `CREATE [UNIQUE] INDEX [CONCURRENTLY]`（在线时 `runOutsideTransaction=true`；注意 PG 唯一索引不支持
     * CONCURRENTLY，[unique]=true 时实现须忽略 online）；MySQL `CREATE INDEX` 或建表内 UNIQUE。
     * [table] 已由 [NamespaceMapper] 限定；[columns] 为普通列名清单（非表达式）。
     */
    fun createIndex(
        table: String,
        columns: List<String>,
        name: String,
        unique: Boolean,
        online: Boolean,
    ): List<DdlStatement>

    /** 删索引（S7 档一热字段回退 / ADD_INDEX_EXPR 的逆步）。幂等由执行器 precheck 探存再发。 */
    fun dropIndex(
        table: String,
        name: String,
        online: Boolean,
    ): DdlStatement
}

/** 列变更的方言封装（在线加列 / 生成列 / 删列）。 */
interface ColumnOps {
    /**
     * 加可空列。返回**按优先级排列**的候选语句：MySQL 首选 `ALGORITHM=INSTANT`、次选 `INPLACE`
     * （探测失败由 M1-03 执行器逐条回退）；PG 单条即成（其 DDL 事务安全）。
     */
    fun addNullableColumn(
        table: String,
        name: String,
        cast: ValueCast,
    ): List<DdlStatement>

    /**
     * 加 VIRTUAL 生成列（可选同时建索引）。PG18 `GENERATED ALWAYS AS (...) VIRTUAL`；
     * MySQL `... VIRTUAL`（8.0 支持生成列二级索引）。[index] 为真时追加对应普通索引语句。
     */
    fun addVirtualColumn(
        table: String,
        name: String,
        cast: ValueCast,
        expressionSql: String,
        index: Boolean,
    ): List<DdlStatement>

    /** 删列。 */
    fun dropColumn(
        table: String,
        name: String,
    ): DdlStatement
}

/**
 * 表/约束级 DDL 的方言封装（步骤卡 M1-03 物化引擎消费）。只产出语句、绝不执行（红线④⑤）。
 *
 * `CREATE_TABLE` 与 `CREATE_RTABLE` 共用 [createTable]（差异仅在 [TableSpec.area]）。PG 侧动态表落 `app`
 * schema，故实现须在首个建表前保证 `CREATE SCHEMA IF NOT EXISTS app`（幂等，MySQL 无此步）。
 */
interface TableOps {
    /** 建表：返回**有序**语句序列（PG 首条可能为确保 schema 存在，随后单条 `CREATE TABLE`）。 */
    fun createTable(spec: TableSpec): List<DdlStatement>

    /**
     * 为已存在表加一列（真列化：REF 引用列 / DICT 路径列 / ANYREF 伴生列 / 物理化字段）。
     *
     * 与 [ColumnOps.addNullableColumn] 的区别：这里按 [PhysicalColumn] 的显式类型（如 `varchar(32)`）建列——
     * 引用列须定长才能挂 FK（MySQL TEXT 不可作外键列）。返回**按优先级排列**的候选：MySQL 首选
     * `ALGORITHM=INSTANT`、次选 `INPLACE`（执行器逐条探测回退）；PG 单条即成。[table] 已限定。
     */
    fun addColumn(
        table: String,
        column: PhysicalColumn,
    ): List<DdlStatement>

    /** 删表（`DROP TABLE IF EXISTS`，幂等）。破坏性由执行器的引用检查把关。 */
    fun dropTable(
        area: LogicalArea,
        name: String,
    ): DdlStatement

    /**
     * 为已存在表补一条外键约束（`ADD_FK_COLUMN` 步的 FK 半程，与 [ColumnOps.addNullableColumn] 组合）。
     * 单条即成（MySQL 加 FK 只能 INPLACE，无 INSTANT 档）。
     */
    fun addForeignKey(spec: ForeignKeySpec): DdlStatement
}

/**
 * 结构存在性探测（幂等 precheck/postcheck 的方言出口，M1-03 执行器据此判断「这步是否已生效」）。
 *
 * 一律返回 `SELECT COUNT(*) ...` 形态的 [Fragment]（具名占位符绑定表/列/索引/约束名），执行器按
 * `count > 0` 判存在——两库经 `information_schema`（PG 索引用 `pg_indexes`）各自成型，探测 SQL 的方言
 * 差异被圈在这里，执行器侧无 `if (isMySQL)`（红线⑤）。
 */
interface IntrospectionOps {
    fun tableExists(
        area: LogicalArea,
        name: String,
    ): Fragment

    fun columnExists(
        area: LogicalArea,
        table: String,
        column: String,
    ): Fragment

    fun indexExists(
        area: LogicalArea,
        table: String,
        index: String,
    ): Fragment

    fun foreignKeyExists(
        area: LogicalArea,
        table: String,
        constraint: String,
    ): Fragment

    /**
     * 列出某表当前全部物理列名（diff 取「已物化列集合」用，免去逐列探测）。
     *
     * 返回单列结果集（列名 `column_name`）的 `SELECT` [Fragment]；[table] 传逻辑表名，实现按 [area] 现算限定。
     */
    fun listColumns(
        area: LogicalArea,
        table: String,
    ): Fragment
}

/** upsert 子句的方言封装。 */
interface UpsertFragment {
    /** 是否支持 `... RETURNING`（PG true；MySQL 走 `LAST_INSERT_ID()` 技巧供取号）。 */
    fun supportsReturning(): Boolean

    /**
     * 组装完整 INSERT..冲突更新语句（值用 `:列名` 具名占位符，由调用方绑定）。
     * PG `ON CONFLICT (keys) DO UPDATE SET c=EXCLUDED.c [RETURNING returning]`；
     * MySQL `ON DUPLICATE KEY UPDATE c=VALUES(c)`（无 RETURNING，[returning] 非空时忽略并由 [supportsReturning] 说明）。
     */
    fun build(
        table: String,
        columns: List<String>,
        conflictColumns: List<String>,
        updateColumns: List<String>,
        returning: String? = null,
    ): String
}

/** 命名锁的方言封装（M1-06 写入通道锁键用）。 */
interface LockOps {
    /** 阻塞式获取。PG `pg_advisory_lock(:id)`；MySQL `GET_LOCK(:name, -1)`（无限等待）。 */
    fun lock(key: LockKey): Fragment

    /** 带超时尝试。返回 1/true＝成功。PG `pg_try_advisory_lock(:id)`；MySQL `GET_LOCK(:name, :timeout)`。 */
    fun tryLock(
        key: LockKey,
        timeoutSeconds: Int,
    ): Fragment

    /** 释放。PG `pg_advisory_unlock(:id)`；MySQL `RELEASE_LOCK(:name)`。 */
    fun unlock(key: LockKey): Fragment
}

/** 加锁读的方言封装。 */
interface LockingReadOps {
    /** 行锁子句（拼在 SELECT 末尾）。[skipLocked] 为真时两库均加 `SKIP LOCKED`（受 [Capability.SKIP_LOCKED] 保护）。 */
    fun forUpdate(skipLocked: Boolean): String
}

/** DDL 事务语义的方言声明（M1-03 物化引擎据此决定补偿强度）。 */
interface DdlTx {
    /** 该库 DDL 是否可回滚。PG true；MySQL false（隐式提交）。 */
    fun supportsTransactionalDDL(): Boolean

    /** 是否强制「分步落进度」的作业状态机。默认＝[supportsTransactionalDDL] 取反（MySQL 需要，M1-03 地基）。 */
    fun stepCheckpointRequired(): Boolean = !supportsTransactionalDDL()
}

/**
 * 全文/模糊检索的方言封装。
 *
 * ⟨可逆⟩：真全文检索（PG `pg_trgm`/`tsvector`、MySQL `ngram`）归 M5；M1-02 只落地双方言
 * `LIKE` 兜底谓词，接口先行以固定扩展点，避免 M5 落地时改上层签名。
 */
interface FullText {
    /** LIKE 兜底谓词：`lower(col) LIKE lower(:param)`，绑定值由调用方加 `%` 通配与转义。 */
    fun likePredicate(
        column: String,
        param: String,
    ): Fragment
}

/**
 * 聚合口：业务层唯一注入的方言扩展点。启动时按 `kteasy.db.dialect` 选 pg/mysql 实现（`KteasyKernelConfig`），
 * capability 快照灌进 [cn.x.ac.kteasy.core.kernel.KteasyContext.capabilities] 与健康端点。
 */
interface SchemaProvider {
    val namespace: NamespaceMapper
    val json: JsonOps
    val index: IndexOps
    val column: ColumnOps
    val table: TableOps
    val introspection: IntrospectionOps
    val upsert: UpsertFragment
    val lock: LockOps
    val lockingRead: LockingReadOps
    val ddlTx: DdlTx
    val fullText: FullText

    /** 本方言「原生支持」的能力集（进 health 快照，保持扁平字符串语义）。 */
    fun capabilities(): Set<Capability>

    /** 全量能力台账（含 DEGRADED/ABSENT 与说明）：进 `GET /api/md/capabilities` 与 golden-file 防漂移。 */
    fun ledger(): List<CapabilityReport>
}
