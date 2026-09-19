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
 * MySQL 方言实现（【清单】S8：对 PG 参考实现做「对应处理」，能力缺口经台账如实降级，不假装等价）。
 *
 * 与 PG 版的三处本质差异都在接口产物里编码：① 无事务性 DDL（[capabilities] 不含
 * [Capability.TRANSACTIONAL_DDL]，[ddlTx] 返 false → M1-03 强制作业断点）；② 无 JSON 整体索引
 * （数组包含改走多值索引 + `MEMBER OF`，存在性大表慢标 [CapabilityLevel.ABSENT]）；
 * ③ 在线变更用 `ALGORITHM=INPLACE, LOCK=NONE` 而非 `CONCURRENTLY`（[DdlStatement.runOutsideTransaction]
 * 全 false，MySQL DDL 本就隐式提交、无「事务外」概念）。
 */
class MySqlSchemaProvider : SchemaProvider {
    override val namespace: NamespaceMapper = MyNamespace

    override val json: JsonOps = MyJsonOps

    override val index: IndexOps = MyIndexOps

    override val column: ColumnOps = MyColumnOps

    override val table: TableOps = MyTableOps

    override val introspection: IntrospectionOps = MyIntrospection

    override val upsert: UpsertFragment = MyUpsert

    override val lock: LockOps = MyLock

    override val lockingRead: LockingReadOps = MyLockingRead

    override val ddlTx: DdlTx = MyDdlTx

    override val fullText: FullText = MyFullText

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.JSON_MULTI_VALUED_INDEX,
            Capability.VIRTUAL_COLUMN_INDEX,
            Capability.INSTANT_ADD_COLUMN,
            Capability.SKIP_LOCKED,
        )

    override fun ledger(): List<CapabilityReport> =
        listOf(
            CapabilityReport(Capability.JSON_GIN_INDEX, CapabilityLevel.ABSENT, "无 JSON 整体索引；存在性/包含走多值索引或函数索引，大表存在性查询慢"),
            CapabilityReport(Capability.JSON_MULTI_VALUED_INDEX, CapabilityLevel.SUPPORTS, "8.0.17+ CAST AS ARRAY 多值索引"),
            CapabilityReport(Capability.ONLINE_INDEX_NO_LOCK, CapabilityLevel.DEGRADED, "INPLACE,LOCK=NONE，无 PG 式 CONCURRENTLY 窗口保证；失败降级 COPY"),
            CapabilityReport(Capability.TRANSACTIONAL_DDL, CapabilityLevel.ABSENT, "DDL 隐式提交 → 物化作业强制分步断点续跑"),
            CapabilityReport(Capability.VIRTUAL_COLUMN_INDEX, CapabilityLevel.SUPPORTS, "生成列 + 二级索引，成熟"),
            CapabilityReport(Capability.INSTANT_ADD_COLUMN, CapabilityLevel.SUPPORTS, "8.0.12+ ALGORITHM=INSTANT（限表末尾、不可混操作）"),
            CapabilityReport(Capability.SKIP_LOCKED, CapabilityLevel.SUPPORTS, "8.0 FOR UPDATE SKIP LOCKED"),
        )
}

private object MyNamespace : NamespaceMapper {
    override fun qualified(
        area: LogicalArea,
        name: String,
    ): String = area.mysqlPrefix + name
}

private object MyJsonOps : JsonOps {
    override fun extractText(
        column: String,
        path: JsonPath,
    ): Fragment = Fragment("JSON_UNQUOTE(JSON_EXTRACT($column, '${path.toMySqlJsonPath()}'))")

    override fun extractTyped(
        column: String,
        path: JsonPath,
        cast: ValueCast,
    ): Fragment {
        val text = "JSON_UNQUOTE(JSON_EXTRACT($column, '${path.toMySqlJsonPath()}'))"
        val sql =
            when (cast) {
                ValueCast.TEXT -> text
                ValueCast.BOOL -> "($text = 'true')"
                ValueCast.LONG -> "CAST($text AS SIGNED)"
                ValueCast.DOUBLE -> "CAST($text AS DECIMAL(30, 10))"
                ValueCast.DATE -> "CAST($text AS DATE)"
                ValueCast.TIMESTAMP -> "CAST($text AS DATETIME)"
            }
        return Fragment(sql)
    }

