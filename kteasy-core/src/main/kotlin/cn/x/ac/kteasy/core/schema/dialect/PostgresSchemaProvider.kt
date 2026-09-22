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
 * PostgreSQL 方言实现（【清单】S8：PG 为**参考实现**，JSONB + GIN + CONCURRENTLY 全能力）。
 *
 * 纯字符串构造，零 JDBC 依赖（core 保持纯库；SQL 的执行与参数绑定归 query/schema 模块）。
 * 唯一强制约束：`CREATE INDEX CONCURRENTLY` 返回的语句 `runOutsideTransaction=true`，
 * 从接口产物层面杜绝「把在线建索引塞进事务」——由 M1-03 执行器在事务外单独跑。
 */
class PostgresSchemaProvider : SchemaProvider {
    override val namespace: NamespaceMapper = PgNamespace

    override val json: JsonOps = PgJsonOps

    override val index: IndexOps = PgIndexOps

    override val column: ColumnOps = PgColumnOps

    override val table: TableOps = PgTableOps

    override val introspection: IntrospectionOps = PgIntrospection

    override val upsert: UpsertFragment = PgUpsert

    override val lock: LockOps = PgLock

    override val lockingRead: LockingReadOps = PgLockingRead

    override val ddlTx: DdlTx = PgDdlTx

    override val fullText: FullText = PgFullText

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.JSON_GIN_INDEX,
            Capability.ONLINE_INDEX_NO_LOCK,
            Capability.TRANSACTIONAL_DDL,
            Capability.VIRTUAL_COLUMN_INDEX,
            Capability.INSTANT_ADD_COLUMN,
            Capability.SKIP_LOCKED,
        )

    override fun ledger(): List<CapabilityReport> =
        listOf(
            CapabilityReport(Capability.JSON_GIN_INDEX, CapabilityLevel.SUPPORTS, "GIN + jsonb_path_ops 承载 @> 包含与存在性"),
            CapabilityReport(
                Capability.JSON_MULTI_VALUED_INDEX,
                CapabilityLevel.DEGRADED,
                "PG 无 CAST AS ARRAY 多值索引；数组包含改由 GIN jsonb_path_ops 承载",
            ),
            CapabilityReport(Capability.ONLINE_INDEX_NO_LOCK, CapabilityLevel.SUPPORTS, "CREATE INDEX CONCURRENTLY（须事务外）"),
            CapabilityReport(Capability.TRANSACTIONAL_DDL, CapabilityLevel.SUPPORTS, "DDL 可回滚"),
            CapabilityReport(Capability.VIRTUAL_COLUMN_INDEX, CapabilityLevel.SUPPORTS, "PG18 VIRTUAL 生成列"),
            CapabilityReport(Capability.INSTANT_ADD_COLUMN, CapabilityLevel.SUPPORTS, "加可空列瞬时"),
            CapabilityReport(Capability.SKIP_LOCKED, CapabilityLevel.SUPPORTS, "FOR UPDATE SKIP LOCKED"),
        )
}

private object PgNamespace : NamespaceMapper {
    override fun qualified(
        area: LogicalArea,
        name: String,
    ): String {
        val schema = requireNotNull(area.pgSchema) { "PG 需 schema 限定，逻辑区 $area 无 schema" }
        return "$schema.${area.pgPrefix}$name"
    }
}

private object PgJsonOps : JsonOps {
    override fun extractText(
        column: String,
        path: JsonPath,
    ): Fragment = Fragment("($column #>> '${path.toPgArrayLiteral()}')")

    override fun extractTyped(
        column: String,
        path: JsonPath,
        cast: ValueCast,
    ): Fragment = Fragment("($column #>> '${path.toPgArrayLiteral()}')::${cast.toPgType()}")

    override fun predicateExists(
        column: String,
        path: JsonPath,
    ): Fragment = Fragment("($column #> '${path.toPgArrayLiteral()}' IS NOT NULL)")

    override fun arrayContains(
        column: String,
        path: JsonPath,
        value: String,
    ): Fragment {
        val doc = buildPgContainmentDoc(path, value)
        return Fragment("($column @> CAST(:$PG_ARR_PARAM AS jsonb))", mapOf(PG_ARR_PARAM to doc))
    }

