-- Historial de la zona horaria y la hora de corte de cada negocio. Cambiarlas NO reagrupa los días ya vividos: la regla nueva rige desde una
-- jornada futura y cada instante se asigna a su día con la regla que estaba vigente entonces.
CREATE TABLE business_day_rule (
    business_id    uuid        NOT NULL REFERENCES business (id),
    effective_from date        NOT NULL,   -- primera jornada (con esta regla) en que rige; 1970-01-01 = desde siempre
    timezone       text        NOT NULL,
    day_cutoff     time        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    PRIMARY KEY (business_id, effective_from)
);

INSERT INTO business_day_rule (business_id, effective_from, timezone, day_cutoff)
SELECT id, DATE '1970-01-01', timezone, day_cutoff FROM business;

ALTER TABLE business_day_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON business_day_rule FOR ALL TO cuadra_app
    USING (business_id = nullif(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = nullif(current_setting('app.business_id', true), '')::uuid);

-- Jornada comercial de un instante con la regla vigente en ese momento (la misma lógica que `BusinessDayService` en Java y `BusinessRange` en el teléfono).
CREATE FUNCTION business_date(bid uuid, ts timestamptz) RETURNS date
    LANGUAGE sql STABLE AS
$$
-- La regla vigente en `ts` da su fecha local; si hay una regla siguiente, el último día viejo termina donde empieza el primero nuevo
-- (por eso la fecha nunca pasa de "la víspera del cambio"): así los días son contiguos, igual que en Java y en el teléfono.
SELECT LEAST(
           ((ts AT TIME ZONE r.timezone) - CAST(r.day_cutoff AS interval))::date,
           COALESCE((SELECT n.effective_from - 1 FROM business_day_rule n WHERE n.business_id = bid AND n.effective_from > r.effective_from ORDER BY n.effective_from LIMIT 1), DATE 'infinity'))
  FROM business_day_rule r
 WHERE r.business_id = bid AND (r.effective_from = DATE '1970-01-01' OR ((r.effective_from + r.day_cutoff) AT TIME ZONE r.timezone) <= ts)
 ORDER BY r.effective_from DESC
 LIMIT 1
$$;
