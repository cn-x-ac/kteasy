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

/**
 * 字段逻辑类型全集：26 型 + [SYSTEM]（系统列虚拟字段，【全景】§3.1、模块图纸 01 §2）。
 *
 * 每型绑定唯一的合法 [StorageKind]（存储矩阵＝「默认 EXT，白名单才 COLUMN」的机器可读形式）：
 * - 标量型默认 EXT（进 ext JSON，加字段零 DDL）；
 * - DICT/REF/ANYREF/SYSTEM 为 COLUMN（关系/路径/系统列，真列判据）；
 * - N2N 为 [StorageKind.N2N]（落 r_* 关联表，M1-07）。
 *
 * 本枚举只承载「型 × 存储」与中文展示名；每型的控件/校验器/EQL cast/值形态归
 * M1-04 字段类型注册表（sealed class FieldType 逐型展开），此处不提前发明。
 */
enum class LogicalType(
    val storage: StorageKind,
    val display: String,
) {
    TEXT(StorageKind.EXT, "文本"),
    TEXTAREA(StorageKind.EXT, "多行文本"),
    PHONE(StorageKind.EXT, "电话"),
    EMAIL(StorageKind.EXT, "邮箱"),
    URL(StorageKind.EXT, "链接"),
    NUMBER(StorageKind.EXT, "整数"),
    DECIMAL(StorageKind.EXT, "小数"),
    AUTONUM(StorageKind.EXT, "自动编号"),
    DATETIME(StorageKind.EXT, "日期时间"),
    DATE(StorageKind.EXT, "日期"),
    TIME(StorageKind.EXT, "时间"),
    PICKLIST(StorageKind.EXT, "下拉"),
    MULTISELECT(StorageKind.EXT, "多选"),
    TAGS(StorageKind.EXT, "标签"),
    BOOL(StorageKind.EXT, "布尔"),
    FILE(StorageKind.EXT, "附件"),
    IMAGE(StorageKind.EXT, "图片"),
    AVATAR(StorageKind.EXT, "头像"),
    QRCODE(StorageKind.EXT, "二维码"),
    BARCODE(StorageKind.EXT, "条码"),
    LOCATION(StorageKind.EXT, "位置"),
    SIGN(StorageKind.EXT, "签名"),
    DICT(StorageKind.COLUMN, "分类"),
    REF(StorageKind.COLUMN, "引用"),
    ANYREF(StorageKind.COLUMN, "任意引用"),
    N2N(StorageKind.N2N, "多引用"),

    /** 系统列虚拟字段（owner_user/created_at/…），只读、恒真列，用户不可新建。 */
    SYSTEM(StorageKind.COLUMN, "系统列"),
    ;

    companion object {
        /** 安全解析：未知值抛 [IllegalArgumentException]（前端校验始终抛错，拒绝静默忽略）。 */
        fun fromValue(value: String): LogicalType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "未知的字段逻辑类型 '$value'，允许值：${entries.joinToString { it.name }}",
                )
    }
}
