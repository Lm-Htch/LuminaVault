create extension if not exists vector;
create extension if not exists pg_trgm;

alter table asset_embedding add column image_embedding_vec vector(768);
alter table character_reference add column embedding_vec vector(768);

create index idx_asset_embedding_image_vec on asset_embedding using hnsw (image_embedding_vec vector_cosine_ops);
create index idx_character_reference_vec on character_reference using hnsw (embedding_vec vector_cosine_ops);

create index idx_asset_filename_trgm on asset using gin (original_filename gin_trgm_ops);
create index idx_asset_author_trgm on asset using gin (author gin_trgm_ops);

update asset_embedding set image_embedding_vec = image_embedding::vector
where image_embedding is not null and image_model = 'wd14-image';

update character_reference set embedding_vec = embedding::vector
where embedding is not null and model = 'wd14-image';
