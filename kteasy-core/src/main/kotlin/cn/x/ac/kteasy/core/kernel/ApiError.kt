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

import java.util.LinkedHashMap

/**
 * 全局错误契约注册表（步骤卡 M0-03 §3）。响应形状恒为三键 `{error_code, error_msg, data}`，
 * 语义自定（不照抄参照实现）。本卡只立基线段；M1+ 按【模块图纸 04】§2 追加分节
 * （412 EQL／413 校验／414 权限／415 引用／416 编号／417 导入／418 转换／419 审批），
 * 段号预留、不得与下列冲突。
 *
 * @property code 应用层错误码（进响应体 error_code）
 * @property defaultMessage 缺省人话提示（可被抛出点覆盖为带定位符的 error_msg）
 * @property httpStatus 映射到 HTTP 传输层的状态码（禁裸 500）
 */
enum class ApiError(
    val code: Int,
    val defaultMessage: String,
    val httpStatus: Int,
) {
    /** 未鉴权（缺/坏凭据）。 */
    UNAUTHORIZED(401, "未鉴权或凭据无效", 401),

    /** 无权访问或被限频。 */
    FORBIDDEN(403, "无权访问或触发限频", 403),

    /** 参数非法（格式/类型/必填）。 */
    INVALID_PARAM(410, "参数非法", 400),

    /** 业务约束不满足（含写入守卫拒绝、记录转换冲突等）。 */
    BUSINESS_RULE(420, "业务约束不满足", 409),

    /** 资源不存在。 */
    NOT_FOUND(404, "资源不存在", 404),

    /** 功能未实现（占位端点/尚未开工的能力）。 */
    NOT_IMPLEMENTED(501, "功能尚未实现", 501),

    /** 服务端内部错误（兜底，仍走契约体、不裸 500）。 */
    INTERNAL(500, "服务端内部错误", 500),
    ;

    /**
     * 组装严格三键契约体。用 `LinkedHashMap` 固定键序（error_code, error_msg, data），
     * 且不依赖任何 JSON 库的命名策略/注解（规避 Boot 4 换 Jackson 3 后的包名不确定性）。
     */
    fun toBody(
        message: String = defaultMessage,
        data: Any? = null,
    ): Map<String, Any?> =
        LinkedHashMap<String, Any?>().apply {
            put("error_code", code)
            put("error_msg", message)
            put("data", data)
        }
}
