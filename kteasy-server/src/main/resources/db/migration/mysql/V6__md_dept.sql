-- V6（M2a-01 单元②）：组织树 md_dept —— 与 pg/V6__md_dept.sql 列集逻辑等价（DATETIME(6)↔timestamptz）。
-- binary 规则同 V3/V4/V5：仅 id 及引用列（parent_id/code/leader_user_id）钉 utf8mb4_bin，path 保持表默认排序规则。
-- 层级/段宽判据在 core.account.TreePaths（唯一真源），不放 CHECK；leader_user_id 本卡不建外键（md_user 在 V7）。
-- code 可空唯一：MySQL 与 PG 同样「NULL 不参与唯一性」，但这条等价性由 DeptTreeIT 双库真连各测一次（§E 23 口径）。

CREATE TABLE md_dept (
    id             varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    parent_id      varchar(32)  COLLATE utf8mb4_bin,
    code           varchar(64)  COLLATE utf8mb4_bin,
    name           varchar(191) NOT NULL,
    path           varchar(512) NOT NULL,
    seq            int          NOT NULL DEFAULT 1,
    leader_user_id varchar(32)  COLLATE utf8mb4_bin,
    enabled        boolean      NOT NULL DEFAULT true,
    created_at     datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_md_dept_parent FOREIGN KEY (parent_id) REFERENCES md_dept (id),
    CONSTRAINT uk_md_dept_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 父链遍历与"某父下有无子节点"是移动/删除的热路径；path 模式索引留给 M2b-04 按 EXPLAIN 决定（见 pg 版注释）。
CREATE INDEX ix_md_dept_parent ON md_dept (parent_id);
