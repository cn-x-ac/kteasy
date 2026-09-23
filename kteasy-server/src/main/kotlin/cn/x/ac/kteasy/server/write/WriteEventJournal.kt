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
package cn.x.ac.kteasy.server.write

import cn.x.ac.kteasy.core.write.WriteCommittedEvent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 写提交事件的**进程内环形账本**（图纸 04 阶段 12 的可观测面）。
 *
 * 存在的两个理由，缺一不可：
 * 1. 排障与后续 M3 执行谱系需要「这条记录什么时候、被谁、从哪个来源改过」的当场可查面——
 *    M5 审计落库之前，这是唯一的事实源；有界（[CAPACITY]）所以不会吃光内存。
 * 2. 让 `AFTER_COMMIT` 成为**可断言**的契约。卡面 GWT5 要求「事务内抛错→无半行、无事件」，
 *    如果事件只落日志，那条验收就只能靠读日志人工看，等于没验收。
 *
 * 只增不删语义：[snapshot] 给倒序最近事件；[clear] 供测试与运维归零。多副本部署下账本是**每进程**的
 * （无状态纪律：权威态在库里），跨副本汇总归 M5 审计，不在这里假装全局。
 */
@Component
class WriteEventJournal {
    private val events = ConcurrentLinkedDeque<WriteCommittedEvent>()

    fun record(
        event: WriteCommittedEvent,
    ) {
        events.addFirst(event)
        while (events.size > CAPACITY) {
            events.pollLast()
        }
    }

    /** 倒序（最近在前）快照。 */
    fun snapshot(): List<WriteCommittedEvent> = events.toList()

    fun forRecord(
        recordId: String,
    ): List<WriteCommittedEvent> = events.filter { it.recordId == recordId }

    fun clear() {
        events.clear()
    }

    private companion object {
        const val CAPACITY = 512
    }
}
