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
package cn.x.ac.kteasy.core.meta

import cn.x.ac.kteasy.core.schema.dialect.ValueCast

/*
 * 字段类型注册表（步骤卡 M1-04，模块图纸 01 §2 的机器可读化）。
 *
 * 设计铁律：**每型一份声明，禁止在业务层散落 `if(类型==X)`**——ext 编解码、类型级校验、可查询性、
 * 主显/快查资格、控件提示全部从这里出。`LogicalType` 是身份与存储矩阵（M1-01 落），本注册表是其上的**行为面**。
 *
 * core 保持零第三方依赖：ext 值以「规范存串」表示（标量＝字符串/数字/布尔字面量；数组＝JSON 字符串数组，
 * 复用 [MetadataValidator.parseStringArray] 容错解析）；把规范存串装进 ext JSON 文档、以及成员/存在性类校验
 * （选项∈option_set、字典 path∈树、引用/文件存在性）归 **M1-06 写通道**（有 DB 上下文）。本层只做类型级格式校验。
 */

/** 控件提示（渲染协议字段，M6 消费；服务端只声明不发浏览器逻辑）。 */
enum class UiControl {
    INPUT,
    TEXTAREA,
    PHONE,
    EMAIL,
    URL,
    NUMBER,
    DECIMAL,
    AUTONUM,
    DATE,
    DATETIME,
    TIME,
    SELECT,
    MULTISELECT,
    DICT,
    REF,
    ANYREF,
    FILE,
    IMAGE,
    AVATAR,
    LOCATION,
    SIGN,
    SWITCH,
    TAGS,
    QRCODE,
    BARCODE,
    SYSTEM,
}

/** 一种字段类型的全部行为声明。实现均为 `object`（每型一个），故密封性可编译期保证「漏配即不过」。 */
sealed interface FieldType {
    val logicalType: LogicalType

    /** 存储判据（＝[LogicalType.storage]）：EXT 标量 / COLUMN 真列 / N2N 关联表。 */
    val storage: StorageKind get() = logicalType.storage

    /** EQL 查询期对 ext 值 cast 的类型（真列型亦可用作列类型提示）；null＝不参与 cast。 */
    val cast: ValueCast? get() = null

    val filterable: Boolean get() = true
    val sortable: Boolean get() = true
    val aggregatable: Boolean get() = false

    /** 可作对象显示名称模板的片段（禁选规则＝主显资格）。 */
    val nameFieldEligible: Boolean get() = false

    /** 可作快速查询字段。 */
    val quickSearchEligible: Boolean get() = false

    /** 被选为主显时是否生成拼音检索码伴生列（列新增复用 M1-03 TableOps，值写入归 M1-06）。 */
    val pinyinGeneratable: Boolean get() = false

    /** 可经 S7 档二「显式作业」提为独立可写真列（标量型适用；关系/系统列走各自通道）。 */
    val physicalizable: Boolean get() = false

    val ui: UiControl

    /** 用户原始值 → 规范存串（trim/归一）；空/blank 归一为 null。数组型返回规范化后的 JSON 数组串。 */
    fun normalize(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** 类型级格式校验；返回 null＝合法，非 null＝错误文案。成员/存在性类校验归 M1-06。 */
    fun validate(raw: String?): String? = null
}

// ---------------- 文本族 ----------------

object Text : FieldType {
    override val logicalType = LogicalType.TEXT
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = true
    override val quickSearchEligible = true
    override val pinyinGeneratable = true
    override val physicalizable = true
    override val ui = UiControl.INPUT
}

object Textarea : FieldType {
    override val logicalType = LogicalType.TEXTAREA
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = true
    override val pinyinGeneratable = true
    override val physicalizable = true
    override val ui = UiControl.TEXTAREA

    override fun normalize(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}

object Phone : FieldType {
    override val logicalType = LogicalType.PHONE
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = true
    override val quickSearchEligible = true
    override val pinyinGeneratable = true
    override val physicalizable = true
    override val ui = UiControl.PHONE

    private val CN = Regex("^0\\d{2,3}-?\\d{7,8}$") // 固话（区号+号）
    private val MOBILE = Regex("^1[3-9]\\d{9}$") // 大陆手机
    private val INTL = Regex("^\\+\\d{5,15}$") // 国际区号

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        if (CN.matches(v) || MOBILE.matches(v) || INTL.matches(v)) return null
        return "电话 [$v] 非法：须为固话（0xx-xxxxxxx）/ 大陆手机（1[3-9]xxxxxxxxx）/ 国际（+数字）"
    }
}

object Email : FieldType {
    override val logicalType = LogicalType.EMAIL
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = true
    override val quickSearchEligible = true
    override val physicalizable = true
    override val ui = UiControl.EMAIL
    private val RX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "邮箱 [$v] 格式非法"
    }
}

