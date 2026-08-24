package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="repo_lifecycle_event")
public class RepoLifecycleEvent {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="repo_trade_id", nullable=false) private UUID repoTradeId;
    @Enumerated(EnumType.STRING) @Column(name="event_type", nullable=false, length=40) private RepoTypes.LifecycleEventType eventType;
    @Column(name="actor_entity_id", nullable=false) private UUID actorEntityId;
    @Column(name="actor_user_id") private UUID actorUserId;
    @Column(precision=38, scale=18) private BigDecimal amount;
    @Column(name="asset_id") private UUID assetId;
    @Column(precision=38, scale=18) private BigDecimal quantity;
    @Column(length=200) private String reference;
    @Column(length=1000) private String note;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt=Instant.now();
    public UUID getId(){return id;} public UUID getRepoTradeId(){return repoTradeId;} public void setRepoTradeId(UUID v){repoTradeId=v;}
    public RepoTypes.LifecycleEventType getEventType(){return eventType;} public void setEventType(RepoTypes.LifecycleEventType v){eventType=v;}
    public UUID getActorEntityId(){return actorEntityId;} public void setActorEntityId(UUID v){actorEntityId=v;}
    public UUID getActorUserId(){return actorUserId;} public void setActorUserId(UUID v){actorUserId=v;}
    public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=v;}
    public UUID getAssetId(){return assetId;} public void setAssetId(UUID v){assetId=v;}
    public BigDecimal getQuantity(){return quantity;} public void setQuantity(BigDecimal v){quantity=v;}
    public String getReference(){return reference;} public void setReference(String v){reference=v;}
    public String getNote(){return note;} public void setNote(String v){note=v;}
    public Instant getCreatedAt(){return createdAt;}
}