    override fun predicateExists(
        column: String,
        path: JsonPath,
    ): Fragment = Fragment("JSON_CONTAINS_PATH($column, 'one', '${path.toMySqlJsonPath()}')")

    override fun arrayContains(
        column: String,
        path: JsonPath,
        value: String,
    ): Fragment =
        Fragment(
            "(:$MY_OF_PARAM MEMBER OF (JSON_EXTRACT($column, '${path.toMySqlJsonPath()}')))",
            mapOf(MY_OF_PARAM to value),
        )

    override fun removeKey(
        column: String,
        path: JsonPath,
    ): Fragment = Fragment("JSON_REMOVE($column, '${path.toMySqlJsonPath()}')")

    override fun bindJson(param: String): String = ":$param"

    private const val MY_OF_PARAM = "__kteasy_of"
}

private object MyIndexOps : IndexOps {
    override fun createExpressionIndex(
        table: String,
        expressionSql: String,
        name: String,
        online: Boolean,
    ): List<DdlStatement> {
        val tail = if (online) " ALGORITHM=INPLACE, LOCK=NONE" else ""
        return listOf(DdlStatement("CREATE INDEX $name ON $table (($expressionSql))$tail"))
    }

    override fun createJsonArrayIndex(
        table: String,
        column: String,
        path: JsonPath,
        name: String,
        online: Boolean,
    ): List<DdlStatement> {
        val tail = if (online) ", ALGORITHM=INPLACE, LOCK=NONE" else ""
        val expr = "CAST($column -> '${path.toMySqlJsonPath()}' AS CHAR($MY_MULTI_VAL_LEN) ARRAY)"
        return listOf(DdlStatement("ALTER TABLE $table ADD INDEX $name (($expr))$tail"))
    }

    override fun createIndex(
        table: String,
        columns: List<String>,
        name: String,
        unique: Boolean,
        online: Boolean,
    ): List<DdlStatement> {
        val kind = if (unique) "UNIQUE INDEX" else "INDEX"
        val tail = if (online) ", ALGORITHM=INPLACE, LOCK=NONE" else ""
        return listOf(DdlStatement("ALTER TABLE $table ADD $kind $name (${columns.joinToString(", ")})$tail"))
    }

    override fun dropIndex(
        table: String,
        name: String,
        online: Boolean,
    ): DdlStatement {
        val tail = if (online) ", ALGORITHM=INPLACE, LOCK=NONE" else ""
        return DdlStatement("ALTER TABLE $table DROP INDEX $name$tail")
    }
}

private object MyColumnOps : ColumnOps {
    override fun addNullableColumn(
        table: String,
        name: String,
        cast: ValueCast,
    ): List<DdlStatement> {
        val col = "$name ${cast.toMySqlColumnType()} NULL"
        // 首选 INSTANT（表末尾加可空列），次选 INPLACE,LOCK=NONE 兜底——执行器逐条探测回退（M1-03）。
        return listOf(
            DdlStatement("ALTER TABLE $table ADD COLUMN $col, ALGORITHM=INSTANT"),
            DdlStatement("ALTER TABLE $table ADD COLUMN $col, ALGORITHM=INPLACE, LOCK=NONE"),
        )
    }

    override fun addVirtualColumn(
        table: String,
        name: String,
        cast: ValueCast,
        expressionSql: String,
        index: Boolean,
    ): List<DdlStatement> {
        val out =
            mutableListOf(
                DdlStatement("ALTER TABLE $table ADD COLUMN $name ${cast.toMySqlColumnType()} GENERATED ALWAYS AS ($expressionSql) VIRTUAL"),
            )
        if (index) {
            out += DdlStatement("ALTER TABLE $table ADD INDEX ${name}_ix ($name)")
        }
        return out
    }

