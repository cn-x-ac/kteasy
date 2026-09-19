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
package cn.x.ac.kteasy.core.schema

import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.StepKind.ADD_FK_COLUMN
import cn.x.ac.kteasy.core.schema.StepKind.ADD_INDEX_EXPR
import cn.x.ac.kteasy.core.schema.StepKind.ADD_VIRTUAL_COLUMN
import cn.x.ac.kteasy.core.schema.StepKind.BACKFILL_BATCH
import cn.x.ac.kteasy.core.schema.StepKind.CLEAN_EXT_KEY
import cn.x.ac.kteasy.core.schema.StepKind.CREATE_RTABLE
import cn.x.ac.kteasy.core.schema.StepKind.CREATE_TABLE
import cn.x.ac.kteasy.core.schema.StepKind.DROP_COLUMN
import cn.x.ac.kteasy.core.schema.StepKind.DROP_TABLE
import cn.x.ac.kteasy.core.schema.StepKind.SWITCH_READ
import cn.x.ac.kteasy.core.schema.dialect.ColumnType
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.PhysicalColumn
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 物化 diff 内核 L1 单测（步骤卡 M1-03「零 DDL 证明」的纯函数面）。
 *
 * 关键不变式：① 新建对象→CREATE_TABLE(+每 N2N 一张 CREATE_RTABLE)；② 标量（EXT）增删改→**零步**
 * （【规格】§5.2 硬验收「加 20 标量字段零步」的机器可证版）；③ 增量真列/N2N→ADD_FK_COLUMN/CREATE_RTABLE；
 * ④ 停用真列→DROP_COLUMN。EXT 字段在 CREATE_TABLE 里绝不落成列（只进 ext）。
 */
class SchemaDiffTest {
    private val obj = MdObject(id = "OBJ1", apiName = "customer", label = "客户", kind = ObjectKind.PLAIN)

    private val apiById = mapOf("OBJ2" to "contract")

    private fun extField(
        api: String,
        enabled: Boolean = true,
    ): MdField = MdField(id = "F_$api", objectId = "OBJ1", apiName = api, label = api, logicalType = LogicalType.TEXT, storageKind = StorageKind.EXT, enabled = enabled)

    private fun refField(api: String): MdField = MdField(id = "F_$api", objectId = "OBJ1", apiName = api, label = api, logicalType = LogicalType.REF, storageKind = StorageKind.COLUMN, refObjectId = "OBJ2", enabled = true)

    private fun n2nField(api: String): MdField = MdField(id = "F_$api", objectId = "OBJ1", apiName = api, label = api, logicalType = LogicalType.N2N, storageKind = StorageKind.N2N, refObjectId = "OBJ2", enabled = true)

    private fun dictField(api: String): MdField = MdField(id = "F_$api", objectId = "OBJ1", apiName = api, label = api, logicalType = LogicalType.DICT, storageKind = StorageKind.COLUMN, dictId = "D1", enabled = true)

    private fun input(
        fields: List<MdField>,
        actual: MaterializedState,
        parentApi: String? = null,
        meta: MdObject = this.obj,
    ) = DiffInput(meta, fields, apiById, parentApi, actual)

    @Test
    fun `新建对象 建表加每张N2N关联表 且标量字段不落列`() {
        val fields = listOf(extField("name"), refField("owner_ref"), n2nField("tags"))
        val steps = SchemaDiff.diff(input(fields, MaterializedState(tableExists = false)))

        assertThat(steps.map { it.kind }).containsExactly(CREATE_TABLE, CREATE_RTABLE)
        assertThat(steps.map { it.seq }).containsExactly(0, 1)

        val create = steps[0].op as StepOp.CreateTable
        val colNames = create.spec.columns.map { it.name }
        // 系统列 + ext 恒在；引用列 owner_ref 落列；标量 name 绝不落列（走 ext）。
        assertThat(colNames).contains("id", "owner_user", "ext", "owner_ref")
        assertThat(colNames).doesNotContain("name")
        assertThat(create.spec.foreignKeys.map { it.column }).containsExactly("owner_ref")
        assertThat(
            create.spec.foreignKeys
                .single()
                .refTable,
        ).isEqualTo("contract")

        val rel = steps[1].op as StepOp.CreateRelationTable
        assertThat(rel.spec.area).isEqualTo(LogicalArea.RELATION)
        assertThat(rel.spec.name).isEqualTo("customer_tags")
        assertThat(rel.spec.foreignKeys.map { it.refTable }).contains("customer", "contract")
        assertThat(
            rel.spec.uniques
                .single()
                .columns,
        ).hasSize(2)
    }

    @Test
    fun `已存在对象连加20标量字段 零步`() {
        val base = listOf(refField("owner_ref"))
        val twentyScalars = (1..20).map { extField("s$it") }
        val actual = MaterializedState(tableExists = true, columns = setOf("id", "owner_user", "ext", "owner_ref"))
        val steps = SchemaDiff.diff(input(base + twentyScalars, actual))
        assertThat(steps).isEmpty()
    }

    @Test
    fun `已存在对象新增1引用1多引用 恰发两类步`() {
        val actual = MaterializedState(tableExists = true, columns = setOf("id", "owner_user", "ext"))
        val steps = SchemaDiff.diff(input(listOf(refField("owner_ref"), n2nField("tags")), actual))
        assertThat(steps.map { it.kind }).containsExactly(ADD_FK_COLUMN, CREATE_RTABLE)
        val add = steps.first().op as StepOp.AddFkColumn
        assertThat(add.column.name).isEqualTo("owner_ref")
        assertThat(add.foreignKey?.refTable).isEqualTo("contract")
    }

