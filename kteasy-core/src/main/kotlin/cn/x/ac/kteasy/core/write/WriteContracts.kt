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
package cn.x.ac.kteasy.core.write

import cn.x.ac.kteasy.core.meta.FieldWritePolicy
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.RequiredScope
import java.time.ZonedDateTime

// 写入通道契约层（步骤卡 M1-06 §设计要点 1）：**全系统唯一写入口**的入参/出参词汇表——
// 新建、导入、转换、开放接口此后都只拼这几个类型，不再各自摸 SQL（三铁律① 的物理保障）。
// core 保持纯库：这里只有数据与纯函数，事务/锁/JDBC 全在装配层（ArchUnit 门禁 + 红线④⑤）。

/**
 * 写入来源（卡面 ctx 的"来源枚举"）。审计与守卫按来源差异化判定的唯一依据。
 *
 * 词汇统一为 `SYSTEM`（P5，2026-09-23）：覆盖引擎内部回写、recalc、自动化下发等特权通道。
 * 不留 `INTERNAL` 别名——同义两值迟早漂移，而 M2/M3/M4/M5 每张卡都要在 `when` 里写这个名字。
 */
enum class WriteSource {
    /** 用户在界面上保存（受完整前端语义约束，但服务端**一律重判**，不信任前端）。 */
    UI,

    /** 批量导入（M4-02/M5 导入 trace；同事务多条走 writeAllInTx）。 */
    IMPORT,

    /** 记录转换（M4-03 生成新记录/回填字段）。 */
    TRANSFORM,

    /** 开放接口直连（卡面 GWT1 的反例主角：OPENAPI 不得成为只读字段的旁路）。 */
    OPENAPI,

    /** 引擎内部特权通道。 */
    SYSTEM,
}

/**
 * 调用方意图。注意 **CREATE 与 UPDATE 不在这里区分**——卡面 §2 阶段 1 明确「按 id 有无」派生，
 * 若允许调用方自行声明新建还是更新，就会多出「POST 带 id 却声明 CREATE」这类需要额外裁决的矛盾输入。
 */
enum class WriteIntent {
    /** 无 id 即新建、有 id 即更新（[resolveKind] 负责裁决）。 */
    UPSERT,

    /** 软删（置 deleted_at，图纸 04 §1 走 1-11 特化）。 */
    DELETE,

    /** 恢复（清 deleted_at；回收站语义归 M5）。 */
    RESTORE,
}

/**
 * 落库结果类别，与图纸 04 §3 事件载荷的 `kind` 四值一一对应（M3 自动化按此匹配「新建/更新/删除」触发器）。
 */
enum class WriteKind {
    CREATED,
    UPDATED,
    DELETED,
    RESTORED,
}

/**
 * 由「意图 + 是否带 id + 既有行是否存在」裁决本次写入的性质（阶段 1 的定位结论）。
 *
 * 纯函数、可单测：DELETE/RESTORE 的 kind 不取决于 id 有无（两者都必然带 id），UPSERT 才分新建/更新。
 */
fun resolveKind(
    intent: WriteIntent,
    hasId: Boolean,
    existed: Boolean,
): WriteKind =
    when (intent) {
        WriteIntent.DELETE -> {
            WriteKind.DELETED
        }

        WriteIntent.RESTORE -> {
            WriteKind.RESTORED
        }

        WriteIntent.UPSERT -> {
            if (!hasId || !existed) {
                WriteKind.CREATED
            } else {
                WriteKind.UPDATED
            }
        }
    }

/**
 * 操作者。M2-01 前由装配层填占位常量（本仓暂无鉴权上下文），M2 起从会话解析——**接缝形状先冻结**。
 *
 * @property userId 归属/创建人取值来源（写进系统列 owner_user / created_by / updated_by）
 * @property deptId 归属部门；与 userId 联动，缺省由 M2 的组织解析补齐
 */
data class WriteActor(
    val userId: String,
    val deptId: String? = null,
)

/**
 * 一次写入的上下文（卡面 §1：用户、traceId、来源）。不可变，管道内只读不写。
 *
 * @property now 注入时钟（与 [cn.x.ac.kteasy.core.query.QueryContext.now] 同形，保证双库 oracle 可复现）
 * @property recordId 既有行 id；UPSERT 时它的有无直接决定新建还是更新
 * @property expectedVersion 乐观并发的客户端版本（承载列＝`row_version`）。P1 定稿：
 *   带了就双条件更新、不匹配出 420 `CONFLICT_RETRY`；不带则照常写入并出 warning
 *   `OVERRIDE_WITHOUT_VERSION`——不丢改动本就由「每记录命名锁 + 事务内读最新行算 diff」保证，
 *   版本号管的是「拿过期表单盲覆盖」这件事。
 */
data class WriteContext(
    val objectApi: String,
    val intent: WriteIntent,
    val source: WriteSource,
    val actor: WriteActor,
    val traceId: String,
    val now: ZonedDateTime,
    val recordId: String? = null,
    val expectedVersion: Long? = null,
    /**
     * 子项写入的主记录 id（M1-07 块 2；仅 [cn.x.ac.kteasy.core.meta.ObjectKind.CHILD] 对象用）。
     *
     * 与 `created_at` 同属**服务端系统值**：调用方永远不能在载荷里带 `parent_id`（阶段 4 已拒），
     * 只能由编排层 `writeWithDetails` 在写完主记录后注入。缺它写子项＝内部不变量被破坏，守卫拒。
     */
    val parentId: String? = null,
)

/**
 * 字段取值（JSON 无关的值代数）。core 零第三方依赖，故序列化只经 [ExtCodec]/装配层，这里不出现任何 JSON 库类型。
 *
 * 「键缺失」不是一种值——它由 [RecordDraft.values] 里没有该 api 表达（更新＝不触碰）。
 */
