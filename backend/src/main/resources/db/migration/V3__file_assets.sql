create table file_assets (
    id uuid primary key,
    project_id uuid not null references projects(id),
    kind varchar(30) not null,
    storage_key varchar(512) not null unique,
    original_filename varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes bigint not null check (size_bytes >= 0),
    sha256 char(64) not null,
    row_count integer,
    header_json jsonb,
    created_by uuid not null references users(id),
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz,
    deleted_at timestamptz,
    check (kind in ('SOURCE_CSV', 'CLEANED_CSV', 'ERROR_CSV', 'REPORT'))
);

create index file_assets_project_created_idx on file_assets(project_id, created_at desc);
create index file_assets_project_sha256_idx on file_assets(project_id, sha256);
create index file_assets_expiry_idx on file_assets(expires_at) where deleted_at is null;
