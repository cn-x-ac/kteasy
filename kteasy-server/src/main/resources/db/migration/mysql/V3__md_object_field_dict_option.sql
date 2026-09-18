-- V3：md 区元数据模型 6 张表（模块图纸 01 §1，MySQL 前缀 md_）。PG 对应版本同目录 V3，
-- 列集逻辑等价（jsonb↔json、timestamptz↔DATETIME(6)、boolean↔TINYINT(1)）。
-- md_dep / md_autonum_rule 归 M1-07（recalc 依赖图与编号规则）建表，本卡不提前。
-- storage_kind 取值含 N2N（多引用落 r_* 关联表、既无真列也不进 ext；图纸 01 §2 已回写）。

CREATE TABLE md_object (
    id                varchar(32)  NOT NULL,
    api_name          varchar(64)  NOT NULL,
    label             varchar(191) NOT NULL,
    kind              varchar(16)  NOT NULL,
    parent_object_id  varchar(32),
    name_field_id     varchar(32),
    quick_search_json json,
    status            varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    disabled          boolean      NOT NULL DEFAULT false,
    created_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by        varchar(32)  NOT NULL,
    updated_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by        varchar(32)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_md_object_api_name UNIQUE (api_name),
    CONSTRAINT ck_md_object_kind CHECK (kind IN ('PARENT', 'CHILD', 'PLAIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE md_field (
    id                varchar(32)  NOT NULL,
    object_id         varchar(32)  NOT NULL,
    api_name          varchar(64)  NOT NULL,
    label             varchar(191) NOT NULL,
    logical_type      varchar(24)  NOT NULL,
    storage_kind      varchar(16)  NOT NULL,
    required          boolean      NOT NULL DEFAULT false,
    default_json      json,
    validation_json   json,
    ui_json           json,
    ref_object_id     varchar(32),
    ref_any_objs_json json,
    dict_id           varchar(32),
    option_set_id     varchar(32),
    seq               integer      NOT NULL DEFAULT 0,
    enabled           boolean      NOT NULL DEFAULT true,
    created_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_md_field_object_api UNIQUE (object_id, api_name),
    CONSTRAINT ck_md_field_storage CHECK (storage_kind IN ('EXT', 'COLUMN', 'N2N'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_md_field_object ON md_field (object_id);

CREATE TABLE md_dict (
    id         varchar(32)  NOT NULL,
    name       varchar(191) NOT NULL,
    created_at datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE md_dict_item (
    id        varchar(32)  NOT NULL,
    dict_id   varchar(32)  NOT NULL,
    parent_id varchar(32),
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
    id         varchar(32)  NOT NULL,
    name       varchar(191) NOT NULL,
    closed     boolean      NOT NULL DEFAULT false,
    created_at datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE md_option (
    id      varchar(32)  NOT NULL,
    set_id  varchar(32)  NOT NULL,
    code    varchar(64)  NOT NULL,
    label   varchar(191) NOT NULL,
    seq     integer      NOT NULL DEFAULT 0,
    enabled boolean      NOT NULL DEFAULT true,
    PRIMARY KEY (id),
    CONSTRAINT fk_option_set FOREIGN KEY (set_id) REFERENCES md_option_set (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_md_option_set ON md_option (set_id);