    override fun removeKey(
        column: String,
        path: JsonPath,
    ): Fragment = Fragment("($column #- '${path.toPgArrayLiteral()}'::text[])")

    override fun bindJson(param: String): String = "CAST(:$param AS jsonb)"

    /** 由路径与标量值合成 `@>` 的包含文档（末段为数组）：如 `{a,b}`+`v` → `{"a":{"b":["v"]}}`。 */
    private fun buildPgContainmentDoc(
        path: JsonPath,
        value: String,
    ): String {
        val element = jsonQuote(value)
        var node = "[$element]"
        for (seg in path.segments.asReversed()) {
            node = "{${jsonQuote(seg)}:$node}"
        }
        return node
    }

    private const val PG_ARR_PARAM = "__kteasy_arr"
}

private object PgIndexOps : IndexOps {
    override fun createExpressionIndex(
        table: String,
        expressionSql: String,
        name: String,
        online: Boolean,
    ): List<DdlStatement> {
        val concurrently = if (online) "CONCURRENTLY " else ""
        // 表达式索引须双括号：外层是列清单、内层把表达式标成 expression。
        val stmt = "CREATE INDEX $concurrently$name ON $table (($expressionSql))"
        return listOf(DdlStatement(stmt, runOutsideTransaction = online))
    }

    override fun createJsonArrayIndex(
        table: String,
        column: String,
        path: JsonPath,
        name: String,
        online: Boolean,
    ): List<DdlStatement> {
        val concurrently = if (online) "CONCURRENTLY " else ""
        val stmt = "CREATE INDEX $concurrently$name ON $table USING gin ($column jsonb_path_ops)"
        return listOf(DdlStatement(stmt, runOutsideTransaction = online))
    }

    override fun createIndex(
        table: String,
        columns: List<String>,
        name: String,
        unique: Boolean,
        online: Boolean,
    ): List<DdlStatement> {
        // PG 唯一索引不支持 CONCURRENTLY；在线仅在非唯一时生效。
        val concurrent = online && !unique
        val head = if (unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
        val stmt = "$head${if (concurrent) " CONCURRENTLY" else ""} $name ON $table (${columns.joinToString(", ")})"
        return listOf(DdlStatement(stmt, runOutsideTransaction = concurrent))
    }

    override fun dropIndex(
        table: String,
        name: String,
        online: Boolean,
    ): DdlStatement {
        // PG 索引按 schema 内的名字删（非按表）；从已限定表名 `app.x` 取 schema 前缀拼出 `app.name`。
        val qualified = "${table.substringBefore('.')}.$name"
        val head = if (online) "DROP INDEX CONCURRENTLY" else "DROP INDEX"
        return DdlStatement("$head IF EXISTS $qualified", runOutsideTransaction = online)
    }
}

private object PgTableOps : TableOps {
    override fun createTable(spec: TableSpec): List<DdlStatement> {
        val out = mutableListOf<DdlStatement>()
        // 动态表落独立 schema（app/md/...）：先幂等确保其存在，MySQL 无此步。
        spec.area.pgSchema?.let { out += DdlStatement("CREATE SCHEMA IF NOT EXISTS $it") }
        val host = pgQualify(spec.area, spec.name)
        val parts = mutableListOf<String>()
        parts += spec.columns.map { it.toPgColumnDef() }
        spec.columns.filter { it.primaryKey }.takeIf { it.isNotEmpty() }?.let { pk ->
            parts += "PRIMARY KEY (${pk.joinToString(", ") { it.name }})"
        }
        spec.foreignKeys.forEach { parts += "CONSTRAINT ${it.name} ${pgFkClause(it)}" }
        spec.uniques.forEach { parts += "CONSTRAINT ${it.name} UNIQUE (${it.columns.joinToString(", ")})" }
        out += DdlStatement("CREATE TABLE $host (${parts.joinToString(", ")})")
        spec.indexes.forEach { idx ->
            out += DdlStatement("CREATE ${if (idx.unique) "UNIQUE " else ""}INDEX ${idx.name} ON $host (${idx.columns.joinToString(", ")})")
        }
        return out
    }

    override fun dropTable(
        area: LogicalArea,
        name: String,
    ): DdlStatement = DdlStatement("DROP TABLE IF EXISTS ${pgQualify(area, name)}")

