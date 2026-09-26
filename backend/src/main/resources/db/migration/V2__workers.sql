create table workers (
    id uuid primary key,
    instance_name varchar(128) not null,
    worker_epoch uuid not null,
    last_heartbeat_at timestamptz not null,
    started_at timestamptz not null,
    status varchar(20) not null
);
