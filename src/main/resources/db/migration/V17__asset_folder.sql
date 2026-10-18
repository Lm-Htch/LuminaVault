create table asset_folder
(
    asset_id   bigint                   not null references asset (id) on delete cascade,
    folder_id  bigint                   not null references folder (id) on delete cascade,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (asset_id, folder_id)
);

create index idx_asset_folder_folder on asset_folder (folder_id);

insert into asset_folder (asset_id, folder_id)
select id, folder_id from asset where folder_id is not null;

alter table asset drop column folder_id;
