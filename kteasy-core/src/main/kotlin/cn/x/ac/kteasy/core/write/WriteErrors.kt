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
    const val ID_OBJECT_DISABLED = "OBJECT_DISABLED"
    const val ID_CONFLICT_RETRY = "CONFLICT_RETRY"
    const val ID_LOCK_RETRY = "LOCK_RETRY"
    const val ID_FK_VIOLATION = "FK_VIOLATION"
    const val ID_IN_USE = "IN_USE"
    const val ID_BATCH_SOURCE_FORBIDDEN = "BATCH_SOURCE_FORBIDDEN"

    /** 全部已登记符号名（M1-06 清单；新增须同步图纸 04 与 API 总表）。 */
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
            ID_BATCH_SOURCE_FORBIDDEN,
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

    /** 守卫拒绝（阶段 2；M2-02 真权限后走这条），HTTP 403 / code 403。 */
    fun forbidden(
        reason: String,
        extra: Map<String, Any?> = emptyMap(),
    ): KnownKteasyException = single(ApiError.FORBIDDEN, ID_FORBIDDEN, "写入被拒绝：$reason", extra)

    /** `writeAllInTx` 被非特权来源调用（卡面 §6），HTTP 403 / code 403。 */
    fun batchSourceForbidden(
        source: String,
    ): KnownKteasyException =
        single(
            ApiError.FORBIDDEN,
            ID_BATCH_SOURCE_FORBIDDEN,
            "同事务批量写 internal API 仅 SYSTEM/TRANSFORM/IMPORT 可用，当前来源 [$source]",
            mapOf("source" to source),
        )

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
