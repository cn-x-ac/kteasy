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

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.server.md.MetadataService.FieldCmd
import cn.x.ac.kteasy.server.md.MetadataService.ObjectCreateCmd
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * 验收⑤：并发 50 写（不同字段）+ 500 读图谱，30s 内无脏读（读者看到的版本号单调不降）、
 * 无双份缓存条目（cache size == 1）。写全部走服务事务（提交后失效），读全部走缓存快照。
 * 本机无库 skipped，CI service-matrix 真跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class MetadataCacheConcurrencyTest {
    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var service: MetadataService

    @Autowired
    lateinit var cache: MetadataGraphCache

    @Autowired
    lateinit var dataSource: DataSource

    private val dialect: String get() = if (context.dialect == Dialect.POSTGRESQL) "pg" else "mysql"

    private val s: String = MdTestSupport.suffix()
    private val parentApi = "m01conc$s"

    @Test
    fun `50 并发写不同字段加 500 读 - 版本单调不降且缓存单条目`() {
        val created =
            service.createObject(
                ObjectCreateCmd(
                    apiName = parentApi,
                    label = "并发压测对象",
                    kind = "PLAIN",
                    displayName = "{name}",
                    fields = listOf(FieldCmd(apiName = "name", label = "名称", logicalType = "TEXT")),
                ),
            )
        assertThat(created.apiName).isEqualTo(parentApi)

        val writers = 50
        val readers = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val errors = AtomicReference<Throwable?>(null)
        val dirtyReads =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val baseVersion = cache.currentVersion()

        // 50 写者：各建 1 个不同字段（提交 → AFTER_COMMIT 失效 → 版本 +1）
        repeat(writers) { i ->
            pool.submit {
                try {
                    start.await()
                    service.createField(
                        parentApi,
                        FieldCmd(apiName = "fld$i", label = "字段$i", logicalType = "TEXT", seq = i),
                    )
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        // 500 读者：循环读快照与版本，断言每个读者视角版本单调不降。
        // 读者逐次 sleep——纯热循环会吃满 CPU 反噬写者（首跑 17 分钟的教训）。
        val readerDone = CountDownLatch(readers)
        repeat(readers) {
            pool.submit {
                try {
                    start.await()
                    var last = -1L
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                    while (System.nanoTime() < deadline) {
                        val v = cache.currentVersion()
                        val snapshot = cache.snapshot()
                        if (v < last) dirtyReads.incrementAndGet()
                        last = v
                        // 快照对象数恒非空（防读到半构造状态）
                        assertThat(snapshot.objects).isNotEmpty()
                        if (done.count == 0L && v >= baseVersion + writers) break
                        TimeUnit.MILLISECONDS.sleep(10)
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                } finally {
                    readerDone.countDown()
                }
            }
        }

        start.countDown()
        val allDone = done.await(30, TimeUnit.SECONDS) && readerDone.await(30, TimeUnit.SECONDS)
        pool.shutdown()

        assertThat(errors.get()).isNull()
        assertThat(allDone).isTrue()
        assertThat(dirtyReads.get()).isEqualTo(0)
        // 版本恰好推进 writers 次（每笔提交恰好一次失效）
        assertThat(cache.currentVersion()).isEqualTo(baseVersion + writers)
        // 无双份缓存条目
        assertThat(cache.estimatedSize()).isEqualTo(1L)
        // 全部字段可见
        assertThat(cache.snapshot().fields.count { it.objectId == created.id }).isEqualTo(writers + 1)
    }

    @AfterAll
    fun cleanup() {
        val like = "%$s%"
        val physical = MdTestSupport.table(dialect, "md_object")
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "DELETE FROM ${MdTestSupport.table(dialect, "md_field")} WHERE object_id IN (SELECT id FROM $physical WHERE api_name LIKE ?)",
                ).use { ps ->
                    ps.setString(1, like)
                    ps.executeUpdate()
                }
            conn.prepareStatement("DELETE FROM $physical WHERE api_name LIKE ?").use { ps ->
                ps.setString(1, like)
                ps.executeUpdate()
            }
        }
        // 用后即焚：防止缓存里残留已删对象污染同上下文的其他测试
        cache.invalidate()
    }
}
