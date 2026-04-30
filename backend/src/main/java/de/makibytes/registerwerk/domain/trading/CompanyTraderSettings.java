package de.makibytes.registerwerk.domain.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company_trader_settings")
public class CompanyTraderSettings {

    @Id
    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_payment_option", nullable = false, length = 30)
    private PaymentOption defaultPaymentOption = PaymentOption.OFFCHAIN_SEPA;

    @Column(name = "immediate_settlement_enabled", nullable = false)
    private boolean immediateSettlementEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getLegalEntityId() {
        return legalEntityId;
    }

    public void setLegalEntityId(UUID legalEntityId) {
        this.legalEntityId = legalEntityId;
    }

    public PaymentOption getDefaultPaymentOption() {
        return defaultPaymentOption;
    }

    public void setDefaultPaymentOption(PaymentOption defaultPaymentOption) {
        this.defaultPaymentOption = defaultPaymentOption;
    }

    public boolean isImmediateSettlementEnabled() {
        return immediateSettlementEnabled;
    }

    public void setImmediateSettlementEnabled(boolean immediateSettlementEnabled) {
        this.immediateSettlementEnabled = immediateSettlementEnabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }
}
