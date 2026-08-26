package de.makibytes.registerwerk.blockchain.internal;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Shared {@code chain_contract_subscription} SQL used by both the normal event-processing path
 *  ({@link ChaincacheLifecycleEventProcessor}) and the post-rollback failure path
 *  ({@link ChaincacheLifecycleFailureRecorder}) — both need to mark a subscription QUARANTINED
 *  under the exact same upsert semantics. */
final class ChaincacheSubscriptionSql {

    private ChaincacheSubscriptionSql() {
    }

    static void quarantineSubscription(JdbcTemplate jdbcTemplate, String durabilityDomainId,
            UUID chainConfigId, String chainKey, String consumerId) {
        jdbcTemplate.update("""
                INSERT INTO chain_contract_subscription
                  (durability_domain_id, chain_config_id, chain_key, consumer_id, subscription_state)
                VALUES (?, ?, ?, ?, 'QUARANTINED')
                ON CONFLICT (durability_domain_id, chain_config_id, consumer_id) DO UPDATE
                  SET subscription_state = 'QUARANTINED', updated_at = NOW()
                """, durabilityDomainId, chainConfigId, chainKey, consumerId);
    }
}
