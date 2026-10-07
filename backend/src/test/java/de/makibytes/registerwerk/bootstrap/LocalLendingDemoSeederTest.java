package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketRegistrar;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review phase 2 veto N2: the local on-chain demo moves the Meridian Green Bond register rows onto
 * the funded Anvil wallets, but left the seeded transfer history on the synthetic wallets — the
 * holder sync then found finalized balances on unmapped wallets and blocked the asset (and its
 * corporate actions). The seeded transfers must follow the register.
 */
@DisplayName("LocalLendingDemoSeeder")
class LocalLendingDemoSeederTest {

    static final String ZERO = "0x0000000000000000000000000000000000000000";

    @Test
    @DisplayName("re-points the seeded Green Bond transfers onto the entities' Anvil wallets")
    void remapsSeededTransferWallets() {
        TokenTransferRepository transfers = mock(TokenTransferRepository.class);
        LocalLendingDemoSeeder seeder = new LocalLendingDemoSeeder(
                mock(AssetRepository.class), mock(AssetDeploymentRepository.class), mock(AssetHolderRepository.class),
                mock(LegalEntityRepository.class), mock(ChainConfigRepository.class), mock(RpcNodeRepository.class),
                mock(BlockchainClientRegistry.class), mock(OrgRegistrationRepository.class),
                mock(OrgMemberWalletRepository.class), mock(LendingMarketRepository.class),
                mock(LendingMarketRegistrar.class), mock(ContractAddressConfig.class), transfers,
                mock(de.makibytes.registerwerk.payment.api.PaymentRailRepository.class),
                mock(de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        Asset greenBond = new Asset();
        greenBond.setId(UUID.randomUUID());
        // Exactly what DemoDataSeeder writes (mixed case), plus an unrelated third-party row.
        TokenTransfer mintNordbank = transfer(ZERO, "0x1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B", "5500");
        TokenTransfer mintRhein = transfer(ZERO, "0x2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C", "3000");
        TokenTransfer mintAurora = transfer(ZERO, "0x3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D", "1500");
        TokenTransfer nordbankToAurora = transfer("0x1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B",
                "0x3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D", "500");
        TokenTransfer unrelated = transfer(ZERO, "0x00000000000000000000000000000000000000ee", "1");
        when(transfers.findByAssetIdOrderByOccurredAtDesc(eq(greenBond.getId()), any()))
                .thenReturn(new PageImpl<>(List.of(mintNordbank, mintRhein, mintAurora, nordbankToAurora, unrelated)));

        seeder.remapSeededTransferWallets(greenBond);

        // The same Anvil wallets LocalLendingDemoSeeder puts on the register rows (COMPANY_WALLETS).
        String nordbank = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
        String rhein = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";
        String aurora = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";
        assertThat(mintNordbank.getToAddress()).isEqualTo(nordbank);
        assertThat(mintRhein.getToAddress()).isEqualTo(rhein);
        assertThat(mintAurora.getToAddress()).isEqualTo(aurora);
        assertThat(nordbankToAurora.getFromAddress()).isEqualTo(nordbank);
        assertThat(nordbankToAurora.getToAddress()).isEqualTo(aurora);
        assertThat(mintNordbank.getFromAddress()).isEqualTo(ZERO);
        assertThat(unrelated.getToAddress()).isEqualTo("0x00000000000000000000000000000000000000ee");
        verify(transfers, times(4)).save(any(TokenTransfer.class));
    }

    private static TokenTransfer transfer(String from, String to, String amount) {
        TokenTransfer t = new TokenTransfer();
        t.setFromAddress(from);
        t.setToAddress(to);
        t.setAmount(new BigDecimal(amount));
        return t;
    }
}
