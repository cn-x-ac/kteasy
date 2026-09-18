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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.kernel.MetadataChangedEvent
import cn.x.ac.kteasy.core.meta.MetadataGraph
import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 元数据图谱缓存（M1-01）：Caffeine AsyncCache 单飞加载全量快照 + 版本号。
 *
 * - **必须用 AsyncCache 而非同步 Cache**：同步 `cache.get(key, loader)` 在持有
 *   ConcurrentHashMap 桶锁期间执行装载（阻塞 DB I/O），JDK 21 虚拟线程在
 *   synchronized 内会钉死载体线程——M1-01 并发验收实测 550 线程全体冻结。
 *   AsyncCache 的等待者 join 同一 [CompletableFuture]（park 可卸载），装载跑在
 *   平台线程池，虚拟线程零钉死。
 * - **失效只挂事务提交后**（【规格】§8-⑦）：[MetadataCacheInvalidator] 以
 *   `@TransactionalEventListener(AFTER_COMMIT)` 监听 [MetadataChangedEvent]，
 *   回滚事务的事件静默丢弃，版本号不动——这是「回滚不脏读」的唯一保证点。
 * - 版本号单调不降：每次失效 +1；读路径先取版本再取快照，写路径提交后失效，
 *   读者最坏读到旧快照但版本号比对可感知（M1-05 查询层做版本一致性复检）。
 * - 快照恒为单条缓存条目（并发验收断言 `estimatedSize() == 1`）。
 * - Redis pub/sub 跨节点总线：配置位已预留（`kteasy.md.cache.redis-channel`），
 *   多节点接线归后续卡，本卡只做进程内失效。
 */
@Component
class MetadataGraphCache(
    private val service: MetadataService,
) {
    private val cache: AsyncCache<String, MetadataSnapshot> =
        Caffeine
            .newBuilder()
            .maximumSize(4)
            .buildAsync()

    private val version = AtomicLong(0)

    /** 当前元数据版本号（单调不降；失效一次 +1）。 */
    fun currentVersion(): Long = version.get()

    /** 取全量快照（单飞加载：并发首次读只有一次真正打库；等待者 join 同一 future）。 */
    fun snapshot(): MetadataSnapshot =
        cache
            .get(
                SNAPSHOT_KEY,
                java.util.function.BiFunction<String, java.util.concurrent.Executor, CompletableFuture<MetadataSnapshot>> { _, _ ->
                    CompletableFuture.supplyAsync({ service.loadSnapshot() })
                },
            ).join()!!

    /** 图谱读取：按对象 api_name 从快照投影。 */
    fun graph(api: String): Pair<Long, MetadataGraph> = currentVersion() to service.buildGraph(snapshot(), api)

    /** 缓存条目数（并发验收断言恒为 1）。 */
    fun estimatedSize(): Long = cache.synchronous().estimatedSize() ?: 0L

    /** 失效并推进版本号；只能由提交后事件触发。 */
    fun invalidate() {
        cache.synchronous().invalidate(SNAPSHOT_KEY)
        version.incrementAndGet()
    }

    private companion object {
        const val SNAPSHOT_KEY = "md:snapshot"
    }
}

/** 提交后失效器：md 写事务提交 → 清缓存 + 版本 +1；回滚事务不触发（§8-⑦ 唯一实现处）。 */
@Component
class MetadataCacheInvalidator(
    private val cache: MetadataGraphCache,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMetadataChanged(event: MetadataChangedEvent) {
        cache.invalidate()
    }
}
