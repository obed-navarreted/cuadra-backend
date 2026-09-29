-- Producto sin precio fijo ("OPEN"): el precio se pregunta al vender. `price_minor` queda como precio sugerido (0 = sin sugerencia).
ALTER TABLE product DROP CONSTRAINT product_pricing_check;
ALTER TABLE product ADD CONSTRAINT product_pricing_check CHECK (pricing IN ('FIXED', 'BY_WEIGHT', 'OPEN'));
