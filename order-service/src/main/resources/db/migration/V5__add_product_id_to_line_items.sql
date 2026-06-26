ALTER TABLE line_items ADD COLUMN product_id UUID NOT NULL DEFAULT gen_random_uuid();
