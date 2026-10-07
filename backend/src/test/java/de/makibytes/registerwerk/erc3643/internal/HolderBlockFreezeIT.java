package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H5 against a real database: V51 matches the entity mapping, the (block, deployment, wallet) row is unique, and the
 * targeted {@code on_chain_freeze_tx_hash} update never rewrites the block's status (a full-row save of a stale entity
 * would un-lift a block that was lifted concurrently).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("holder_block_freeze (integration)")
class HolderBlockFreezeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired HolderBlockFreezeRepository freezes;
    @Autowired HolderBlockRepository blocks;
    @Autowired AssetRepository assetRepository;
    @Autowired AssetDeploymentRepository deploymentRepository;
    @Autowired LegalEntityRepository legalEntityRepository;
    @Autowired TransactionTemplate transactions;

    private HolderBlock block() {
        HolderBlock b = new HolderBlock();
        b.setWalletAddress("0x" + UUID.randomUUID().toString().replace("-", "") + "00000000");
        b.setBlockType(HolderBlock.BlockType.GERICHTSBESCHLUSS);
        b.setLegalBasis("LG Frankfurt 2-04 O 1/26");
        b.setCreatedBy(UUID.randomUUID());
        return blocks.saveAndFlush(b);
    }

    private AssetDeployment deployment() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity issuer = new LegalEntity();
        issuer.setEntityNumber("FRZ-" + suffix);
        issuer.setType(EntityType.ISSUER);
        issuer.setStatus(EntityStatus.ACTIVE);
        issuer.setCurrentName("Freeze IT issuer");
        issuer = legalEntityRepository.saveAndFlush(issuer);
        Asset asset = new Asset();
        asset.setAssetNumber("AST-" + suffix);
        asset.setIssuerId(issuer.getId());
        asset.setName("Freeze IT");
        asset.setTokenStandard(TokenStandard.ERC20);
        asset = assetRepository.saveAndFlush(asset);
        AssetDeployment d = new AssetDeployment();
        d.setAssetId(asset.getId());
        d.setChain(Chain.ETHEREUM);
        d.setNetwork(Network.TESTNET);
        d.setContractAddress("0x" + "cc".repeat(20));
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return deploymentRepository.saveAndFlush(d);
    }

    @Test
    @DisplayName("a row round-trips; (block, deployment, wallet) is unique; status counts and tx lookup work")
    void rowRoundTripAndUniqueness() {
        HolderBlock block = block();
        AssetDeployment dep = deployment();
        UUID txId = UUID.randomUUID();

        HolderBlockFreeze row = new HolderBlockFreeze(block.getId(), dep.getId(), "0xabc");
        row.submitted(HolderBlockFreeze.Status.SUBMITTED, txId, null);
        row = freezes.saveAndFlush(row);

        assertThat(freezes.findByTxId(txId)).extracting(HolderBlockFreeze::getId).containsExactly(row.getId());
        assertThat(freezes.findByHolderBlockIdAndDeploymentIdAndWalletAddress(block.getId(), dep.getId(), "0xabc"))
                .get().extracting(HolderBlockFreeze::getStatus).isEqualTo(HolderBlockFreeze.Status.SUBMITTED);
        assertThat(freezes.countByStatus(HolderBlockFreeze.Status.SUBMITTED)).isGreaterThanOrEqualTo(1);
        assertThat(freezes.findByStatusIn(java.util.List.of(HolderBlockFreeze.Status.SUBMITTED)))
                .extracting(HolderBlockFreeze::getId).contains(row.getId());
        assertThat(freezes.findByHolderBlockId(block.getId())).hasSize(1);
        HolderBlockFreeze duplicate = new HolderBlockFreeze(block.getId(), dep.getId(), "0xabc");
        duplicate.transitionTo(HolderBlockFreeze.Status.FAILED, "duplicate");
        assertThatThrownBy(() -> freezes.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);

        UUID rowId = row.getId();
        String locked = transactions.execute(s -> freezes.findByIdForUpdate(rowId).orElseThrow().getWalletAddress());
        assertThat(locked).isEqualTo("0xabc");
    }

    @Test
    @DisplayName("recordOnChainFreezeTxHash stores the first hash only and never touches the block's status")
    void txHashIsWrittenOnceWithoutRewritingTheBlock() {
        HolderBlock block = block();
        UUID id = block.getId();

        // A concurrent lift committed before the sync writes its hash: the targeted UPDATE must not undo it.
        transactions.executeWithoutResult(s -> {
            HolderBlock live = blocks.findById(id).orElseThrow();
            live.setStatus(HolderBlock.Status.LIFTED);
            blocks.saveAndFlush(live);
        });

        int first = transactions.execute(s -> blocks.recordOnChainFreezeTxHash(id, "0xfirst"));
        int second = transactions.execute(s -> blocks.recordOnChainFreezeTxHash(id, "0xsecond"));

        HolderBlock reloaded = blocks.findById(id).orElseThrow();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(reloaded.getOnChainFreezeTxHash()).isEqualTo("0xfirst");
        assertThat(reloaded.getStatus()).isEqualTo(HolderBlock.Status.LIFTED);
    }
}
