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

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException

/**
 * 写入通道护栏错误工厂（图纸 04 §1 阶段表 + §2 形状约定）。
 *
 * **错误码承载口径**（⟨可逆⟩代拍 D1，用户授权后由本卡定，下游各卡沿用）：
 * 契约码只发 M0-03 冻结的基座段（403/404/410/420），**不新增节段枚举值**；
 * 卡面/图纸里的符号名（413 校验、415 引用、416 编号…）一律落进响应 `data.error_id`。
 * 三条依据：① M1-05 已把「节段禁用」坐实进 `ApiErrorTest` 与 API 总表 §3，一卡破例则下游无单一规则可依；
 * ② 客户端真正的分派键已是 error_id 符号名，再加一层节段＝同一真相两处维护、必然漂移；
 * ③ 可逆性不对称——日后向枚举追加值是向后兼容的增补，现在焊死九节段则将来收不回。
 * 若日后确需 HTTP 传输层语义分层（如限流 429），改的是 [ApiError.httpStatus] 而非节段。
 *
 * 形状恒三键 `{error_code, error_msg, data}`；420 拒绝带 `data.fields[]` 明细（图纸 04 §2）。
 */
object WriteErrors {
    // ---------- 符号名清单（测试按 [ALL_IDS] 锁死，防悄悄新增未评审码） ----------

    const val ID_NOT_FOUND = "WRITE_NOT_FOUND"
    const val ID_FORBIDDEN = "WRITE_FORBIDDEN"
    const val ID_FIELD_TYPE = "FIELD_TYPE"
    const val ID_FIELD_REQUIRED = "FIELD_REQUIRED"
    const val ID_OPTION_DOMAIN = "OPTION_DOMAIN"
    const val ID_FIELD_READONLY = "FIELD_READONLY"
    const val ID_SYSTEM_COLUMN_READONLY = "SYSTEM_COLUMN_READONLY"
    const val ID_EXT_UNKNOWN_KEY = "EXT_UNKNOWN_KEY"

    /** 字段存在但已停用（`enabled=false`）仍被写（P3：与未注册键同档，但回显信息不同）。 */
    const val ID_FIELD_DISABLED = "FIELD_DISABLED"
    const val ID_OBJECT_DISABLED = "OBJECT_DISABLED"
    const val ID_CONFLICT_RETRY = "CONFLICT_RETRY"
    const val ID_LOCK_RETRY = "LOCK_RETRY"
    const val ID_FK_VIOLATION = "FK_VIOLATION"
    const val ID_IN_USE = "IN_USE"

    /** 编号取号失败（**M1-07 预留**：本卡只登记符号名与承载档，不产出）。 */
    const val ID_AUTONUM_FAILED = "AUTONUM_FAILED"

    /** 自动化同步钩子（数据校验/自动审批）拒绝（**M3 预留**：钩子不得另造形状，见图纸 07）。 */
    const val ID_AUTOMATION_REJECTED = "AUTOMATION_REJECTED"

    /**
     * 全部已登记符号名。**新增须同步图纸 04 与 API 总表**，且单测按精确集合相等锁死
     * （只断「无重复」等于没锁——新码会悄悄漂出去）。含 M1-07/M3 两个预留位。
     */
    val ALL_IDS: List<String> =
        listOf(
            ID_NOT_FOUND,
            ID_FORBIDDEN,
            ID_FIELD_TYPE,
            ID_FIELD_REQUIRED,
            ID_OPTION_DOMAIN,
            ID_FIELD_READONLY,
            ID_SYSTEM_COLUMN_READONLY,
            ID_EXT_UNKNOWN_KEY,
            ID_OBJECT_DISABLED,
            ID_CONFLICT_RETRY,
            ID_LOCK_RETRY,
            ID_FK_VIOLATION,
            ID_IN_USE,
            ID_FIELD_DISABLED,
            ID_AUTONUM_FAILED,
            ID_AUTOMATION_REJECTED,
        )

    /**
     * **承载码判据**（2026-09-23 用户裁决 P2，适用面＝写通道）：看「客户端改载荷能不能自救」。
     *
     * - 落在本集合里的符号名＝改载荷即可原样重发 → [ApiError.INVALID_PARAM]（410 / HTTP 400），
     *   前端据此高亮到具体字段；
     * - 不在本集合里的＝改载荷没用、得先改现场状态（值被别人改了、记录被引用、字段配置只读）
     *   → [ApiError.BUSINESS_RULE]（420 / HTTP 409），前端据此给"刷新/重试"而不是让人白改。
     *
     * 判据写成"符号名 → 承载码"的派生表，是为了让阶段 3/4 的每个裁决点都不必自己选码——
     * 选码的地方只有一处，下游卡（M3 校验、M4 转换、M5 导入）新增符号名时只需决定要不要进本表。
     *
     * 查询侧 `EQL_TYPE_MISMATCH` 走 420，是写通道之外的既有行为，不套用本判据（例外注记见
     * `specs/evidence/M1-06.md` §2.0-P2 与图纸 04 §2）。
     */
    val LOAD_FIXABLE_IDS: Set<String> =
        setOf(
            ID_FIELD_TYPE,
            ID_FIELD_REQUIRED,
            ID_OPTION_DOMAIN,
            ID_EXT_UNKNOWN_KEY,
            ID_FIELD_DISABLED,
        )