    override fun addColumn(
        table: String,
        column: PhysicalColumn,
    ): List<DdlStatement> = listOf(DdlStatement("ALTER TABLE $table ADD COLUMN ${column.toPgColumnDef()}"))

    override fun addForeignKey(spec: ForeignKeySpec): DdlStatement = DdlStatement("ALTER TABLE ${pgQualify(spec.hostArea, spec.hostTable)} ADD CONSTRAINT ${spec.name} ${pgFkClause(spec)}")

    /** 逻辑区 + 逻辑名 → PG 限定名（与 [PgNamespace] 同规则，独立于此扩展点内的私有对象）。 */
    private fun pgQualify(
        area: LogicalArea,
        name: String,
    ): String = "${requireNotNull(area.pgSchema) { "PG 建表须有 schema" }}.${area.pgPrefix}$name"

    /** 外键子句体：`FOREIGN KEY (col) REFERENCES <限定宿主> (refcol)`。 */
    private fun pgFkClause(spec: ForeignKeySpec): String = "FOREIGN KEY (${spec.column}) REFERENCES ${pgQualify(spec.refArea, spec.refTable)} (${spec.refColumn})"
}

private object PgIntrospection : IntrospectionOps {
    override fun tableExists(
        area: LogicalArea,
        name: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = :$S AND table_name = :$T",
            mapOf(S to area.pgSchema, T to area.pgPrefix + name),
        )

    override fun columnExists(
        area: LogicalArea,
        table: String,
        column: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = :$S AND table_name = :$T AND column_name = :$C",
            mapOf(S to area.pgSchema, T to area.pgPrefix + table, C to column),
        )

    override fun indexExists(
        area: LogicalArea,
        table: String,
        index: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = :$S AND tablename = :$T AND indexname = :$I",
            mapOf(S to area.pgSchema, T to area.pgPrefix + table, I to index),
        )

    override fun foreignKeyExists(
        area: LogicalArea,
        table: String,
        constraint: String,
    ): Fragment =
        Fragment(
            "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = :$S AND table_name = :$T AND constraint_name = :$I AND constraint_type = 'FOREIGN KEY'",
            mapOf(S to area.pgSchema, T to area.pgPrefix + table, I to constraint),
        )

    override fun listColumns(
        area: LogicalArea,
        table: String,
    ): Fragment =
        Fragment(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = :$S AND table_name = :$T ORDER BY ordinal_position",
            mapOf(S to area.pgSchema, T to area.pgPrefix + table),
        )

    private const val S = "__kteasy_s"
    private const val T = "__kteasy_t"
    private const val C = "__kteasy_c"
    private const val I = "__kteasy_i"
}

/** PG 单列定义（含 NOT NULL / DEFAULT）；主键不在此内联，改由表级 `PRIMARY KEY` 约束。 */
private fun PhysicalColumn.toPgColumnDef(): String {
    val sb = StringBuilder("$name ${type.toPgType(length, scale)}")
    if (binaryCollation) sb.append(" COLLATE \"C\"")
    if (!nullable && !primaryKey) sb.append(" NOT NULL")
    when (val d = default) {
        null -> Unit
        ColumnDefault.Now -> sb.append(" DEFAULT now()")
        ColumnDefault.Zero -> sb.append(" DEFAULT 0")
        is ColumnDefault.Literal -> sb.append(" DEFAULT ${sqlLiteral(d.text)}")
    }
    return sb.toString()
}

/** 由 [ColumnType]（可含 [length]）渲染 PG 列类型。 */
private fun ColumnType.toPgType(
    length: Int?,
    scale: Int?,
): String =
    when (this) {
        ColumnType.VARCHAR -> "varchar(${requireNotNull(length) { "VARCHAR 须带 length" }})"
        ColumnType.TEXT -> "text"
        ColumnType.BIGINT -> "bigint"
        ColumnType.INTEGER -> "integer"
        ColumnType.BOOLEAN -> "boolean"
        ColumnType.TIMESTAMP -> "timestamptz"
        ColumnType.DATE -> "date"
        ColumnType.DECIMAL -> "numeric(${requireNotNull(length) { "DECIMAL 须带 precision" }}, ${requireNotNull(scale) { "DECIMAL 须带 scale" }})"
        ColumnType.JSON -> "jsonb"
    }

private object PgColumnOps : ColumnOps {
    override fun addNullableColumn(
        table: String,
        name: String,
        cast: ValueCast,
    ): List<DdlStatement> = listOf(DdlStatement("ALTER TABLE $table ADD COLUMN $name ${cast.toPgType()} NULL"))

