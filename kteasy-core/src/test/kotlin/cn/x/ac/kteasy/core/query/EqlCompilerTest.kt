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
package cn.x.ac.kteasy.core.query

import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 步骤卡 M1-05 块2 · 元数据感知逻辑编译 L1（不连库）：
 * ① ext 定位与 cast；② 类型×算子矩阵放行/拒绝；③ 点链 REF join；④ N2N has→EXISTS、数组 has；
 * ⑤ 拼音伴生列识别；⑥ 软删恒注入；⑦ 未知字段/字面量类型错误；⑧ 聚合合法性与禁跨端聚合。
 */
class EqlCompilerTest {
    private val customer = MdObject(id = "C1", apiName = "customer", label = "客户", kind = ObjectKind.PLAIN, quickSearchJson = "[\"name\"]")
    private val emp = MdObject(id = "E1", apiName = "emp", label = "员工", kind = ObjectKind.PLAIN)
    private val order = MdObject(id = "O1", apiName = "order", label = "订单", kind = ObjectKind.PLAIN)

    private fun f(
        id: String,
        obj: String,
        api: String,
        type: LogicalType,
        storage: StorageKind,
        refTarget: String? = null,
    ): MdField = MdField(id = id, objectId = obj, apiName = api, label = api, logicalType = type, storageKind = storage, refObjectId = refTarget)

    private val lookup: MetadataLookup =
        object : MetadataLookup {
            private val objects = listOf(customer, emp, order)
            private val fields =
                listOf(
                    f("n1", "C1", "name", LogicalType.TEXT, StorageKind.EXT),
                    f("n2", "C1", "amount", LogicalType.NUMBER, StorageKind.EXT),
                    f("n3", "C1", "status", LogicalType.PICKLIST, StorageKind.EXT),
                    f("n4", "C1", "active", LogicalType.BOOL, StorageKind.EXT),
                    f("n5", "C1", "born", LogicalType.DATE, StorageKind.EXT),
                    f("n6", "C1", "tags", LogicalType.MULTISELECT, StorageKind.EXT),
                    f("n7", "C1", "manager", LogicalType.REF, StorageKind.COLUMN, "E1"),
                    f("n8", "C1", "accounts", LogicalType.N2N, StorageKind.N2N, "E1"),
                    f("e1", "E1", "name", LogicalType.TEXT, StorageKind.EXT),
                    f("o1", "O1", "no", LogicalType.TEXT, StorageKind.EXT),
                    f("o2", "O1", "cust", LogicalType.REF, StorageKind.COLUMN, "C1"),
                    f("o3", "O1", "total", LogicalType.NUMBER, StorageKind.EXT),
                )

            override fun objectByApi(api: String) = objects.firstOrNull { it.apiName == api }

            override fun objectById(id: String) = objects.firstOrNull { it.id == id }

            override fun fieldsByObject(objectId: String) = fields.filter { it.objectId == objectId }
        }

    private fun compile(
        eql: String,
    ): QueryPlan = EqlCompiler.compile(EqlParser.parse(eql), lookup)

    private fun err(
        eql: String,
    ): KnownKteasyException =
        try {
            compile(eql)
            throw AssertionError("应抛错：$eql")
        } catch (e: KnownKteasyException) {
            e
        }

    private fun andParts(
        plan: QueryPlan,
    ): List<RExpr> = (plan.where as RExpr.And).parts

    @Test
    fun `ext 字段定位与 cast 且软删恒注入`() {
        val plan = compile("from customer where amount > 50000")
        val cmp = andParts(plan)[1] as RExpr.Cmp
        assertThat(cmp.location).isEqualTo(ValueLocation.Ext("t0", JsonPath.of("amount"), ValueCast.LONG))
        assertThat(cmp.op).isEqualTo(CmpOp.GT)
        assertThat((cmp.rhs as Rhs.Val).operand).isEqualTo(TypedOperand(ValueCast.LONG, Literal.Num("50000")))
        assertThat(andParts(plan)[0]).isEqualTo(RExpr.IsNull(ValueLocation.Column("t0", "deleted_at"), false))
    }

    @Test
    fun `无 where 时仍仅软删谓词`() {
        val plan = compile("from customer")
        assertThat(plan.where).isEqualTo(RExpr.IsNull(ValueLocation.Column("t0", "deleted_at"), false))
    }

