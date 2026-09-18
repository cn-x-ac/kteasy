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

/**
 * 引擎异常基类（【规格】§1.1）。core 保持纯库：只继承 JDK 运行时异常，不依赖 Spring。
 */
abstract class KteasyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 已知业务异常：携带 [ApiError] 契约码与可选定位数据（如 420 的 `data.fields[]`）。
 * 由全局异常处理器统一渲染成三键契约体（见 server 层）。禁裸 500——一切可预期错误都应走此类型。
 */
class KnownKteasyException(
    val apiError: ApiError,
    message: String = apiError.defaultMessage,
    val data: Any? = null,
    cause: Throwable? = null,
) : KteasyException(message, cause) {
    constructor(apiError: ApiError) : this(apiError, apiError.defaultMessage, null, null)
}
