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
package cn.x.ac.kteasy.server.write

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.WriteErrors
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 步骤卡 M1-07 块 1 · 取号器双库端到端（真连，pg/mysql 各跑一次，两库命中同一组期望值）。
 *
 * 锁四件事：① 同事务顺序取号单调推进；② 周期切换日 period_key 正确重置（新桶从 start 起步）；
 * ③ 字段变量/日期段按兄弟值快照渲染；④ 并发 100 取号**无重复且不小于起点**（缺口允许——卡面 GWT5 口径）。
 * 另加坏模板 → 420 `AUTONUM_FAILED`（P8：取号失败不降级不静默）。
 *
 * **每次 `next` 都包在事务里**：MySQL 的 `LAST_INSERT_ID()` 是连接级变量，推进与取回必须同连接——
 * 生产路径 `WriteService.doWrite` 本就在 `tx.execute` 内，本 IT 的包装不是测试便利而是约束复刻。
 * 规则行直接插 `md_autonum_rule`（元数据治理面的配置 UI 归 M6，此处只需表存在且可查）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class AutonumSeqIT {
    @Autowired
    lateinit var sequencer: AutonumSequencer

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    lateinit var tx: TransactionTemplate

    @Autowired
    lateinit var provider: SchemaProvider

    private lateinit var ruleTable: String
    private lateinit var seqTable: String

    /** 各用例的 field_id 收尾清理名单。 */
    private val fieldIds = ArrayList<String>()

    private fun insertRule(
        segmentsJson: String,
        reset: String = "NONE",
    ): MdField {
        val fid = Ulid.next()
        fieldIds += fid
        jdbc.update(
            "INSERT INTO $ruleTable (id, object_id, field_id, segments_json) " +
                "VALUES (:id, :object_id, :field_id, ${provider.json.bindJson("segments_json")})",
            mapOf(
                "id" to Ulid.next(),
                "object_id" to Ulid.next(),
                "field_id" to fid,
                "segments_json" to segmentsJson,
            ),
        )
        return MdField(
            id = fid,
            objectId = "obj",
            apiName = "no_$fid",
            label = "n",
            logicalType = LogicalType.AUTONUM,
            storageKind = StorageKind.EXT,
        )
    }

    private fun nextInTx(
        field: MdField,
        siblings: Map<String, DraftValue> = emptyMap(),
    ): String? = tx.execute { sequencer.next(field, siblings) }

    @Test
    fun `同事务顺序取号单调推进`() {
        val f = insertRule("""[{"kind":"TEXT","text":"INV-"},{"kind":"SEQ","width":4,"start":1}]""")
        sequencer.today = { LocalDate.of(2026, 9, 24) }
        assertThat(nextInTx(f)).isEqualTo("INV-0001")
        assertThat(nextInTx(f)).isEqualTo("INV-0002")
        assertThat(nextInTx(f)).isEqualTo("INV-0003")
    }

    @Test
    fun `周期切换日 period_key 重置新桶从起点起步`() {
        val f = insertRule("""[{"kind":"SEQ","width":3,"start":1,"reset":"DAY"}]""")
        sequencer.today = { LocalDate.of(2026, 9, 24) }
        assertThat(nextInTx(f)).isEqualTo("001")
        assertThat(nextInTx(f)).isEqualTo("002")
        // 换一天＝新 period_key 桶，从 start 重新起步；seq 表应有两行（两桶）。
        sequencer.today = { LocalDate.of(2026, 9, 25) }
        assertThat(nextInTx(f)).isEqualTo("001")
        val ruleId =
            jdbc.queryForObject("SELECT id FROM $ruleTable WHERE field_id = :fid", mapOf("fid" to f.id), String::class.java)
        val buckets = jdbc.queryForObject("SELECT count(*) FROM $seqTable WHERE rule_id = :rid", mapOf("rid" to ruleId), Int::class.java)
        assertThat(buckets).`as`("两个周期桶").isEqualTo(2)
    }

    @Test
    fun `字段变量与日期段按兄弟值快照渲染`() {
        val f =
            insertRule(
                """[{"kind":"TEXT","text":"INV-"},{"kind":"FIELD","api":"dept"},{"kind":"DATE","pattern":"yyyyMM"},{"kind":"SEQ","width":4,"start":1}]""",
            )
        sequencer.today = { LocalDate.of(2026, 9, 24) }
        val out = nextInTx(f, mapOf("dept" to DraftValue.Text("SH")))
        assertThat(out).isEqualTo("INV-SH2026090001")
    }

    @Test
    fun `并发一百取号无重复且不小于起点`() {
        val f = insertRule("""[{"kind":"SEQ","width":5,"start":1,"reset":"DAY"}]""")
        sequencer.today = { LocalDate.of(2026, 9, 24) }
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..100).map { pool.submit(Callable { nextInTx(f)!! }) }
            val values = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(values).doesNotHaveDuplicates()
            assertThat(values.map { it.toInt() }).allSatisfy { v -> assertThat(v).isGreaterThanOrEqualTo(1) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `坏模板抛 AUTONUM_FAILED 承载 420`() {
        val f = insertRule("""[{"kind":"WAT"}]""")
        assertThatThrownBy { nextInTx(f) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                assertThat(it.apiError).isEqualTo(ApiError.BUSINESS_RULE)
                assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo(WriteErrors.ID_AUTONUM_FAILED)
            }
    }

    @Test
    fun `无规则字段返回 null 不填值`() {
        val orphan =
            MdField(
                id = Ulid.next(),
                objectId = "obj",
                apiName = "no_rule",
                label = "n",
                logicalType = LogicalType.AUTONUM,
                storageKind = StorageKind.EXT,
            )
        assertThat(nextInTx(orphan)).isNull()
    }

    @BeforeAll
    fun setup() {
        // 表名在注入后才能合成（PER_CLASS 生命周期下字段初始化早于 @Autowired）。
        ruleTable = provider.namespace.qualified(LogicalArea.METADATA, "md_autonum_rule")
        seqTable = provider.namespace.qualified(LogicalArea.ENGINE, "kteasy_autonum_seq")
        cleanup()
    }

    @AfterAll
    fun cleanup() {
        if (fieldIds.isEmpty()) return
        jdbc.update("DELETE FROM $seqTable WHERE rule_id IN (SELECT id FROM $ruleTable WHERE field_id IN (:fids))", mapOf("fids" to fieldIds))
        jdbc.update("DELETE FROM $ruleTable WHERE field_id IN (:fids)", mapOf("fids" to fieldIds))
    }
}