    @Test
    fun `布尔字段非布尔字面量被拒`() {
        assertThat((err("from customer where active = 'x'")).apiError).isEqualTo(cn.x.ac.kteasy.core.kernel.ApiError.BUSINESS_RULE)
    }

    @Test
    fun `TEXT 禁序比较与 NUMBER 禁模糊匹配`() {
        assertThat((err("from customer where name > 'a'")).data).isNotNull // ORD 不支持 TEXT
        assertThat((err("from customer where amount ~ '1'")).data).isNotNull // MATCH 不支持 NUMBER
    }

    @Test
    fun `like 前缀与 ~ 命中拼音伴生列`() {
        val like = (compile("from customer where name like 'ab%'").where as RExpr.And).parts[1] as RExpr.Like
        assertThat(like.location).isEqualTo(ValueLocation.Ext("t0", JsonPath.of("name"), ValueCast.TEXT))
        assertThat(like.prefix).isEqualTo("ab")

        val match = ((compile("from customer where name ~ 'kehu'").where) as RExpr.And).parts[1] as RExpr.Match
        assertThat(match.pinyin).isEqualTo(ValueLocation.Column("t0", "name_pinyin"))
        assertThat(match.term).isEqualTo("kehu")
    }

    @Test
    fun `REF 点链产生 join 并定位到目标表别名`() {
        val plan = compile("from customer where manager.name = 'x'")
        assertThat(plan.joins).containsExactly(Join("t1", "emp", "t0", "manager"))
        val cmp = andParts(plan)[1] as RExpr.Cmp
        assertThat(cmp.location).isEqualTo(ValueLocation.Ext("t1", JsonPath.of("name"), ValueCast.TEXT))
    }

    @Test
    fun `N2N has 产生 EXISTS`() {
        val n2n = (compile("from customer where has(accounts)").where as RExpr.And).parts[1] as RExpr.N2n
        assertThat(n2n.n2n.relTable).isEqualTo("customer_accounts")
        assertThat(n2n.n2n.srcColumn).isEqualTo("src_customer_id")
        assertThat(n2n.n2n.dstColumn).isEqualTo("dst_emp_id")
        assertThat(n2n.n2n.hostAlias).isEqualTo("t0")
    }

    @Test
    fun `数组 has 值走 arrayMember`() {
        val am = (compile("from customer where has(tags, 'vip')").where as RExpr.And).parts[1] as RExpr.ArrayMember
        assertThat(am.ext.key).isEqualTo(JsonPath.of("tags"))
        assertThat(am.value).isEqualTo(Literal.Str("vip"))
    }

    @Test
    fun `日期 within 保留 token 与类型`() {
        val w = (compile("from customer where born within last7d").where as RExpr.And).parts[1] as RExpr.Within
        assertThat(w.logicalType).isEqualTo(LogicalType.DATE)
        assertThat(w.token).isEqualTo(DateToken.Relative(RelativeDirection.LAST, 7, CalendarUnit.DAY))
    }

    @Test
    fun `未知对象与未知字段分别拒绝`() {
        assertThat((err("from ghost where a = 1")).data).isNotNull
        assertThat((err("from customer where nope = 1")).data).isNotNull
    }

    @Test
    fun `聚合放行与禁跨端`() {
        val sumPlan = compile("select sum(amount) from customer")
        assertThat((sumPlan.select[0] as SelectPlan.Aggregate).agg.fn).isEqualTo(AggFn.SUM)
        assertThat(err("select sum(name) from customer").data).isNotNull // SUM 非数值
        assertThat(err("select max(manager.name) from customer").data).isNotNull // 禁跨端聚合（点链末端）
    }

    @Test
    fun `引用型系统列首跳定位到真列`() {
        // 合法 26 位 ULID 形状（Ref.validate 会挡畸形引用 id）
        val cmp = (compile("from customer where owner_user = '01ARZ3NDEKTSV4RRFFQ69G5FAV'").where as RExpr.And).parts[1] as RExpr.Cmp
        assertThat(cmp.location).isEqualTo(ValueLocation.Column("t0", "owner_user"))
    }

    @Test
    fun `字段对字段比较解析为两侧定位`() {
        val cmp = (compile("from customer where amount = amount").where as RExpr.And).parts[1] as RExpr.Cmp
        assertThat(cmp.rhs).isEqualTo(Rhs.Loc(ValueLocation.Ext("t0", JsonPath.of("amount"), ValueCast.LONG)))
    }
}
