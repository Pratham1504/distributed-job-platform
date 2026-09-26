create table users (
    id uuid primary key,
    email varchar(320) not null unique,
    password_hash varchar(255) not null,
    role varchar(20) not null,
    created_at timestamptz not null default current_timestamp
);

create table projects (
    id uuid primary key,
    owner_id uuid not null references users(id),
    name varchar(100) not null,
    status varchar(20) not null,
    created_at timestamptz not null default current_timestamp,
    unique (owner_id, name)
);

create table refresh_tokens (
    id uuid primary key,
    user_id uuid not null references users(id),
    family_id uuid not null,
    parent_token_id uuid references refresh_tokens(id),
    token_hash char(64) not null unique,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    replaced_by_token_id uuid references refresh_tokens(id),
    last_used_at timestamptz
);
create index refresh_tokens_user_family_idx on refresh_tokens(user_id, family_id, revoked_at);

create table api_clients (
    id uuid primary key,
    project_id uuid not null references projects(id),
    name varchar(100) not null,
    key_prefix varchar(16) not null unique,
    key_hash varchar(255) not null,
    status varchar(20) not null,
    last_used_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    revoked_at timestamptz,
    unique (project_id, name)
);

create table jobs (
    id uuid primary key,
    project_id uuid not null references projects(id),
    job_type varchar(50) not null,
    payload jsonb not null,
    payload_hash char(64) not null,
    status varchar(20) not null,
    priority varchar(20) not null,
    scheduled_at timestamptz,
    idempotency_key varchar(128) not null,
    cancel_requested_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    completed_at timestamptz,
    unique (project_id, idempotency_key)
);
create index jobs_pending_schedule_idx on jobs(status, scheduled_at) where status = 'PENDING';
create index jobs_project_created_idx on jobs(project_id, created_at desc);
create index jobs_dispatch_idx on jobs(status, priority, created_at);

create table job_runs (
    id uuid primary key,
    job_id uuid not null references jobs(id),
    run_number integer not null,
    effect_id uuid not null unique,
    status varchar(20) not null,
    max_attempts smallint not null check (max_attempts between 1 and 4),
    attempt_count smallint not null default 0,
    dispatch_version integer not null default 0,
    next_dispatch_at timestamptz,
    manual_retry_key varchar(128),
    created_at timestamptz not null default current_timestamp,
    completed_at timestamptz,
    unique (job_id, run_number)
);
create unique index job_runs_manual_retry_key_idx on job_runs(job_id, manual_retry_key) where manual_retry_key is not null;
create index job_runs_dispatch_idx on job_runs(status, next_dispatch_at) where status in ('RETRY_WAIT', 'QUEUED');

create table execution_attempts (
    id uuid primary key,
    run_id uuid not null references job_runs(id),
    attempt_number smallint not null,
    worker_id uuid,
    worker_epoch uuid,
    lease_token uuid,
    lease_expires_at timestamptz,
    status varchar(30) not null,
    started_at timestamptz,
    finished_at timestamptz,
    error_code varchar(64),
    error_message text,
    result_json jsonb,
    result_ref text,
    created_at timestamptz not null default current_timestamp,
    unique (run_id, attempt_number)
);
create index execution_attempts_expired_lease_idx on execution_attempts(status, lease_expires_at) where status = 'RUNNING';

create table outbox_events (
    id uuid primary key,
    aggregate_type varchar(50) not null,
    aggregate_id uuid not null,
    event_type varchar(50) not null,
    payload jsonb not null,
    dedupe_key varchar(180) not null unique,
    created_at timestamptz not null default current_timestamp,
    published_at timestamptz,
    publish_attempts integer not null default 0,
    last_error text,
    locked_until timestamptz
);
create index outbox_unpublished_idx on outbox_events(published_at, locked_until, created_at) where published_at is null;

create table audit_events (
    id uuid primary key,
    project_id uuid references projects(id),
    actor_type varchar(20) not null,
    actor_id uuid,
    event_type varchar(80) not null,
    resource_type varchar(50) not null,
    resource_id uuid,
    metadata jsonb not null,
    created_at timestamptz not null default current_timestamp
);
