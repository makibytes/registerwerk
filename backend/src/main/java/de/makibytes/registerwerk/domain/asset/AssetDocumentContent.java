package de.makibytes.registerwerk.domain.asset;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "asset_document_content")
public class AssetDocumentContent {

    @Id
    private UUID id;

    @Column(name = "content", nullable = false)
    private byte[] content;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
