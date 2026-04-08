package de.makibytes.registerwerk.domain.entity;

import jakarta.persistence.*;
import java.util.UUID;

/** Stores the binary content of inline KYC documents (≤ 5 MB).
 *  Kept in a separate table so metadata queries on {@link KycDocument} never load blobs. */
@Entity
@Table(name = "kyc_document_content")
public class KycDocumentContent {

    @Id
    private UUID id;  // same UUID as the owning KycDocument

    @Lob
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] content;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
