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
package cn.x.ac.kteasy.server.config

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.ExtCodec
import cn.x.ac.kteasy.core.write.PassthroughWriteGuard
import cn.x.ac.kteasy.core.write.StrictJsonExtCodec
import cn.x.ac.kteasy.core.write.WriteCommittedEvent
import cn.x.ac.kteasy.core.write.WriteGuard
import cn.x.ac.kteasy.core.write.WritePipeline
import cn.x.ac.kteasy.core.write.WriteSqlRenderer
import cn.x.ac.kteasy.server.md.PinyinCodeGenerator
import cn.x.ac.kteasy.server.write.WriteEventJournal
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 写入通道的装配（与查询层 `QueryEngine` 的装配同处，保持「业务类不 import 具体方言实现」的红线⑤）。
 *
 * 两个接缝在这里被**明确选桩**，写下来是为了让 M2/M1-07 换实现时只有一个地方要动：
 * - [WriteGuard]＝透传桩（M2-02 换成权限守卫 bean 即可，管道与出口不变）；
 * - `autonum` 取号＝缺省 null（M1-07 实装编号规则）。
 */
@Configuration
class WriteConfig {
    @Bean
    fun extCodec(): ExtCodec = StrictJsonExtCodec()

    @Bean
    fun writeSqlRenderer(provider: SchemaProvider): WriteSqlRenderer = WriteSqlRenderer(provider)

    @Bean
    fun writeGuard(): WriteGuard = PassthroughWriteGuard()

    /**
     * 检索码生成器是 Kotlin `object`（M1-04 落地时无状态、无依赖），故直接引用而不绕 Spring 注入——
     * 少一个只为「可注入」而存在的空 bean；换实现时改这一处即可。
     */
    @Bean
    fun writePipeline(
        guard: WriteGuard,
    ): WritePipeline =
        WritePipeline(
            newId = { Ulid.next() },
            searchCode = { raw -> PinyinCodeGenerator.generate(raw) ?: raw },
            guard = guard,
        )
}

/**
 * 提交后事件的**唯一消费入口**（图纸 04 阶段 12、红线⑦）。
 *
 * `AFTER_COMMIT` 是刻意的：事务回滚时事件必须静默消失，否则自动化/审计会看到半条变更——
 * 卡面 GWT5（写入事务内抛错→无半行、无事件）就是打在这条上。
 * 现在它只做两件事：结构化日志（供 IT 断言事件时序）与后续订阅者的挂载点；
 * M3 自动化、M5 审计各自注册新监听器，不改本类、更不改写通道。
 */
@org.springframework.stereotype.Component
class WriteCommittedListener(
    private val journal: WriteEventJournal,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 先记账本再打日志：账本是卡面 GWT5（回滚即无事件）的断言对象，
     * 挂在 AFTER_COMMIT 上就意味着回滚的事务永远进不来——这正是验收要证的点。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommitted(
        event: WriteCommittedEvent,
    ) {
        journal.record(event)
        log.info(
            "写提交 event={} trace={} object={} id={} kind={} source={} user={} 变更={}",
            event.eventId,
            event.traceId,
            event.objectApi,
            event.recordId,
            event.kind,
            event.source,
            event.actor.userId,
            event.diff.keys,
        )
    }
}
