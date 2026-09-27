-- V6（M2a-01 单元②）：组织树 md_dept（模块图纸 13 §1「md_dept(树 path 物化，同 dict 法)」、图纸 05 §2 的 DEPT_CHILD 子树口径）。
-- 形态照抄 V3 的 md_dict_item：id 及引用列钉 COLLATE "C"、path 不钉（非标识符列保持库默认，与 V3 同口径——
-- 它的跨库等价性由 DeptTreeIT 双库真连证，不当常识）；层级与段宽的判据在 core.account.TreePaths（唯一真源），
-- 本表不放 CHECK，避免同一规则两处各写一份。
-- leader_user_id 属图纸缺项补列（图纸 01 §2③ 的显示名称模板示例 {owner_dept.leader} 需要它才有落点）：
-- **本卡只建列、不建外键**（md_user 在 V7 才存在），引用完整性暂由服务层判；V7 落库后随 M2 收尾 §A-7 squash 折进建表语句。
-- code（部门编码）为可空唯一列：M2d-06「账号导入按 code 优先解析、按名仅在唯一命中时接受」需要落点，
-- 现在建比将来 ALTER 便宜，且收尾 squash 不留二次迁移。

CREATE SCHEMA IF NOT EXISTS md;

CREATE TABLE md.md_dept (
    id             varchar(32)  COLLATE "C" NOT NULL,
    parent_id      varchar(32)  COLLATE "C",
    code           varchar(64)  COLLATE "C",
    name           varchar(191) NOT NULL,
    path           varchar(512) NOT NULL,
    seq            integer      NOT NULL DEFAULT 1,
    leader_user_id varchar(32)  COLLATE "C",
    enabled        boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_dept PRIMARY KEY (id),
    CONSTRAINT fk_md_dept_parent FOREIGN KEY (parent_id) REFERENCES md.md_dept (id),
    CONSTRAINT uk_md_dept_code UNIQUE (code)
);

-- 父链遍历（树上爬/求祖先）与"某父下有无子节点"是删除与移动的热路径，必须走索引。
-- path 的模式索引（PG text_pattern_ops / MySQL 前缀索引）本卡**不预建**：真正用子树前缀查询的是 M2b-04，
-- 届时由那条查询的 EXPLAIN 决定加哪种形态，比现在猜一个更便宜（口径见证据 §6）。
CREATE INDEX ix_md_dept_parent ON md.md_dept (parent_id);
