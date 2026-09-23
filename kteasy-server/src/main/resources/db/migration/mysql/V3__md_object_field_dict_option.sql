-- V3（M1-03 压扁收尾）：md 区元数据模型 6 张表（模块图纸 01 §1，MySQL 前缀 md_）。
-- 折入原 V4（display_name，不再建 name_field_id）、原 V6（id/引用列钉 COLLATE utf8mb4_bin）。
-- 列集与 PG 版逻辑等价（json↔jsonb、DATETIME(6)↔timestamptz、TINYINT(1)↔boolean）。
-- md_dep / md_autonum_rule 归 M1-07 建表；storage_kind 含 N2N（落 r_* 关联表，图纸 01 §2）。
-- binary 规则：仅 id 及引用列钉 utf8mb4_bin；api_name/label/kind/path 等非标识符列吃表默认 utf8mb4_0900_ai_ci。
-- M1-06 追加 md_field 两列：write_policy（服务端硬只读档位）、required_scope（必填作用域），
-- 均非标识符列故吃库默认 collation；取值域由 CHECK 钉住（与 core.meta 两个枚举一字不差）。

CREATE TABLE md_object (
    id                varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    api_name          varchar(64)  NOT NULL,
    label             varchar(191) NOT NULL,
    kind              varchar(16)  NOT NULL,
    parent_object_id  varchar(32)  COLLATE utf8mb4_bin,
    display_name      varchar(255),
    quick_search_json json,
    status            varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    disabled          boolean      NOT NULL DEFAULT false,
    created_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by        varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    updated_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by        varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_md_object_api_name UNIQUE (api_name),
    CONSTRAINT ck_md_object_kind CHECK (kind IN ('PARENT', 'CHILD', 'PLAIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE md_field (
    id                varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    object_id         varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    api_name          varchar(64)  NOT NULL,
    label             varchar(191) NOT NULL,
    logical_type      varchar(24)  NOT NULL,
    storage_kind      varchar(16)  NOT NULL,
    required          boolean      NOT NULL DEFAULT false,
    default_json      json,
    validation_json   json,
    ui_json           json,
    ref_object_id     varchar(32)  COLLATE utf8mb4_bin,
    ref_any_objs_json json,
    dict_id           varchar(32)  COLLATE utf8mb4_bin,
    option_set_id     varchar(32)  COLLATE utf8mb4_bin,
    seq               integer      NOT NULL DEFAULT 0,
    enabled           boolean      NOT NULL DEFAULT true,
    write_policy      varchar(16)  NOT NULL DEFAULT 'WRITABLE',
    required_scope    varchar(16)  NOT NULL DEFAULT 'ALWAYS',
    created_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_md_field_object_api UNIQUE (object_id, api_name),
    CONSTRAINT ck_md_field_storage CHECK (storage_kind IN ('EXT', 'COLUMN', 'N2N')),
    CONSTRAINT ck_md_field_write_policy CHECK (write_policy IN ('WRITABLE', 'NO_CREATE', 'NO_UPDATE', 'READONLY', 'DERIVED')),
    CONSTRAINT ck_md_field_required_scope CHECK (required_scope IN ('ALWAYS', 'CREATE', 'UPDATE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_md_field_object ON md_field (object_id);

CREATE TABLE md_dict (
    id         varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    name       varchar(191) NOT NULL,
    created_at datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE md_dict_item (
    id        varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    dict_id   varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    parent_id varchar(32)  COLLATE utf8mb4_bin,
    path      varchar(512) NOT NULL,
    p_label   varchar(191) NOT NULL,
    seq       integer      NOT NULL DEFAULT 0,
    enabled   boolean      NOT NULL DEFAULT true,
    PRIMARY KEY (id),
    CONSTRAINT fk_dict_item_dict FOREIGN KEY (dict_id) REFERENCES md_dict (id),
    CONSTRAINT fk_dict_item_parent FOREIGN KEY (parent_id) REFERENCES md_dict_item (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_md_dict_item_dict ON md_dict_item (dict_id);

CREATE TABLE md_option_set (
    id         varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    name       varchar(191) NOT NULL,
    closed     boolean      NOT NULL DEFAULT false,
    created_at datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE md_option (
    id      varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    set_id  varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    code    varchar(64)  NOT NULL,
    label   varchar(191) NOT NULL,
    seq     integer      NOT NULL DEFAULT 0,
    enabled boolean      NOT NULL DEFAULT true,
    PRIMARY KEY (id),
    CONSTRAINT fk_option_set FOREIGN KEY (set_id) REFERENCES md_option_set (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_md_option_set ON md_option (set_id);
