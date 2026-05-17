package de.makibytes.registerwerk.kyc.api;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stores the binary content of inline KYC documents (≤ 5 MB).
 *  Kept in a separate table so metadata queries on {@link KycDocument} never load blobs. */
@Entity
@Table(name = "kyc_document_content")
public class KycDocumentContent {

    @Id
    private UUID id;  // same UUID as the owning KycDocument

    // Explicit BYTEA mapping avoids OID/LOB handling mismatches on newer Hibernate/PostgreSQL combos.
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] content;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
