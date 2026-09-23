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

import cn.x.ac.kteasy.core.kernel.WallClock
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.meta.SystemColumns
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.ExtCodec
import cn.x.ac.kteasy.core.write.FieldDiff
import cn.x.ac.kteasy.core.write.WritePlan
import cn.x.ac.kteasy.core.write.WriteRow
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 既有行 <-> 管道视图的映射（块 3 的读侧半区）。
 *
 * 读回的值一律折成 [DraftValue]：`ext` 由 [ExtCodec] 解码、真列按 JDBC 类型归一。
 * 时间列统一转成 **UTC 裸墙钟串**（与 [WallClock] 同形，故这里只做无时区偏移的格式化）——
 * 否则 PG 读回 `timestamptz`、MySQL 读回 `datetime`，diff 会凭空多出「值变了」的假变更。
 */
@Component
class RowValues(
    private val codec: ExtCodec,
) {
    /** @param raw 一次 `SELECT *` 的结果（键＝物理列名） */
    fun toRow(
        raw: Map<String, Any?>,
        fields: List<MdField>,
    ): WriteRow {
        val values = LinkedHashMap(codec.decode(raw[SystemColumns.EXT] as String?))
        fields.filter { it.storageKind == StorageKind.COLUMN }.forEach { f ->
            columnValue(raw[f.apiName])?.let { values[f.apiName] = it }
        }
        return WriteRow(
            id = raw[SystemColumns.ID] as String,
            rowVersion = (raw["row_version"] as? Number)?.toLong() ?: 0L,
            values = values,
            ownerUser = raw["owner_user"] as String?,
            ownerDept = raw["owner_dept"] as String?,
            approvalState = raw["approval_state"] as String?,
            deleted = raw["deleted_at"] != null,
        )
    }

    private fun columnValue(
        v: Any?,
    ): DraftValue? =
        when (v) {
            null -> null
            is Boolean -> DraftValue.Bool(v)
            is BigDecimal -> DraftValue.Number(v.toPlainString())
            is Number -> DraftValue.Number(v.toString())
            is Timestamp -> DraftValue.Text(wall(v.toLocalDateTime()))
            is LocalDateTime -> DraftValue.Text(wall(v))
            else -> DraftValue.Text(v.toString())
        }

    private fun wall(
        dt: LocalDateTime,
    ): String = WallClock.timestamp(dt.atZone(ZoneOffset.UTC))

    /** ext 列的绑定值：空图给 null（整列无值时不留空对象字面量，两库比较轴更干净）。 */
    fun extBinding(
        plan: WritePlan,
    ): String? = if (plan.extValues.isEmpty()) null else codec.encode(plan.extValues)

    /** 管道 diff -> 对外/事件载荷（`{o,n}` 形状，键缺省＝无值；DraftValue 在这里才变成 JSON 友好形态）。 */
    fun diffWire(
        diff: Map<String, FieldDiff>,
    ): Map<String, Map<String, Any?>> =
        diff.mapValues { (_, d) ->
            LinkedHashMap<String, Any?>().apply {
                d.old?.let { put("o", valueWire(it)) }
                d.new?.let { put("n", valueWire(it)) }
            }
        }

    private fun valueWire(
        v: DraftValue,
    ): Any? =
        when (v) {
            is DraftValue.Text -> v.value
            is DraftValue.Bool -> v.value
            is DraftValue.Number -> v.literal
            is DraftValue.Many -> v.items
            is DraftValue.Cleared -> null
        }
}
