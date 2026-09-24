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

import cn.x.ac.kteasy.core.autonum.AutonumSpec
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.WriteErrors
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 自动编号取号器（步骤卡 M1-07 设计要点 4）——写通道阶段 5 注入点的真实现。
 *
 * 职责链：按字段查规则 → 解析模板 → 算周期键 → **单事务内推进计数并取回** → 渲染号串。
 * 模板解析/周期键/渲染的纯函数半区在 `core.autonum`，本类只做 IO 与装配（红线⑤：方言差异只在
 * [SchemaProvider.upsert] 的取号语句里，本类不出现 `if (isMySQL)`）。
 *
 * **必须在写事务内调用**（阶段 5 在 `WriteService.doWrite` 的 `tx.execute` 内，天然满足）：
 * MySQL 的 `LAST_INSERT_ID()` 是连接级变量，推进与取回两语句必须同连接——事务内
 * [NamedParameterJdbcTemplate] 复用同一物理连接，这正是 M1-06 写下的「锁与事务同一连接」约束的镜像。
 *
 * 失败语义（决策台 P8）：规则模板非法 → 420 `AUTONUM_FAILED`（改元数据配置可自救的是配置不是载荷，
 * 但它不是「现场状态冲突」，归 420 与取号不可重试语义一致）；基础设施故障照抛 Spring `DataAccessException`
 * 不翻译不猜——降级出假号比失败更糟。无规则的字段返回 null＝不填值（该字段走必填/默认值裁决）。
 */
@Repository
class AutonumSequencer(
    private val jdbc: NamedParameterJdbcTemplate,
    provider: SchemaProvider,
) {
    private val namespace = provider.namespace
    private val upsert = provider.upsert

    /** 供 IT 固定时钟用；生产路径恒 UTC 墙钟日（与写侧时间列同轴，P7）。 */
    internal var today: () -> LocalDate = { LocalDate.now(java.time.ZoneOffset.UTC) }

    fun next(
        field: MdField,
        siblings: Map<String, DraftValue>,
    ): String? {
        val rule = ruleOf(field.id) ?: return null
        val spec =
            try {
                AutonumSpec.parse(rule.segmentsJson)
            } catch (e: IllegalArgumentException) {
                throw WriteErrors.rejected(
                    listOf(WriteErrors.violation(field.apiName, WriteErrors.ID_AUTONUM_FAILED, "编号规则模板非法：${e.message}")),
                )
            }
        val date = today()
        val seqValue = bump(rule.ruleId, spec, date)
        return spec.render(seqValue, date, siblingStrings(siblings))
    }

    private data class Rule(
        val ruleId: String,
        val segmentsJson: String,
    )

    private fun ruleOf(fieldId: String): Rule? =
        jdbc
            .query(
                "SELECT id, segments_json FROM ${namespace.qualified(LogicalArea.METADATA, "md_autonum_rule")} WHERE field_id = :field_id",
                mapOf("field_id" to fieldId),
            ) { rs, _ ->
                Rule(ruleId = rs.getString("id"), segmentsJson = rs.getString("segments_json"))
            }.singleOrNull()

    /** 推进计数并返回本次可用序号：首行＝start，其后每次 +1（方言语句见 [upsert.buildCounterBump]）。 */
    private fun bump(
        ruleId: String,
        spec: AutonumSpec,
        date: LocalDate,
    ): Long {
        val table = namespace.qualified(LogicalArea.ENGINE, "kteasy_autonum_seq")
        val sql = upsert.buildCounterBump(table, listOf("rule_id", "period_key"), "seq_value")
        val params =
            mapOf(
                "rule_id" to ruleId,
                "period_key" to spec.periodKey(date),
                "seq_value" to spec.seq.start,
            )
        return if (upsert.supportsReturning()) {
            requireNotNull(jdbc.queryForObject(sql, params, Long::class.java)) { "取号 RETURNING 未返回值" }
        } else {
            jdbc.update(sql, params)
            val follow = requireNotNull(upsert.counterFollowUp()) { "无 RETURNING 的方言必须提供取回语句" }
            requireNotNull(jdbc.queryForObject(follow, emptyMap<String, Any?>(), Long::class.java)) { "取号取回为空" }
        }
    }

    /** 字段变量取值：DraftValue → 渲染串。多值以逗号相连（确定性优先），显式清空＝空串。 */
    private fun siblingStrings(siblings: Map<String, DraftValue>): Map<String, String> =
        siblings.mapValues { (_, v) ->
            when (v) {
                is DraftValue.Text -> v.value
                is DraftValue.Number -> v.literal
                is DraftValue.Bool -> v.value.toString()
                is DraftValue.Many -> v.items.joinToString(",")
                DraftValue.Cleared -> ""
            }
        }
}
