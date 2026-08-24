-- New deployments persist chain_config_id before their first chain write. Backfill legacy rows
-- where the (chain, network) pair resolves unambiguously to one enabled configuration. Rows that
-- remain NULL are deliberately visible for operator remediation rather than guessed when custom
-- installations contain multiple matching chain configurations.
WITH unique_match AS (
    SELECT d.id AS deployment_id, (array_agg(c.id))[1] AS chain_config_id
    FROM asset_deployment d
    JOIN chain_config c
      ON c.enabled = TRUE
     AND c.network_type = d.network
     AND left(c.identifier, length(d.chain) + 1) = d.chain || '_'
    WHERE d.chain_config_id IS NULL
    GROUP BY d.id
    HAVING count(*) = 1
)
UPDATE asset_deployment d
SET chain_config_id = m.chain_config_id
FROM unique_match m
WHERE d.id = m.deployment_id;
