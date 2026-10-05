alter table asset add column thumbnail_key varchar(512);
alter table asset add column description varchar(1024);
alter table asset add column deleted boolean not null default false;
alter table asset add column deleted_at timestamp with time zone;

create index idx_asset_deleted_at on asset (deleted_at);

create table app_setting
(
    setting_key   varchar(128) primary key,
    setting_value varchar(512)              not null,
    updated_at    timestamp with time zone not null default current_timestamp
);
