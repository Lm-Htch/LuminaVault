alter table asset_embedding add column image_embedding text;
alter table asset_embedding add column image_model varchar(128);

alter table character_reference add column model varchar(128);
