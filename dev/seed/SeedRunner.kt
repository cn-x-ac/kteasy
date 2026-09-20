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
package cn.x.ac.kteasy.dev.seed

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/*
 * 50w 级实体表造数脚本（步骤卡 M1-03 · Block F 性能证据用）。以集合式 SQL 一次灌入 N 行（PG `generate_series`、
 * MySQL 递归 CTE），id 用定宽前缀串保证 binary 列下唯一且可比。作为 kteasy-server 的额外 test 源目录参与编译，
 * 既被性能集成测调用，也可经 `:kteasy-server:seed50w` 任务独立运行（见 build.gradle.kts）。
 * 属 dev 工具：按方言各写各的批量 SQL，不受引擎「无 if(isMySQL)」红线约束（红线④⑤只圈 src/main 运行期路径）。
 */
object SeedRunner {
    /** 向实体表 [physicalTable]（已限定名）集合式灌入 [rows] 行，`ext` 携 {jsonKey: <0..996>}。返回耗时 ms。 */
    fun seed(
        jdbc: NamedParameterJdbcTemplate,
        physicalTable: String,
        dialect: String,
        rows: Int,
        jsonKey: String = "v",
    ): Long {
        val t0 = System.currentTimeMillis()
        if (dialect == "pg") {
            jdbc.update(
                """
                INSERT INTO $physicalTable (id, ext)
                SELECT 'seed' || lpad(g::text, 12, '0'), jsonb_build_object('$jsonKey', (g % 997))
                  FROM generate_series(1, :n) AS g
                """.trimIndent(),
                mapOf("n" to rows),
            )
        } else {
            jdbc.update("SET SESSION cte_max_recursion_depth = 2000000", emptyMap<String, Any>())
            jdbc.update(
                """
                INSERT INTO $physicalTable (id, ext)
                WITH RECURSIVE seq(n) AS (
                    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < :n
                )
                SELECT CONCAT('seed', LPAD(n, 12, '0')), JSON_OBJECT('$jsonKey', MOD(n, 997)) FROM seq
                """.trimIndent(),
                mapOf("n" to rows),
            )
        }
        return System.currentTimeMillis() - t0
    }

    /** 独立入口：从 KTEASY_DB_* / SPRING_PROFILES_ACTIVE 读连接，向 args[0] 指定的物理表灌 args[1] 行。 */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "用法: seed50w <物理表限定名> <行数>" }
        val table = args[0]
        val n = args[1].toInt()
        val host = System.getenv("KTEASY_DB_HOST") ?: "127.0.0.1"
        val db = System.getenv("KTEASY_DB_NAME") ?: "kteasy"
        val user = System.getenv("KTEASY_DB_USER") ?: "jteasy"
        val pass = System.getenv("KTEASY_DB_PASSWORD") ?: ""
        val dialect = (System.getenv("SPRING_PROFILES_ACTIVE") ?: "pg").lowercase()
        val port = System.getenv("KTEASY_DB_PORT") ?: if (dialect == "pg") "5432" else "3306"
        val url = if (dialect == "pg") "jdbc:postgresql://$host:$port/$db" else "jdbc:mysql://$host:$port/$db?useSSL=false&allowPublicKeyRetrieval=true"
        java.sql.DriverManager.getConnection(url, user, pass).use { conn ->
            val ms = seed(NamedParameterJdbcTemplate(SingleConnectionDataSource(conn, true)), table, dialect, n)
            println("seed $n rows into $table on $dialect in ${ms}ms")
        }
    }
}