    /** 由符号名派生承载码的**唯一**入口（阶段 3/4 一律走这里，禁在调用点手选 [ApiError]）。 */
    fun violation(
        field: String,
        errorId: String,
        message: String,
    ): FieldViolation =
        FieldViolation(
            field,
            errorId,
            message,
            contract = if (errorId in LOAD_FIXABLE_IDS) ApiError.INVALID_PARAM else ApiError.BUSINESS_RULE,
        )

    /**
     * 单字段违规（阶段 3/4 的裁决产物）。
     *
     * @property field 字段 api_name（未注册键的拒绝场景＝那个野键名本身，卡面要求「回显 key 名」）
     * @property contract [ApiError] 承载码（410 或 420）
     */
    data class FieldViolation(
        val field: String,
        val errorId: String,
        val message: String,
        val contract: ApiError = ApiError.BUSINESS_RULE,
    )

    private fun single(
        apiError: ApiError,
        errorId: String,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = KnownKteasyException(apiError, message, extra + ("error_id" to errorId))

    /** 定位失败：带 id 但行不存在/已软删（图纸 04 阶段 1），HTTP 404 / code 404。 */
    fun notFound(
        objectApi: String,
        recordId: String,
    ): KnownKteasyException =
        single(
            ApiError.NOT_FOUND,
            ID_NOT_FOUND,
            "对象 [$objectApi] 下记录 [$recordId] 不存在或已删除",
            mapOf("object" to objectApi, "record_id" to recordId),
        )

    /**
     * 守卫拒绝（阶段 2；M2-02 真权限后走这条），HTTP 403 / code 403。
     *
     * **守卫类拒绝只有一个符号名**（P5）：`writeAllInTx` 的来源闸门、层级/条件权限、字段权限都经此出口，
     * 差异写进 [reason] 与 [extra]——否则守卫会长出第二个拒绝入口，「出口唯一」反被稀释。
     */
    fun forbidden(
        reason: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = single(ApiError.FORBIDDEN, ID_FORBIDDEN, "写入被拒绝：$reason", extra)

    /** 对象停用/归档仍被写（阶段 1/4 的治理位），HTTP 409 / code 420。 */
    fun objectDisabled(
        objectApi: String,
    ): KnownKteasyException = single(ApiError.BUSINESS_RULE, ID_OBJECT_DISABLED, "对象 [$objectApi] 已停用，不接受写入", mapOf("object" to objectApi))

    /**
     * 裁决拒绝的**唯一出口**：把阶段 3/4 收集到的违规一次性渲染成 420 + `data.fields[]`。
     *
     * 承载码取违规集里最"轻"的那个（全 410 则 410，含 420 则 420），保证同一批违规只出一个契约码，
     * 客户端按 `data.fields[].error_id` 逐条定位。
     */
    fun rejected(
        violations: List<FieldViolation>,
    ): KnownKteasyException {
        require(violations.isNotEmpty()) { "rejected() 需要至少一条违规" }
        val contract = if (violations.any { it.contract == ApiError.BUSINESS_RULE }) ApiError.BUSINESS_RULE else ApiError.INVALID_PARAM
        val fields = violations.map { linkedMapOf("field" to it.field, "error_id" to it.errorId, "msg" to it.message) }
        val body = LinkedHashMap<String, Any?>()
        body["error_id"] = violations.first().errorId
        body["fields"] = fields
        return KnownKteasyException(contract, violations.first().message, body)
    }

    /** 并发版本冲突（阶段 9 乐观并发；卡面 §3），HTTP 409 / code 420。 */
    fun conflictRetry(
        recordId: String,
        expected: Long,
        actual: Long,
    ): KnownKteasyException =
        single(
            ApiError.BUSINESS_RULE,
            ID_CONFLICT_RETRY,
            "记录 [$recordId] 已被他人修改（期望版本 $expected，实际 $actual），请重取后重试",
            mapOf("record_id" to recordId, "expected_version" to expected, "actual_version" to actual),
        )

    /**
     * 写锁获取超时（卡面 §5：`KTEASY:WRITE:` 命名锁超时 → 420 重试提示）。
     *
     * 图纸 04 把它归类为 `RETRY_429`；⟨可逆⟩代拍 D6 以卡面为准走 420（本卡不引入 429 承载位，
     * 限流语义真需要时按 D1 的 httpStatus 通道扩，不占节段）。
     */
    fun lockRetry(
        lockKey: String,
        timeoutSeconds: Int,
    ): KnownKteasyException =
        single(
            ApiError.BUSINESS_RULE,
            ID_LOCK_RETRY,
            "同一记录的写入正忙（锁 [$lockKey] 等待 ${timeoutSeconds}s 超时），请稍后重试",
            mapOf("lock" to lockKey, "timeout_seconds" to timeoutSeconds),
        )

    /** 落库外键违例（阶段 9 翻译驱动异常），HTTP 409 / code 420。 */
    fun fkViolation(
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = single(ApiError.BUSINESS_RULE, ID_FK_VIOLATION, message, extra)

    /** 删除/停用时被 FK 或 EXISTS 依赖占用（图纸 04 §1 软删特化），HTTP 409 / code 420。 */
    fun inUse(
        recordId: String,
        referencedBy: List<String>,
    ): KnownKteasyException =
        single(
            ApiError.BUSINESS_RULE,
            ID_IN_USE,
            "记录 [$recordId] 被其他数据引用，不能删除",
            mapOf("record_id" to recordId, "referenced_by" to referencedBy),
        )
}