sealed interface DraftValue {
    /** 显式清空：ext 侧删除该键、真列侧绑 NULL（与 M1-05 D9「ext 空值＝键存在性」口径对齐）。 */
    data object Cleared : DraftValue

    /** 文本族（含日期时间/时间/位置等以串承载的型，见图纸 01 §2 值形态列）。 */
    data class Text(
        val value: String,
    ) : DraftValue

    data class Bool(
        val value: Boolean,
    ) : DraftValue

    /** 数字以**字面量字符串**承载：DECIMAL(30,8) 的精度不得经 Double 往返，渲染期才按目标列定型。 */
    data class Number(
        val literal: String,
    ) : DraftValue

    /** 多值形态（多选/标签/附件引用清单），元素恒为字符串码值。 */
    data class Many(
        val items: List<String>,
    ) : DraftValue
}

/**
 * 记录载荷：字段 api_name → 值。
 *
 * 未注册的 key **不在这里丢弃**——阶段 4 按「unknown ext key 拒收」出 410 并回显 key 名（卡面反例），
 * 静默丢键会让调用方的拼写错误变成无声的数据缺失。
 *
 * @property details 子项差量载荷（A1 定稿：`子对象 api_name → 该子表的行`）。语义与字段级 PATCH 同轴：
 *   **缺某个子对象键＝本次不碰该子表**；**给空数组＝清空该子表全部（软删）**。块 2 只支持一层——
 *   子行是 [DetailRow]（不含 details），孙级在类型上就无法表达、传输层解析到即拒（P10/A1）。
 */
data class RecordDraft(
    val values: Map<String, DraftValue> = emptyMap(),
    val details: Map<String, List<DetailRow>> = emptyMap(),
) {
    /** 本次是否触碰该字段（含显式清空）。 */
    fun touches(
        fieldApi: String,
    ): Boolean = values.containsKey(fieldApi)

    /** 触碰到的字段集合（守卫、diff、审计共用）。 */
    val touchedFields: Set<String> get() = values.keys

    /** 本次触碰到的子对象集合（缺键的不在内＝不碰）。 */
    val touchedDetails: Set<String> get() = details.keys

    companion object {
        fun of(
            vararg pairs: Pair<String, DraftValue>,
        ): RecordDraft = RecordDraft(pairs.toMap())
    }
}

/**
 * 子项行（A1）：`{id?, fields}`——复用字段值代数，独立成类而非塞进 [RecordDraft]，
 * 是为了让「只支持一层」成为**类型约束**（本类没有 details 槽，孙级写不出来），
 * 也让子行 id 与父记录 id（恒在 [WriteContext.recordId]）分处两地、不混。
 *
 * @property id 既有子行 id；null＝新建。带 id 但不在本父现存集内＝定位失败（404 `WRITE_NOT_FOUND`）。
 */
data class DetailRow(
    val id: String? = null,
    val fields: Map<String, DraftValue> = emptyMap(),
) {
    companion object {
        fun of(
            vararg pairs: Pair<String, DraftValue>,
        ): DetailRow = DetailRow(null, pairs.toMap())

        fun of(
            id: String,
            vararg pairs: Pair<String, DraftValue>,
        ): DetailRow = DetailRow(id, pairs.toMap())
    }
}

/** 单字段变化（图纸 04 §3 事件 diff 的 `{o,n}` 两端；`old`=null 表示原先无值）。 */
data class FieldDiff(
    val field: String,
    val old: DraftValue?,
    val new: DraftValue?,
)

/**
 * 写入结果（卡面 §1：`{id, version, warnings[]}`）。
 *
 * @property version 提交后的 `row_version`，供调用方下次带 [WriteContext.expectedVersion]
 * @property warnings 非致命告知（如未带版本却覆盖了当前值）。形状按 P6 定为类型化
 *   [WriteWarning]（`{code, field?, msg}`，与 420 的 `data.fields[]` 同一套词汇）——
 *   裸串 `CODE:detail` 日后要被 M5 审计与前端分派，届时改形状是破坏性的。
 */
data class WriteResult(
    val id: String,
    val version: Long,
    val kind: WriteKind,
    val diff: Map<String, FieldDiff> = emptyMap(),
    val warnings: List<WriteWarning> = emptyList(),
)

/**
 * 字段是否允许该写入路径触碰（元数据位 [FieldWritePolicy] → 判定，图纸 04 阶段 4 的只读裁决）。
 *
 * 放 core.meta 之外写成扩展函数：与 [cn.x.ac.kteasy.core.meta.MdField] 同属元数据语义，但只有写通道消费，
 * 别把写词汇倒灌进元数据层。
 */
fun MdField.writableOn(
    creating: Boolean,
): Boolean =
    when (writePolicy) {
        FieldWritePolicy.WRITABLE -> true

        FieldWritePolicy.NO_CREATE -> !creating

        FieldWritePolicy.NO_UPDATE -> creating

        // 元数据只读与自动化下发都不得由调用方提供值（后者 M3 经 SYSTEM 通道回写，本卡一并拒）。
        FieldWritePolicy.READONLY,
        FieldWritePolicy.DERIVED,
        -> false
    }

/** 必填作用域是否命中本次路径（`required=false` 时恒不命中，正交组合的另一半）。 */
fun MdField.isRequiredOn(
    creating: Boolean,
): Boolean =
    required &&
        when (requiredScope) {
            cn.x.ac.kteasy.core.meta.RequiredScope.ALWAYS -> true
            cn.x.ac.kteasy.core.meta.RequiredScope.CREATE -> creating
            cn.x.ac.kteasy.core.meta.RequiredScope.UPDATE -> !creating
        }
