CREATE INDEX idx_asset_public_data ON asset USING GIN (public_data)
    WHERE public_data IS NOT NULL;

CREATE INDEX idx_asset_isin_btree ON asset (isin)
    WHERE isin IS NOT NULL;
