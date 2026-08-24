package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class HolderDataServiceTransactionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @MockitoBean AssetDeploymentRepository deploymentRepository;
    @MockitoBean TokenTransferRepository tokenTransferRepository;
    @MockitoBean AssetHolderRepository assetHolderRepository;

    @Autowired HolderDataService holderDataService;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void expectedUnmappedIdentityFailureDoesNotPoisonOuterCompensationTransaction() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        TokenTransfer transfer = new TokenTransfer();
        transfer.setDeploymentId(deploymentId);
        transfer.setFromAddress("0x0000000000000000000000000000000000000000");
        transfer.setToAddress("0xUnmapped");
        transfer.setAmount(BigDecimal.ONE);
        transfer.setOccurredAt(Instant.now());
        transfer.setFinalityStatus(FinalityLevel.FINALIZED);

        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment));
        when(tokenTransferRepository.findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(
                eq(deploymentId), eq(FinalityLevel.FINALIZED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transfer)));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Boolean rollbackOnly = new TransactionTemplate(transactionManager).execute(status -> {
            assertThatThrownBy(() -> holderDataService.syncHoldersFromBlockchain(assetId))
                    .isInstanceOf(UnmappedHolderIdentityException.class);
            return status.isRollbackOnly();
        });

        assertThat(rollbackOnly).isFalse();
    }
}