object Url : FieldType {
    override val logicalType = LogicalType.URL
    override val cast = ValueCast.TEXT
    override val physicalizable = true
    override val ui = UiControl.URL
    private val RX = Regex("^https?://[^\\s]+$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "链接 [$v] 非法：须以 http(s):// 开头"
    }
}

// ---------------- 数字族 ----------------

object Number : FieldType {
    override val logicalType = LogicalType.NUMBER
    override val cast = ValueCast.LONG
    override val aggregatable = true
    override val physicalizable = true
    override val ui = UiControl.NUMBER

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (v.matches(Regex("^-?\\d+$"))) null else "整数 [$v] 非法：须为可选负号的十进制整数"
    }
}

object Decimal : FieldType {
    override val logicalType = LogicalType.DECIMAL
    override val cast = ValueCast.DOUBLE
    override val aggregatable = true
    override val physicalizable = true
    override val ui = UiControl.DECIMAL

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (v.matches(Regex("^-?\\d+(\\.\\d+)?$"))) null else "小数 [$v] 非法：须为数字（可含小数）"
    }
}

object Autonum : FieldType {
    override val logicalType = LogicalType.AUTONUM
    override val cast = ValueCast.TEXT
    override val sortable = true
    override val quickSearchEligible = true
    override val ui = UiControl.AUTONUM
    // 规则生成、不可手填（写入守卫在 M1-06 拒绝对其赋值）；类型级无格式校验。
}

// ---------------- 日期时间族 ----------------

object Date : FieldType {
    override val logicalType = LogicalType.DATE
    override val cast = ValueCast.DATE
    override val physicalizable = true
    override val ui = UiControl.DATE
    private val RX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "日期 [$v] 非法：须为 yyyy-MM-dd（UTC 存）"
    }
}

object Datetime : FieldType {
    override val logicalType = LogicalType.DATETIME
    override val cast = ValueCast.TIMESTAMP
    override val physicalizable = true
    override val ui = UiControl.DATETIME
    private val RX = Regex("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:\\d{2})?$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "日期时间 [$v] 非法：须为 ISO-8601（yyyy-MM-ddTHH:mm:ss[.frac][Z|±hh:mm]，UTC 存）"
    }
}

object Time : FieldType {
    override val logicalType = LogicalType.TIME
    override val cast = ValueCast.TEXT
    override val physicalizable = true
    override val ui = UiControl.TIME
    private val RX = Regex("^\\d{2}:\\d{2}(:\\d{2})?$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "时间 [$v] 非法：须为 HH:mm[:ss]"
    }
}

// ---------------- 选项族（成员/树校验归 M1-06） ----------------

object Picklist : FieldType {
    override val logicalType = LogicalType.PICKLIST
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = true
    override val quickSearchEligible = true
    override val physicalizable = true
    override val ui = UiControl.SELECT
}

object Multiselect : FieldType {
    override val logicalType = LogicalType.MULTISELECT
    override val cast = ValueCast.TEXT
    override val aggregatable = false
    override val sortable = false
    override val ui = UiControl.MULTISELECT

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (MetadataValidator.parseStringArray(v) != null) null else "多选 [$v] 非法：须为 JSON 字符串数组"
    }
}

object Tags : FieldType {
    override val logicalType = LogicalType.TAGS
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.TAGS

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (MetadataValidator.parseStringArray(v) != null) null else "标签 [$v] 非法：须为 JSON 字符串数组"
    }
}

// ---------------- 关系族（COLUMN / N2N） ----------------

object Dict : FieldType {
    override val logicalType = LogicalType.DICT
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = true
    override val quickSearchEligible = true
    override val ui = UiControl.DICT
    private val RX = Regex("^[^/]+(/[^/]+){0,3}$") // 物化路径 ≤4 段

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "分类路径 [$v] 非法：须为 `/` 分隔、层级 ≤4（树成员校验归 M1-06）"
    }
}

object Ref : FieldType {
    override val logicalType = LogicalType.REF
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = false
    override val ui = UiControl.REF
    private val ULID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (ULID.matches(v)) null else "引用 ID [$v] 非法：须为 26 位 ULID（存在性校验归 M1-06）"
    }
}

object N2n : FieldType {
    override val logicalType = LogicalType.N2N
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.REF
    private val ULID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        val arr = MetadataValidator.parseStringArray(v) ?: return "多引用 [$v] 非法：须为 JSON ID 数组"
        val bad = arr.firstOrNull { !ULID.matches(it) } ?: return null
        return "多引用含非法 ID [$bad]：须为 26 位 ULID"
    }
}

