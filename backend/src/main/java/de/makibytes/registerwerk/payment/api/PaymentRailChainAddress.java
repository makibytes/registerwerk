package de.makibytes.registerwerk.payment.api;

import jakarta.persistence.*;

import java.util.UUID;

/** Onchain deployment of a payment rail on one chain (token or settlement contract). */
@Entity
@Table(name = "payment_rail_chain_address")
public class PaymentRailChainAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_rail_id", nullable = false)
    private UUID paymentRailId;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "token_address", nullable = false, length = 66)
    private String tokenAddress;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPaymentRailId() { return paymentRailId; }
    public void setPaymentRailId(UUID paymentRailId) { this.paymentRailId = paymentRailId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getTokenAddress() { return tokenAddress; }
    public void setTokenAddress(String tokenAddress) { this.tokenAddress = tokenAddress; }
}
