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
package cn.x.ac.kteasy.server.schema

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.dev.seed.SeedRunner
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 步骤卡 M1-03 Block F · GWT#1（50w 行对象连加 20 标量字段 → `md_schema_change_job` 零步、读写全程不中断、
 * 新字段即刻可写 ext）。KTEASY_PERF=true 门控、行数取 KTEASY_SEED_ROWS（默认 500000）；CI 干净 service 库跑，
 * 本机共享库慎跑全量。读/写用裸 JDBC 模拟（不依赖 M1-06 写通道），只证「加标量零 DDL → 表读写不被打断」。
 *
 * 耗时与行数**必须落盘**（`build/perf/perf-smoke.txt`）：convention plugin 刻意关 `showStandardStreams`，
 * `println` 在 CI 日志里不可见，只留一个 success 步骤等于「绿了但不知道真测了 50w 没有」。
 * 落盘件由 workflow 上传成 artifact，是重件的唯一自证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_PERF", matches = "true")
class PerfSmokeIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var context: cn.x.ac.kteasy.core.kernel.KteasyContext

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val apiName = "m03perf$suffix"
    private val rows = (System.getenv("KTEASY_SEED_ROWS") ?: "500000").toInt()
    private val dialect get() = if (context.dialect == Dialect.POSTGRESQL) "pg" else "mysql"
    private val readErr = AtomicInteger()
    private val writeErr = AtomicInteger()
    private var dbUrl = "?"
    private var seedMs = -1L
    private var fieldAddMs = -1L
    private var seededRows = -1L
    private var stepDelta = -1
    private var stepsBefore = -1

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun awaitIdle(
        objectId: String,
        timeoutMs: Long = 60_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && jobRepo.hasUnfinished(objectId)) {
            TimeUnit.MILLISECONDS.sleep(200)
        }
    }

    private fun tablePresent(): Boolean {
        val f = provider.introspection.tableExists(LogicalArea.ENTITY, apiName)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(
        timeoutMs: Long = 60_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(100)
        }
        return poll()
    }

    /**
     * 落盘自证件（`@AfterEach` 无论红绿都写）：`showStandardStreams` 关着，没有这个文件，CI 上就分不清
     * 「50w 真灌过」与「测试被门控跳过」。落盘路径与 [cn.x.ac.kteasy.server.write.DetailsPerfIT] 同目录，
     * 由 workflow 统一收进 artifact。
     */
    private fun report() {
        val out = File(System.getenv("KTEASY_PERF_OUT") ?: "build/perf/perf-smoke.txt")
        out.parentFile?.mkdirs()
        val stamp = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        out
            .appendText(
                """
                [$stamp] dialect=$dialect db=$dbUrl 目标行数=$rows 实测行数=$seededRows
                  灌数=${seedMs}ms / 连加 20 标量含排空=${fieldAddMs}ms
                  加字段期间 读异常=${readErr.get()} 写异常=${writeErr.get()} / job 步数 $stepsBefore→${stepsBefore + stepDelta}（增量 $stepDelta，零步判定＝增量 0）

                """.trimIndent() + "\n",
            )
    }

    @AfterEach
    fun cleanup() {
        runCatching { report() } // 落盘失败不得吃掉清库
        val jt = JdbcTemplate(dataSource)
        runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.ENTITY, apiName)} CASCADE") }
        val like = "%$suffix%"
        val objQual = qual(LogicalArea.METADATA, "md_object")
        dataSource.connection.use { conn ->
            conn
                .prepareStatement("DELETE FROM ${qual(LogicalArea.METADATA, "md_field")} WHERE object_id IN (SELECT id FROM $objQual WHERE api_name LIKE ?)")
                .use { ps ->
                    ps.setString(1, like)
                    ps.executeUpdate()
                }
            conn.prepareStatement("DELETE FROM ${qual(LogicalArea.METADATA, "md_schema_change_job")} WHERE object_id IN (SELECT id FROM $objQual WHERE api_name LIKE ?)").use { ps ->
                ps.setString(1, like)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM $objQual WHERE api_name LIKE ?").use { ps ->
                ps.setString(1, like)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `50w 行对象连加20标量 零 DDL 步 并行读写不中断 新字段即刻可写 ext`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = apiName,
                label = "流水",
                kind = "PLAIN",
                displayName = "{name}",
                fields = listOf(MetadataService.FieldCmd(apiName = "name", label = "名", logicalType = "TEXT")),
            ),
        )
        val obj = meta.buildGraph(meta.loadSnapshot(), apiName).objectMeta
        val table = qual(LogicalArea.ENTITY, apiName)
        dbUrl = runCatching { dataSource.connection.use { it.metaData.url } }.getOrDefault("?").substringBefore("?")
        assertThat(await { tablePresent() }).`as`("50w 前先等 CREATE_TABLE 落表").isTrue()
        awaitIdle(obj.id)
        val baseSteps = jobRepo.listByObject(obj.id).size
        stepsBefore = baseSteps

        seedMs = SeedRunner.seed(jdbc(), table, dialect, rows)
        println("[seed] $rows 行 → $dialect 实体表 $table，耗时 ${seedMs}ms")
        val counted: java.lang.Long? = jdbc().queryForObject("SELECT COUNT(*) FROM $table", emptyMap<String, Any>(), java.lang.Long::class.java)
        seededRows = counted?.toLong() ?: -1L
        assertThat(seededRows).isEqualTo(rows.toLong())

        // 并行读写冒烟：读线程 SELECT、写线程裸 INSERT ext 行，全程统计异常。
        val stop = AtomicBoolean(false)
        var wseq = 0L
        val reader =
            Thread {
                try {
                    while (!stop.get()) {
                        jdbc().queryForObject("SELECT COUNT(*) FROM $table", emptyMap<String, Any>(), java.lang.Long::class.java)
                        TimeUnit.MILLISECONDS.sleep(100) // ~10 QPS
                    }
                } catch (e: Exception) {
                    readErr.incrementAndGet()
                }
            }
        val writer =
            Thread {
                try {
                    while (!stop.get()) {
                        val id = "smw${wseq++}"
                        jdbc().update("INSERT INTO $table (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})", mapOf("id" to id, "ext" to "{\"v\":$wseq}"))
                        TimeUnit.MILLISECONDS.sleep(1000) // 1 QPS
                    }
                } catch (e: Exception) {
                    writeErr.incrementAndGet()
                }
            }
        reader.isDaemon = true
        writer.isDaemon = true
        reader.start()
        writer.start()

        val tAdd = System.currentTimeMillis()
        try {
            // 连加 20 标量字段（EXT 存储）——零 DDL。
            repeat(20) { i -> meta.createField(apiName, MetadataService.FieldCmd(apiName = "sc$i", label = "标量$i", logicalType = "TEXT")) }
            awaitIdle(obj.id)
        } finally {
            stop.set(true)
            reader.join(5_000)
            writer.join(5_000)
        }
        fieldAddMs = System.currentTimeMillis() - tAdd

        assertThat(readErr.get()).`as`("加字段期间读不得报错").isEqualTo(0)
        assertThat(writeErr.get()).`as`("加字段期间写不得报错").isEqualTo(0)
        // 零步：20 标量增改不新增任何 schema_change_job 行。
        stepDelta = jobRepo.listByObject(obj.id).size - baseSteps
        assertThat(stepDelta).`as`("连加 20 标量应零步").isEqualTo(0)
        // 新字段即刻可写 ext。
        val probe = "probe$suffix"
        jdbc().update("INSERT INTO $table (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})", mapOf("id" to probe, "ext" to "{\"sc0\":\"hello\"}"))
        val back =
            jdbc().queryForObject(
                "SELECT ${provider.json.extractText(
                    "ext",
                    cn.x.ac.kteasy.core.schema.dialect.JsonPath
                        .of("sc0"),
                ).sql} FROM $table WHERE id = :id",
                mapOf("id" to probe),
                String::class.java,
            )
        assertThat(back).isEqualTo("hello")
    }
}
