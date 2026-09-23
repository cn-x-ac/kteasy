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
package cn.x.ac.kteasy.core.kernel

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 时间值的**唯一绑定形状**（读侧 `SqlRenderer` 与写侧 `WritePipeline` 共用，避免两处各格式化一遍）。
 *
 * 存储约定是「存 UTC」（图纸 03 §3 / 图纸 01 §2）：
 * - DATE 类 → [LocalDate]（两库日期列按日历日比较）；
 * - TIMESTAMP 类 → **UTC 裸墙钟串**（`yyyy-MM-dd HH:mm:ss`，无时区后缀）。
 *
 * 为什么裸墙钟串而不是带时区的 ISO 串：MySQL `DATETIME` 本就无时区、按墙钟比较；
 * PG `timestamptz` 会把裸串按会话时区解释——**故会话时区必须是 UTC**（CI service 库与本机 dev 库同此前提）。
 * 这是 M1-04 §S8「typed 跨库不等价」的落法：两库都以「UTC 墙钟」为唯一比较轴。
 */
object WallClock {
    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** UTC 裸墙钟串（TIMESTAMP 列/ext 里的 DATETIME 值）。 */
    fun timestamp(
        z: ZonedDateTime,
    ): String = TS.format(z.withZoneSameInstant(ZoneOffset.UTC))

    /** 日历日（DATE 列/ext 里的 DATE 值）。 */
    fun date(
        z: ZonedDateTime,
    ): LocalDate = LocalDate.of(z.year, z.monthValue, z.dayOfMonth)

    /**
     * 写入侧的时间绑定值：UTC 日历时间、**不带偏移**。
     *
     * 为什么不能像查询侧那样绑裸墙钟串：PG 在**比较**语境下会把字面量串推断成 timestamp，
     * 但 `INSERT`/`UPDATE` 的赋值位不会——它报「column is of type timestamp with time zone
     * but expression is of type character varying」（MySQL 会隐式转，故只在 PG 侧暴露）。
     * 绑 `LocalDateTime` 两库驱动都接受，且不做时区换算（会话时区为 UTC，见 [timestamp] 的说明）。
     */
    fun utcLocalDateTime(
        z: ZonedDateTime,
    ): LocalDateTime = z.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

    /** 写入侧的日期绑定值。 */
    fun utcLocalDate(
        z: ZonedDateTime,
    ): LocalDate = date(z)

    /** JDBC 绑定值：isDate 决定两种形状之一。 */
    fun bind(
        z: ZonedDateTime,
        isDate: Boolean,
    ): Any = if (isDate) date(z) else timestamp(z)
}
