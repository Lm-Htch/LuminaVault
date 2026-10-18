CREATE OR REPLACE FUNCTION lumina_natural_key(value text) RETURNS text AS $$
SELECT COALESCE(string_agg(
        CASE
            WHEN part[1] <> '' THEN part[1]
            WHEN part[2] = '' THEN ''
            WHEN length(regexp_replace(part[2], '^0+', '')) = 0 THEN '000000000000'
            WHEN length(regexp_replace(part[2], '^0+', '')) <= 12
                THEN lpad(regexp_replace(part[2], '^0+', ''), 12, '0')
            ELSE ':' || regexp_replace(part[2], '^0+', '')
        END, ''), '')
FROM (
    SELECT regexp_matches(lower(value), '(\D*)(\d*)', 'g') AS part
) matches;
$$ LANGUAGE sql IMMUTABLE;

UPDATE asset SET sort_key = lumina_natural_key(original_filename) WHERE sort_key IS NULL;
