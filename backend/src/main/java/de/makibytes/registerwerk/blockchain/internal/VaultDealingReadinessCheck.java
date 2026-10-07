package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production readiness warning for forward pricing (T1-07): lists every confirmed ERC-7540 vault that has no
 * (readable) dealing cut-off on-chain. Such a vault is refused for new subscriptions in production mode
 * ({@code Erc7540AdminPort#requireDealingCutoffConfigured}) — this warning tells the operator why, and which
 * vaults need {@code POST /api/v1/deployments/{id}/dealing-cutoff}. Never blocks startup; the chain reads run on
 * their own thread so a slow node cannot delay it.
 */
@Component
class VaultDealingReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(VaultDealingReadinessCheck.class);

    private final Erc7540AdminPort vaults;
    private final VaultDealingSettings settings;

    VaultDealingReadinessCheck(Erc7540AdminPort vaults, VaultDealingSettings settings) {
        this.vaults = vaults;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (settings.production()) {
            Thread.ofVirtual().name("vault-dealing-readiness").start(this::warnAboutVaultsWithoutDealingCutoff);
        }
    }

    void warnAboutVaultsWithoutDealingCutoff() {
        if (!settings.production()) {
            return;
        }
        try {
            List<String> unconfigured = vaults.listVaultsWithoutDealingCutoff();
            if (!unconfigured.isEmpty()) {
                log.error("*** READINESS: {} ERC-7540 vault(s) have no usable dealing cut-off — forward pricing is "
                        + "NOT active (late-trading exposure) and new subscriptions on them are refused until an "
                        + "operator sets it (POST /api/v1/deployments/{id}/dealing-cutoff, step-up + second "
                        + "approver): {} ***", unconfigured.size(), String.join("; ", unconfigured));
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the vaults' dealing cut-offs: {}", e.getMessage());
        }
    }
}
