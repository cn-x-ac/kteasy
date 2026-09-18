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
 * 对象三型（【全景】§3.1：主/子项/独立）。
 *
 * - [PARENT]：主对象，可拥有子项对象；记录可发起审批。
 * - [CHILD]：子项对象，必须挂主（parent_object_id 必填）；随主读写、明细随主（M1-07 差量）。
 * - [PLAIN]：独立对象，无主无从。
 */
enum class ObjectKind {
    PARENT,
    CHILD,
    PLAIN,
}