    @Test
    fun `新增DICT真列 落路径列与左前缀btree索引`() {
        val actual = MaterializedState(tableExists = true, columns = setOf("id", "ext"))
        val steps = SchemaDiff.diff(input(listOf(dictField("stage")), actual))
        assertThat(steps.single().kind).isEqualTo(ADD_FK_COLUMN)
        val add = steps.single().op as StepOp.AddFkColumn
        assertThat(add.column.name).isEqualTo("stage")
        assertThat(add.index?.columns).containsExactly("stage")
        assertThat(add.foreignKey).isNull()
    }

    @Test
    fun `停用真列发删列 停用标量零步`() {
        val actual = MaterializedState(tableExists = true, columns = setOf("id", "ext", "owner_ref", "name"))
        // owner_ref 停用（在 fields 里但 enabled=false）→ 删列；name(EXT) 停用→不产步。
        val fields = listOf(refField("owner_ref").copy(enabled = false), extField("name", enabled = false))
        val steps = SchemaDiff.diff(input(fields, actual))
        assertThat(steps.map { it.kind }).containsExactly(DROP_COLUMN)
        assertThat((steps.single().op as StepOp.DropColumn).column).isEqualTo("owner_ref")
    }

    @Test
    fun `子对象建表挂parent_id外键`() {
        val child = MdObject(id = "OJC", apiName = "order_line", label = "明细", kind = ObjectKind.CHILD, parentObjectId = "OBJ1")
        val steps = SchemaDiff.diff(input(emptyList(), MaterializedState(tableExists = false), parentApi = "customer", meta = child))
        val create = steps.single().op as StepOp.CreateTable
        assertThat(create.spec.columns.map { it.name }).contains("parent_id")
        assertThat(
            create.spec.foreignKeys
                .single()
                .column,
        ).isEqualTo("parent_id")
        assertThat(
            create.spec.foreignKeys
                .single()
                .refTable,
        ).isEqualTo("customer")
    }

    @Test
    fun `对象标记删除且表存在只发DROP_TABLE 未建表则空`() {
        val gone = obj.copy(disabled = true)
        val exists = SchemaDiff.diff(input(emptyList(), MaterializedState(tableExists = true), meta = gone))
        assertThat(exists.map { it.kind }).containsExactly(DROP_TABLE)
        assertThat((exists.single().op as StepOp.DropTable).name).isEqualTo("customer")
        assertThat((exists.single().op as StepOp.DropTable).area).isEqualTo(LogicalArea.ENTITY)

        val notYet = SchemaDiff.diff(input(emptyList(), MaterializedState(tableExists = false), meta = gone))
        assertThat(notYet).isEmpty()
    }

    @Test
    fun `物理化计划 带索引五步不丢数幂等字段齐备`() {
        val column = PhysicalColumn("amount", ColumnType.BIGINT)
        val steps =
            SchemaDiff.planPhysicalize(
                objectId = "OBJ1",
                hostTable = "customer",
                fieldId = "F_amount",
                fieldApi = "amount",
                column = column,
                cast = ValueCast.LONG,
                makeIndex = true,
            )
        assertThat(steps.map { it.kind }).containsExactly(ADD_VIRTUAL_COLUMN, BACKFILL_BATCH, ADD_INDEX_EXPR, SWITCH_READ, CLEAN_EXT_KEY)
        assertThat(steps.map { it.seq }).containsExactly(0, 1, 2, 3, 4)

        assertThat((steps[0].op as StepOp.AddVirtualColumn).column).isEqualTo(column)
        val backfill = steps[1].op as StepOp.BackfillBatch
        assertThat(backfill.targetColumn).isEqualTo("amount")
        assertThat(backfill.batchSize).isEqualTo(2000)
        val index = steps[2].op as StepOp.AddIndexExpr
        assertThat(index.online).isTrue()
        assertThat(index.indexName).isEqualTo("ix_customer_amount")
        assertThat(index.keyPath).containsExactly("amount")
        assertThat((steps[3].op as StepOp.SwitchRead).fieldId).isEqualTo("F_amount")
        val clean = steps[4].op as StepOp.CleanExtKey
        // 不丢数护栏：清 key 只作用已回填的 targetColumn 行。
        assertThat(clean.targetColumn).isEqualTo("amount")
        assertThat(clean.keyPath).containsExactly("amount")
    }

    @Test
    fun `物理化计划 无索引则四步`() {
        val steps = SchemaDiff.planPhysicalize("OBJ1", "customer", "F_amount", "amount", PhysicalColumn("amount", ColumnType.BIGINT), ValueCast.LONG, makeIndex = false)
        assertThat(steps.map { it.kind }).containsExactly(ADD_VIRTUAL_COLUMN, BACKFILL_BATCH, SWITCH_READ, CLEAN_EXT_KEY)
        assertThat(steps.map { it.seq }).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `可物理化标量判据 COLUMN标量为真 关系型与EXT为假`() {
        val scalarCol = MdField(id = "F1", objectId = "OBJ1", apiName = "amount", label = "金额", logicalType = LogicalType.DECIMAL, storageKind = StorageKind.COLUMN, enabled = true)
        assertThat(SchemaDiff.isPhysicalizableScalar(scalarCol)).isTrue()
        assertThat(SchemaDiff.isPhysicalizableScalar(refField("owner_ref"))).isFalse()
        assertThat(SchemaDiff.isPhysicalizableScalar(dictField("stage"))).isFalse()
        assertThat(SchemaDiff.isPhysicalizableScalar(extField("name"))).isFalse()
    }
}
