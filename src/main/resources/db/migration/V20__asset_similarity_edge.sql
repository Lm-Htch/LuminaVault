CREATE TABLE asset_similarity_edge (
    a_id BIGINT NOT NULL,
    b_id BIGINT NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    channels VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_similarity_edge PRIMARY KEY (a_id, b_id),
    CONSTRAINT ck_similarity_edge_order CHECK (a_id < b_id),
    CONSTRAINT fk_similarity_edge_a FOREIGN KEY (a_id) REFERENCES asset (id) ON DELETE CASCADE,
    CONSTRAINT fk_similarity_edge_b FOREIGN KEY (b_id) REFERENCES asset (id) ON DELETE CASCADE
);

CREATE INDEX idx_similarity_edge_a ON asset_similarity_edge (a_id);
CREATE INDEX idx_similarity_edge_b ON asset_similarity_edge (b_id);
