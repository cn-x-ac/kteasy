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
package cn.x.ac.kteasy.server.account

import cn.x.ac.kteasy.core.account.DeptTreeErrors
import cn.x.ac.kteasy.core.account.TreePaths
import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.DeptService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 步骤卡 M2a-01 单元④ · 部门树双库端到端（真连 pg/mysql，两库命中同一组期望值）。
 *
 * 锁五条 GWT（卡面）：
 * 1. 三层树取非叶子树＝全部后代、不含兄弟子树成员；
 * 2. 把祖先挂到自己后代下成环 → 420 `DEPT_CYCLE`，无半棵树入库；
 * 3. 双库各灌同一棵树，子树集合逐元素相等（本 IT 跑两次：CI pg job 与 mysql job）；
 * 4. 移动子树 → path 级联重算：旧前缀查不到、新前缀查得到、无孤儿行；
 * 5. `enabled=false` 的部门仍在子树里（P23：停用≠消失）。
 *
 * **本表是新表、无其他 IT 占用**，@BeforeAll 直接清空，@AfterAll 再清一次，不做 id 追踪。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class DeptTreeIT {
    @Autowired
    lateinit var service: DeptService

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    lateinit var provider: SchemaProvider

    private lateinit var table: String

    // 树节点（create 后回填 id）
    private lateinit var a: DeptService.DeptRow // root seq=1
    private lateinit var b: DeptService.DeptRow // child of a, seq=1 (leaf)
    private lateinit var c: DeptService.DeptRow // child of a, seq=2
    private lateinit var d: DeptService.DeptRow // child of c, seq=1
    private lateinit var e: DeptService.DeptRow // child of c, seq=2
    private lateinit var f: DeptService.DeptRow // root seq=2

    @BeforeAll
    fun setup() {
        table = provider.namespace.qualified(LogicalArea.METADATA, "md_dept")
        clean()
        // 三层树：
        //   a(001)
        //   ├─ b(001/001)
        //   └─ c(001/002)
        //      ├─ d(001/002/001)
        //      └─ e(001/002/002)
        //   f(002)  ← 另一个根，验证"不含兄弟子树成员"
        a = service.create(DeptService.DeptCreateCmd(name = "A", seq = 1))
        b = service.create(DeptService.DeptCreateCmd(parentId = a.id, name = "B", seq = 1))
        c = service.create(DeptService.DeptCreateCmd(parentId = a.id, name = "C", seq = 2))
        d = service.create(DeptService.DeptCreateCmd(parentId = c.id, name = "D", seq = 1))
        e = service.create(DeptService.DeptCreateCmd(parentId = c.id, name = "E", seq = 2))
        f = service.create(DeptService.DeptCreateCmd(name = "F", seq = 2))
    }

    @AfterAll
    fun teardown() = clean()

    private fun clean() {
        // MySQL FK 不让直接 DELETE 父行；先置空 parent_id（FK 允许 NULL），再清表。两库通用。
        jdbc.update("UPDATE $table SET parent_id = NULL", emptyMap<String, Any>())
        jdbc.update("DELETE FROM $table", emptyMap<String, Any>())
    }

    private fun countLike(prefix: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM $table WHERE path = :exact OR path LIKE :like",
            mapOf("exact" to prefix, "like" to prefix + TreePaths.SEPARATOR + "%"),
            Int::class.java,
        ) ?: 0

    @Test
    fun `三层树取非叶子树等于全部后代不含兄弟子树成员`() {
        // subtree(c) 应＝{c, d, e}，不含 a、b、f
        val subtreeC = service.subtreeIds(c.id)
        assertThat(subtreeC).containsExactlyInAnyOrder(c.id, d.id, e.id)
        assertThat(subtreeC).doesNotContain(a.id, b.id, f.id)

        // subtree(a) 应＝{a, b, c, d, e}，不含 f
        val subtreeA = service.subtreeIds(a.id)
        assertThat(subtreeA).containsExactlyInAnyOrder(a.id, b.id, c.id, d.id, e.id)
        assertThat(subtreeA).doesNotContain(f.id)

        // subtree(f) 只＝{f}
        assertThat(service.subtreeIds(f.id)).containsExactly(f.id)
    }

    @Test
    fun `把祖先挂到自己后代下成环抛 DEPT_CYCLE 且无半棵树入库`() {
        // 移动前快照
        val beforeCount = jdbc.queryForObject("SELECT count(*) FROM $table", emptyMap<String, Any>(), Int::class.java)
        val beforeCPath = service.findById(c.id)!!.path

        assertThatThrownBy { service.move(a.id, d.id) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                assertThat(it.apiError).isEqualTo(ApiError.BUSINESS_RULE)
                assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo("DEPT_CYCLE")
            }

        // 无半棵树入库：行数不变、c 的 path 没变、a 的 parent_id 仍为 null
        val afterCount = jdbc.queryForObject("SELECT count(*) FROM $table", emptyMap<String, Any>(), Int::class.java)
        assertThat(afterCount).isEqualTo(beforeCount)
        assertThat(service.findById(c.id)!!.path).isEqualTo(beforeCPath)
        assertThat(service.findById(a.id)!!.parentId).isNull()
    }

    @Test
    fun `移动子树 path 级联重算旧前缀查不到新前缀查得到`() {
        // 移动前：旧前缀 001/002 命中 c,d,e 三行
        assertThat(countLike("001/002")).isEqualTo(3)
        assertThat(countLike("002/002")).isEqualTo(0)

        // 把 c 整棵子树移到 f 下（c.seq=2 在 f 下无兄弟，不撞 seq）
        service.move(c.id, f.id)

        // 移动后：c,d,e 的 path 全部以 002/002 开头；旧前缀 001/002 空
        assertThat(countLike("001/002")).`as`("旧前缀不应再命中任何行").isEqualTo(0)
        assertThat(countLike("002/002")).`as`("新前缀应命中 c,d,e 三行").isEqualTo(3)

        // 逐行核验 path：c=002/002, d=002/002/001, e=002/002/002
        assertThat(service.findById(c.id)!!.path).isEqualTo("002/002")
        assertThat(service.findById(d.id)!!.path).isEqualTo("002/002/001")
        assertThat(service.findById(e.id)!!.path).isEqualTo("002/002/002")

        // a 这一枝不受影响
        assertThat(service.findById(a.id)!!.path).isEqualTo("001")
        assertThat(service.findById(b.id)!!.path).isEqualTo("001/001")

        // 移回原位，恢复树，给后续用例留干净现场
        service.move(c.id, a.id)
        assertThat(service.findById(c.id)!!.path).isEqualTo("001/002")
    }

    @Test
    fun `停用部门仍在子树里 P23`() {
        // 建一个 enabled=false 的叶子挂在 c 下
        val off =
            service.create(
                DeptService.DeptCreateCmd(parentId = c.id, name = "OFF", seq = 3, enabled = false),
            )
        assertThat(off.enabled).isFalse

        // subtree(c) 必须仍含 off（停用≠记录消失，漏判即越权）
        val subtreeC = service.subtreeIds(c.id)
        assertThat(subtreeC).contains(off.id)

        // 顺手清掉，不污染别的用例（本类 @BeforeAll 已清，但这里自建自删更显式）
        jdbc.update("DELETE FROM $table WHERE id = :id", mapOf("id" to off.id))
    }

    @Test
    fun `悬空父创建被拒 DEPT_UNKNOWN_NODE 404`() {
        val ghost = "01JNOTREALDEPTID00000000000"
        assertThatThrownBy {
            service.create(DeptService.DeptCreateCmd(parentId = ghost, name = "Ghost", seq = 1))
        }.isInstanceOfSatisfying(KnownKteasyException::class.java) {
            assertThat(it.apiError).isEqualTo(ApiError.NOT_FOUND)
            assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo("DEPT_UNKNOWN_NODE")
        }
    }

    @Test
    fun `移动到不存在的部门抛 DEPT_UNKNOWN_NODE 404`() {
        val ghost = "01JNOTREALDEPTID00000000000"
        assertThatThrownBy { service.move(b.id, ghost) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                assertThat(it.apiError).isEqualTo(ApiError.BUSINESS_RULE)
                assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo("DEPT_DANGLING_PARENT")
            }
    }

    @Test
    fun `重复 code 拒绝 DEPT_CODE_DUPLICATED`() {
        service.create(DeptService.DeptCreateCmd(name = "Uniq", code = "IT-DUP", seq = 9))
        assertThatThrownBy {
            service.create(DeptService.DeptCreateCmd(name = "Uniq2", code = "IT-DUP", seq = 10))
        }.isInstanceOfSatisfying(KnownKteasyException::class.java) {
            assertThat(it.apiError).isEqualTo(ApiError.BUSINESS_RULE)
            assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo("DEPT_CODE_DUPLICATED")
        }
        jdbc.update("DELETE FROM $table WHERE code = 'IT-DUP'", emptyMap<String, Any>())
    }

    @Test
    fun `未知节点查子树抛 DEPT_UNKNOWN_NODE 404`() {
        val ghost = "01JNOTREALDEPTID00000000000"
        assertThatThrownBy { service.subtreeIds(ghost) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                assertThat(it.apiError).isEqualTo(ApiError.NOT_FOUND)
                assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo("DEPT_UNKNOWN_NODE")
            }
    }

    @Test
    fun `幂等移动父未变不重写 path`() {
        val before = service.findById(b.id)!!
        service.move(b.id, before.parentId)
        val after = service.findById(b.id)!!
        assertThat(after.path).isEqualTo(before.path)
        assertThat(after.parentId).isEqualTo(before.parentId)
    }
}