object Anyref : FieldType {
    override val logicalType = LogicalType.ANYREF
    override val cast = ValueCast.TEXT
    override val ui = UiControl.ANYREF
    private val RX = Regex("^[a-z][a-z0-9_]{2,47}:[^:]+$") // {entityHint}:{id}

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "任意引用 [$v] 非法：须为 'api:id' 复合串（两段存在性归 M1-06）"
    }
}

// ---------------- 文件族（引用串；数量 0-9 / mime 白名单软校验归 M1-06） ----------------

private fun validateFileRefs(raw: String?): String? {
    val v = raw ?: return null
    val arr = MetadataValidator.parseStringArray(v) ?: return "文件引用 [$v] 非法：须为 JSON file_ref 数组"
    return if (arr.size in 0..9) null else "文件数量 ${arr.size} 超上限 9"
}

object File : FieldType {
    override val logicalType = LogicalType.FILE
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.FILE

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?) = validateFileRefs(raw)
}

object Image : FieldType {
    override val logicalType = LogicalType.IMAGE
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.IMAGE

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?) = validateFileRefs(raw)
}

object Avatar : FieldType {
    override val logicalType = LogicalType.AVATAR
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.AVATAR

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?) = validateFileRefs(raw)
}

object Qrcode : FieldType {
    override val logicalType = LogicalType.QRCODE
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.QRCODE

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?) = validateFileRefs(raw)
}

object Barcode : FieldType {
    override val logicalType = LogicalType.BARCODE
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.BARCODE

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?) = validateFileRefs(raw)
}

object Sign : FieldType {
    override val logicalType = LogicalType.SIGN
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val ui = UiControl.SIGN

    override fun normalize(raw: String?): String? = raw?.let { JsonArrays.canonicalStringArray(MetadataValidator.parseStringArray(it)) }

    override fun validate(raw: String?) = validateFileRefs(raw)
}

// ---------------- 位置 / 布尔 ----------------

object Location : FieldType {
    override val logicalType = LogicalType.LOCATION
    override val cast = ValueCast.TEXT
    override val sortable = false
    override val physicalizable = true
    override val ui = UiControl.LOCATION

    // 规范串 "addr$$$lng,lat"（坐标系可配，默认 WGS84，见图纸 01 §2）
    private val RX = Regex("^[^$]*\\$\\$\\$-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$")

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        return if (RX.matches(v)) null else "位置 [$v] 非法：须为 '地址\$\$\$经度,纬度'"
    }
}

object Bool : FieldType {
    override val logicalType = LogicalType.BOOL
    override val cast = ValueCast.BOOL
    override val physicalizable = true
    override val ui = UiControl.SWITCH
    private val TRUE = setOf("true", "t", "yes", "y", "1", "是")
    private val FALSE = setOf("false", "f", "no", "n", "0", "否")

    override fun normalize(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return when {
            v in TRUE -> "true"
            v in FALSE -> "false"
            else -> v
        }
    }

    override fun validate(raw: String?): String? {
        val v = raw ?: return null
        val n = v.trim().lowercase()
        return if (n in TRUE || n in FALSE) null else "布尔 [$v] 非法：须为 true/false 或六种真值记法"
    }
}

// ---------------- 系统列族（引擎注入，只读、恒真列） ----------------

object System : FieldType {
    override val logicalType = LogicalType.SYSTEM
    override val cast = ValueCast.TEXT
    override val nameFieldEligible = false
    override val ui = UiControl.SYSTEM
}

/** 依赖无关的字符串数组规范存串生成（与 [MetadataValidator.parseStringArray] 的容错解析对偶）。 */
internal object JsonArrays {
    fun canonicalStringArray(items: List<String>?): String? {
        if (items == null) return null
        return items.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
    }
}

/**
 * 类型注册表：`LogicalType` → [FieldType] 单一来源。完整性测试断言覆盖 26+SYSTEM 全枚举。
 */
object TypeRegistry {
    val all: List<FieldType> =
        listOf(
            Text,
            Textarea,
            Phone,
            Email,
            Url,
            Number,
            Decimal,
            Autonum,
            Datetime,
            Date,
            Time,
            Picklist,
            Multiselect,
            Dict,
            Ref,
            N2n,
            Anyref,
            File,
            Image,
            Avatar,
            Qrcode,
            Location,
            Sign,
            Bool,
            Tags,
            Barcode,
            System,
        )

    private val byLogical: Map<LogicalType, FieldType> = all.associateBy { it.logicalType }

    init {
        // 密封注册表须与枚举一一对应（漏配/多配即启动失败）。
        val missing = LogicalType.entries.toSet() - byLogical.keys
        require(missing.isEmpty()) { "FieldType 注册表缺少类型：$missing" }
    }

    fun of(type: LogicalType): FieldType = byLogical[type] ?: error("未注册的类型 $type")

    fun of(api: String): FieldType = of(LogicalType.fromValue(api))
}
