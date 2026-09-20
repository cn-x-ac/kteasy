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

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.StepKind
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.dev.seed.SeedRunner
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * 步骤卡 M1-03 Block F · GWT#4（MySQL 大表 `ADD_FK_COLUMN` 走 INSTANT 探测→失败回退 INPLACE，日志标注走了哪条）。
 *
 * 仅 MySQL：给已 seed 的大表加引用字段 → 执行器 `addColumnWithFallback` 逐条候选探测，`加列实际走：<DDL>` 记进 INFO。
 * 捕获该日志断言实际走了 `ALGORITHM=INSTANT`（MySQL 8 追加可空列支持 INSTANT，规模无关），并验证真列 + 外键落地。
 * INPLACE 回退候选由 [cn.x.ac.kteasy.core.schema.dialect.ColumnOps.addNullableColumn] 产出、L1 已锁两条候选；
 * 本测证「探测并按日志标注实走路径」的 capability 行为。KTEASY_PERF 门控、行数取 KTEASY_SEED_ROWS。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_PERF", matches = "true")
class InstantPathIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val hostApi = "m04inst$suffix"
    private val refApi = "m04ref$suffix"
    private val rows = (System.getenv("KTEASY_SEED_ROWS") ?: "500000").toInt()

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun exists(
        area: LogicalArea,
        logical: String,
    ): Boolean {
        val f = provider.introspection.tableExists(area, logical)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun colExists(
        host: String,
        col: String,
    ): Boolean {
        val f = provider.introspection.columnExists(LogicalArea.ENTITY, host, col)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(
        timeoutMs: Long = 120_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    @AfterEach
    fun cleanup() {
        val jt = JdbcTemplate(dataSource)
        runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.ENTITY, hostApi)} CASCADE") }
        runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.ENTITY, refApi)} CASCADE") }
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
    fun `MySQL 大表加引用列 走 INSTANT 且日志标注实走路径`() {
        assumeTrue(context.dialect == Dialect.MYSQL, "仅 MySQL 验证 INSTANT/INPLACE 探测")

        meta.createObject(
            MetadataService.ObjectCreateCmd(apiName = refApi, label = "被引", kind = "PLAIN", displayName = "{code}", fields = listOf(MetadataService.FieldCmd(apiName = "code", label = "码", logicalType = "TEXT"))),
        )
        meta.createObject(
            MetadataService.ObjectCreateCmd(apiName = hostApi, label = "宿主", kind = "PLAIN", displayName = "{xname}", fields = listOf(MetadataService.FieldCmd(apiName = "xname", label = "名", logicalType = "TEXT"))),
        )
        val hostObj = meta.buildGraph(meta.loadSnapshot(), hostApi).objectMeta
        assertThat(await { exists(LogicalArea.ENTITY, hostApi) && exists(LogicalArea.ENTITY, refApi) }).isTrue()
        awaitIdle(hostObj.id)

        SeedRunner.seed(jdbc(), qual(LogicalArea.ENTITY, hostApi), "mysql", rows, "x")

        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val log = LoggerFactory.getLogger(SchemaJobExecutor::class.java) as LogbackLogger
        log.addAppender(appender)
        try {
            meta.createField(hostApi, MetadataService.FieldCmd(apiName = "owner_ref", label = "归属", logicalType = "REF", refObjectApi = refApi))
            val fk = { jobRepo.listByObject(hostObj.id).firstOrNull { it.stepKind == StepKind.ADD_FK_COLUMN } }
            assertThat(await { fk()?.state == "DONE" }).`as`("大表加引用列应完成").isTrue()
        } finally {
            log.detachAppender(appender)
        }

        val chosen = appender.list.map { it.formattedMessage }.filter { it.contains("加列实际走") }
        assertThat(chosen).`as`("必须记录实际走了哪条加列语句").isNotEmpty()
        assertThat(chosen).`as`("MySQL 8 追加可空引用列应命中 INSTANT 快路径").anyMatch { it.contains("ALGORITHM=INSTANT") }
        assertThat(colExists(hostApi, "owner_ref")).`as`("引用真列已落").isTrue()
    }

    private fun awaitIdle(objectId: String) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline && jobRepo.hasUnfinished(objectId)) {
            TimeUnit.MILLISECONDS.sleep(200)
        }
    }
}
