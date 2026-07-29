package de.makibytes.registerwerk.support.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One message in a support ticket's thread — either the customer or an operator. */
@Entity
@Table(name = "support_ticket_message")
public class SupportTicketMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "author_is_operator", nullable = false)
    private boolean authorIsOperator;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public void setTicketId(UUID v) { this.ticketId = v; }
    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID v) { this.authorId = v; }
    public boolean isAuthorIsOperator() { return authorIsOperator; }
    public void setAuthorIsOperator(boolean v) { this.authorIsOperator = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public Instant getCreatedAt() { return createdAt; }
}
