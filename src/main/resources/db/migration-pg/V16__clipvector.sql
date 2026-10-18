alter table asset_embedding add column clip_embedding_vec vector(512);

create index idx_asset_embedding_clip_vec on asset_embedding using hnsw (clip_embedding_vec vector_cosine_ops);