    override fun addVirtualColumn(
        table: String,
        name: String,
        cast: ValueCast,
        expressionSql: String,
        index: Boolean,
    ): List<DdlStatement> {
        val out =
            mutableListOf(
                DdlStatement("ALTER TABLE $table ADD COLUMN $name ${cast.toPgType()} GENERATED ALWAYS AS ($expressionSql) VIRTUAL"),
            )
        if (index) {
            out += DdlStatement("CREATE INDEX ${name}_ix ON $table ($name)")
        }
        return out
    }

    override fun dropColumn(
        table: String,
        name: String,
    ): DdlStatement = DdlStatement("ALTER TABLE $table DROP COLUMN $name")
}

private object PgUpsert : UpsertFragment {
    override fun supportsReturning(): Boolean = true

    override fun build(
        table: String,
        columns: List<String>,
        conflictColumns: List<String>,
        updateColumns: List<String>,
        returning: String?,
    ): String {
        require(columns.isNotEmpty()) { "upsert 至少一列" }
        require(conflictColumns.isNotEmpty()) { "PG ON CONFLICT 需冲突目标列" }
        val colList = columns.joinToString(", ")
        val valList = columns.joinToString(", ") { ":$it" }
        val sb = StringBuilder("INSERT INTO $table ($colList) VALUES ($valList)")
        sb.append(" ON CONFLICT (${conflictColumns.joinToString(", ")})")
        if (updateColumns.isEmpty()) {
            sb.append(" DO NOTHING")
        } else {
            sb.append(" DO UPDATE SET ")
            sb.append(updateColumns.joinToString(", ") { "$it = EXCLUDED.$it" })
        }
        if (!returning.isNullOrBlank()) {
            sb.append(" RETURNING $returning")
        }
        return sb.toString()
    }
}

private object PgLock : LockOps {
    override fun lock(key: LockKey): Fragment = Fragment("SELECT pg_advisory_lock(:$LOCK_ID)", mapOf(LOCK_ID to key.id))

    // PG try-lock 为「立即返回」语义（不支持秒级等待），timeout 参数被忽略——两库差异经台账如实暴露。
    override fun tryLock(
        key: LockKey,
        timeoutSeconds: Int,
    ): Fragment = Fragment("SELECT pg_try_advisory_lock(:$LOCK_ID)", mapOf(LOCK_ID to key.id))

    override fun unlock(key: LockKey): Fragment = Fragment("SELECT pg_advisory_unlock(:$LOCK_ID)", mapOf(LOCK_ID to key.id))

    private const val LOCK_ID = "__kteasy_lock_id"
}

private object PgLockingRead : LockingReadOps {
    override fun forUpdate(skipLocked: Boolean): String = if (skipLocked) "FOR UPDATE SKIP LOCKED" else "FOR UPDATE"
}

private object PgDdlTx : DdlTx {
    override fun supportsTransactionalDDL(): Boolean = true
}

private object PgFullText : FullText {
    override fun likePredicate(
        column: String,
        param: String,
    ): Fragment = Fragment("(lower($column) LIKE lower(:$param))")
}

/** 路径段 → PG 文本数组字面量 `{a,b}`（`#>`/`#>>` 要求；段为受校验的标识符）。 */
internal fun JsonPath.toPgArrayLiteral(): String = segments.joinToString(",", "{", "}")

/** [ValueCast] → PG 类型名（加速/落库用；M1-04 完整 26 型 DDL 映射另行落地）。 */
internal fun ValueCast.toPgType(): String =
    when (this) {
        ValueCast.BOOL -> "boolean"
        ValueCast.LONG -> "bigint"
        ValueCast.DOUBLE -> "numeric"
        ValueCast.TEXT -> "text"
        ValueCast.DATE -> "date"
        ValueCast.TIMESTAMP -> "timestamptz"
    }

/** 最小 JSON 字符串转义（够标识符与常见标量用；不含代理对精细处理）。 */
internal fun jsonQuote(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.append("\"").toString()
}