    override fun dropColumn(
        table: String,
        name: String,
    ): DdlStatement = DdlStatement("ALTER TABLE $table DROP COLUMN $name")
}

private object MyTableOps : TableOps {
    override fun createTable(spec: TableSpec): List<DdlStatement> {
        val host = spec.area.mysqlPrefix + spec.name
        val parts = mutableListOf<String>()
        parts += spec.columns.map { it.toMySqlColumnDef() }
        spec.columns.filter { it.primaryKey }.takeIf { it.isNotEmpty() }?.let { pk ->
            parts += "PRIMARY KEY (${pk.joinToString(", ") { it.name }})"
        }
        spec.uniques.forEach { parts += "UNIQUE INDEX ${it.name} (${it.columns.joinToString(", ")})" }
        spec.indexes.forEach { parts += "${if (it.unique) "UNIQUE " else ""}INDEX ${it.name} (${it.columns.joinToString(", ")})" }
        spec.foreignKeys.forEach { parts += "CONSTRAINT ${it.name} ${myFkClause(it)}" }
        val stmt = "CREATE TABLE $host (${parts.joinToString(", ")}) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4"
        return listOf(DdlStatement(stmt))
    }

    override fun dropTable(
        area: LogicalArea,
        name: String,
    ): DdlStatement = DdlStatement("DROP TABLE IF EXISTS ${area.mysqlPrefix}$name")

    override fun addColumn(
        table: String,
        column: PhysicalColumn,
    ): List<DdlStatement> {
        val col = column.toMySqlColumnDef()
        // 首选 INSTANT（表末尾加列、可空免锁），次选 INPLACE,LOCK=NONE 兜底——执行器逐条探测回退（M1-03）。
        return listOf(
            DdlStatement("ALTER TABLE $table ADD COLUMN $col, ALGORITHM=INSTANT"),
            DdlStatement("ALTER TABLE $table ADD COLUMN $col, ALGORITHM=INPLACE, LOCK=NONE"),
        )
    }

    override fun addForeignKey(spec: ForeignKeySpec): DdlStatement = DdlStatement("ALTER TABLE ${spec.hostArea.mysqlPrefix}${spec.hostTable} ADD CONSTRAINT ${spec.name} ${myFkClause(spec)}")

    private fun myFkClause(spec: ForeignKeySpec): String = "FOREIGN KEY (${spec.column}) REFERENCES ${spec.refArea.mysqlPrefix}${spec.refTable} (${spec.refColumn})"
}

private object MyIntrospection : IntrospectionOps {
    override fun tableExists(
        area: LogicalArea,
        name: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = :$T",
            mapOf(T to area.mysqlPrefix + name),
        )

    override fun columnExists(
        area: LogicalArea,
        table: String,
        column: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :$T AND column_name = :$C",
            mapOf(T to area.mysqlPrefix + table, C to column),
        )

    override fun indexExists(
        area: LogicalArea,
        table: String,
        index: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = :$T AND index_name = :$I",
            mapOf(T to area.mysqlPrefix + table, I to index),
        )

    override fun foreignKeyExists(
        area: LogicalArea,
        table: String,
        constraint: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = :$T AND constraint_name = :$I AND constraint_type = 'FOREIGN KEY'",
            mapOf(T to area.mysqlPrefix + table, I to constraint),
        )

    override fun listColumns(
        area: LogicalArea,
        table: String,
    ): Fragment =
        Fragment(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :$T ORDER BY ordinal_position",
            mapOf(T to area.mysqlPrefix + table),
        )

    private const val T = "__kteasy_t"
    private const val C = "__kteasy_c"
    private const val I = "__kteasy_i"
}

/** MySQL 单列定义（含 NOT NULL / DEFAULT）；主键改由表级 `PRIMARY KEY` 约束。 */
private fun PhysicalColumn.toMySqlColumnDef(): String {
    val sb = StringBuilder("$name ${type.toMySqlType(length)}")
    if (binaryCollation) sb.append(" COLLATE utf8mb4_bin")
    if (!nullable && !primaryKey) sb.append(" NOT NULL")
    when (val d = default) {
        null -> Unit
        ColumnDefault.Now -> sb.append(" DEFAULT CURRENT_TIMESTAMP(6)")
        ColumnDefault.Zero -> sb.append(" DEFAULT 0")
        is ColumnDefault.Literal -> sb.append(" DEFAULT ${sqlLiteral(d.text)}")
    }
    return sb.toString()
}

