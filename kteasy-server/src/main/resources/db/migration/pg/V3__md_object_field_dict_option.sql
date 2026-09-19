-- V3（M1-03 压扁收尾）：md 区元数据模型 6 张表（模块图纸 01 §1，PG schema=md）。
-- 折入原 V4（display_name 显示名称模板，不再建 name_field_id）、原 V6（id/引用列钉 binary COLLATE "C"）。
-- 列集与 MySQL 版逻辑等价（jsonb↔json、timestamptz↔DATETIME(6)、boolean↔TINYINT(1)）。
-- md_dep / md_autonum_rule 归 M1-07 建表；storage_kind 含 N2N（落 r_* 关联表，图纸 01 §2）。
-- binary 规则：仅 id 及引用列（object_id/parent_object_id/ref_object_id/dict_id/option_set_id/
-- set_id/parent_id/created_by/updated_by）钉 COLLATE "C"；api_name/label/kind/path 等非标识符列保持库默认。

CREATE TABLE md.md_object (
    id                varchar(32)  COLLATE "C" NOT NULL,
    api_name          varchar(64)  NOT NULL,
    label             varchar(191) NOT NULL,
    kind              varchar(16)  NOT NULL,
    parent_object_id  varchar(32)  COLLATE "C",
    display_name      varchar(255),
    quick_search_json jsonb,
    status            varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    disabled          boolean      NOT NULL DEFAULT false,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        varchar(32)  COLLATE "C" NOT NULL,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        varchar(32)  COLLATE "C" NOT NULL,
    CONSTRAINT pk_md_object PRIMARY KEY (id),
    CONSTRAINT uk_md_object_api_name UNIQUE (api_name),
    CONSTRAINT ck_md_object_kind CHECK (kind IN ('PARENT', 'CHILD', 'PLAIN'))
);

CREATE TABLE md.md_field (
    id                varchar(32)  COLLATE "C" NOT NULL,
    object_id         varchar(32)  COLLATE "C" NOT NULL,
    api_name          varchar(64)  NOT NULL,
    label             varchar(191) NOT NULL,
    logical_type      varchar(24)  NOT NULL,
    storage_kind      varchar(16)  NOT NULL,
    required          boolean      NOT NULL DEFAULT false,
    default_json      jsonb,
    validation_json   jsonb,
    ui_json           jsonb,
    ref_object_id     varchar(32)  COLLATE "C",
    ref_any_objs_json jsonb,
    dict_id           varchar(32)  COLLATE "C",
    option_set_id     varchar(32)  COLLATE "C",
    seq               integer      NOT NULL DEFAULT 0,
    enabled           boolean      NOT NULL DEFAULT true,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_field PRIMARY KEY (id),
    CONSTRAINT uk_md_field_object_api UNIQUE (object_id, api_name),
    CONSTRAINT ck_md_field_storage CHECK (storage_kind IN ('EXT', 'COLUMN', 'N2N'))
);

CREATE INDEX ix_md_field_object ON md.md_field (object_id);

CREATE TABLE md.md_dict (
    id         varchar(32)  COLLATE "C" NOT NULL,
    name       varchar(191) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_dict PRIMARY KEY (id)
);

CREATE TABLE md.md_dict_item (
    id        varchar(32)  COLLATE "C" NOT NULL,
    dict_id   varchar(32)  COLLATE "C" NOT NULL,
    parent_id varchar(32)  COLLATE "C",
    path      varchar(512) NOT NULL,
    p_label   varchar(191) NOT NULL,
    seq       integer      NOT NULL DEFAULT 0,
    enabled   boolean      NOT NULL DEFAULT true,
    CONSTRAINT pk_md_dict_item PRIMARY KEY (id),
    CONSTRAINT fk_dict_item_dict FOREIGN KEY (dict_id) REFERENCES md.md_dict (id),
    CONSTRAINT fk_dict_item_parent FOREIGN KEY (parent_id) REFERENCES md.md_dict_item (id)
);

CREATE INDEX ix_md_dict_item_dict ON md.md_dict_item (dict_id);

CREATE TABLE md.md_option_set (
    id         varchar(32)  COLLATE "C" NOT NULL,
    name       varchar(191) NOT NULL,
    closed     boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_option_set PRIMARY KEY (id)
);

CREATE TABLE md.md_option (
    id         varchar(32)  COLLATE "C" NOT NULL,
    set_id     varchar(32)  COLLATE "C" NOT NULL,
    code       varchar(64)  NOT NULL,
    label      varchar(191) NOT NULL,
    seq        integer      NOT NULL DEFAULT 0,
    enabled    boolean      NOT NULL DEFAULT true,
    CONSTRAINT pk_md_option PRIMARY KEY (id),
    CONSTRAINT fk_option_set FOREIGN KEY (set_id) REFERENCES md.md_option_set (id)
);

CREATE INDEX ix_md_option_set ON md.md_option (set_id);
