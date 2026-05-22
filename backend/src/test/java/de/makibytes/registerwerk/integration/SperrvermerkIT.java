package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.internal.SperrvermerkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("§16 eWpG Sperrvermerk integration tests")
class SperrvermerkIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired
    private SperrvermerkService service;

    @Autowired
    private HolderBlockRepository repository;

    @Test
    @DisplayName("Create block — persists ACTIVE Sperrvermerk for wallet")
    void createBlock_persistsActiveBlock() {
        String wallet = "0xDeAdBeEf" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        UUID createdBy = UUID.randomUUID();

        HolderBlock block = new HolderBlock();
        block.setWalletAddress(wallet);
        block.setBlockType(HolderBlock.BlockType.GERICHTSBESCHLUSS);
        block.setLegalBasis("Court order ref: LG Frankfurt Az. 2-04 O 123/26");

        HolderBlock saved = service.create(block, createdBy);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(HolderBlock.Status.ACTIVE);
        assertThat(saved.getCreatedBy()).isEqualTo(createdBy);
    }

    @Test
    @DisplayName("Find active blocks by wallet — returns only ACTIVE blocks")
    void findActiveByWallet_returnsOnlyActiveBlocks() {
        String wallet = "0xCaFeBaBe" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        UUID createdBy = UUID.randomUUID();

        HolderBlock block = new HolderBlock();
        block.setWalletAddress(wallet);
        block.setBlockType(HolderBlock.BlockType.PFANDRECHT);
        block.setLegalBasis("Pledge agreement ref: P-2026-001");
        service.create(block, createdBy);

        List<HolderBlock> active = service.findActiveByWallet(wallet);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(HolderBlock.Status.ACTIVE);
    }

    @Test
    @DisplayName("Lift block — transitions ACTIVE → LIFTED")
    void liftBlock_transitionsToLifted() {
        String wallet = "0xFeedFace" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        UUID createdBy = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        HolderBlock block = new HolderBlock();
        block.setWalletAddress(wallet);
        block.setBlockType(HolderBlock.BlockType.PFAENDUNG);
        block.setLegalBasis("Attachment by creditor — court ref: AG München 1 M 5678/26");
        HolderBlock saved = service.create(block, createdBy);

        HolderBlock lifted = service.lift(saved.getId(), createdBy, "Debt settled", approver);

        assertThat(lifted.getStatus()).isEqualTo(HolderBlock.Status.LIFTED);
        assertThat(lifted.getLiftReason()).isEqualTo("Debt settled");
        assertThat(lifted.getLiftedBy()).isEqualTo(createdBy);
    }
}
