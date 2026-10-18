ALTER TABLE asset ADD COLUMN sort_key VARCHAR(512);

CREATE INDEX idx_asset_sort_key ON asset (sort_key);
