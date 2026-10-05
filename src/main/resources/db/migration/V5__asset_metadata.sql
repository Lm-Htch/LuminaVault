alter table asset add column author varchar(255);
alter table asset add column metadata text;

create index idx_asset_author on asset (author);