/** 由 [ColumnType]（可含 [length]）渲染 MySQL 列类型。 */
private fun ColumnType.toMySqlType(length: Int?): String =
    when (this) {
        ColumnType.VARCHAR -> "varchar(${requireNotNull(length) { "VARCHAR 须带 length" }})"
        ColumnType.TEXT -> "longtext"
        ColumnType.BIGINT -> "bigint"
        ColumnType.INTEGER -> "int"
        ColumnType.BOOLEAN -> "boolean"
        ColumnType.TIMESTAMP -> "datetime(6)"
        ColumnType.JSON -> "json"
    }

private object MyUpsert : UpsertFragment {
    override fun supportsReturning(): Boolean = false

    override fun build(
        table: String,
        columns: List<String>,
        conflictColumns: List<String>,
        updateColumns: List<String>,
        returning: String?,
    ): String {
        require(columns.isNotEmpty()) { "upsert 至少一列" }
        val colList = columns.joinToString(", ")
        val valList = columns.joinToString(", ") { ":$it" }
        val sb = StringBuilder("INSERT INTO $table ($colList) VALUES ($valList)")
        if (updateColumns.isNotEmpty()) {
            sb.append(" ON DUPLICATE KEY UPDATE ")
            sb.append(updateColumns.joinToString(", ") { "$it = VALUES($it)" })
        }
        // MySQL 无 RETURNING；取号靠 LAST_INSERT_ID() 技巧，由写入层（M1-06）处理，此处忽略 returning。
        return sb.toString()
    }
}

private object MyLock : LockOps {
    override fun lock(key: LockKey): Fragment = Fragment("SELECT GET_LOCK(:$LOCK_NAME, -1)", mapOf(LOCK_NAME to key.name))

    override fun tryLock(
        key: LockKey,
        timeoutSeconds: Int,
    ): Fragment =
        Fragment(
            "SELECT GET_LOCK(:$LOCK_NAME, :$LOCK_TIMEOUT)",
            mapOf(LOCK_NAME to key.name, LOCK_TIMEOUT to timeoutSeconds),
        )

    override fun unlock(key: LockKey): Fragment = Fragment("SELECT RELEASE_LOCK(:$LOCK_NAME)", mapOf(LOCK_NAME to key.name))

    private const val LOCK_NAME = "__kteasy_lock_name"
    private const val LOCK_TIMEOUT = "__kteasy_lock_timeout"
}

private object MyLockingRead : LockingReadOps {
    override fun forUpdate(skipLocked: Boolean): String = if (skipLocked) "FOR UPDATE SKIP LOCKED" else "FOR UPDATE"
}

private object MyDdlTx : DdlTx {
    override fun supportsTransactionalDDL(): Boolean = false
}

private object MyFullText : FullText {
    override fun likePredicate(
        column: String,
        param: String,
    ): Fragment = Fragment("(lower($column) LIKE lower(:$param))")
}

/** 路径段 → MySQL JSON 路径 `$.a.b`。 */
internal fun JsonPath.toMySqlJsonPath(): String = "$." + segments.joinToString(".")

/** [ValueCast] → MySQL 列类型（DDL 用；CAST 类型另有其表，见 [MyJsonOps.extractTyped]）。 */
internal fun ValueCast.toMySqlColumnType(): String =
    when (this) {
        ValueCast.BOOL -> "BOOLEAN"
        ValueCast.LONG -> "BIGINT"
        ValueCast.DOUBLE -> "DOUBLE"
        ValueCast.TEXT -> "TEXT"
        ValueCast.DATE -> "DATE"
        ValueCast.TIMESTAMP -> "DATETIME(6)"
    }

private const val MY_MULTI_VAL_LEN = 255
