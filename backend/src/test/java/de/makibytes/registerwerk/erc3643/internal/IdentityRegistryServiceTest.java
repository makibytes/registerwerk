package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.events.InvestorRemovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint16;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@code IdentityRegistryService.removeInvestor} — it used to call the
 * blocking {@code EvmContractService.send()}/{@code waitForReceipt()} and treat the first mined
 * receipt as final, with no reorg guard and (unlike {@link #registerInvestor}) no
 * {@link BlockchainTransactionService} tracking at all. It now submits non-blocking and records
 * the tx the same way {@code registerInvestor} does, so a reorg on the {@code deleteIdentity}
 * transaction is at least visible to the tracked, model-aware poller instead of invisible.
 */
@ExtendWith(MockitoExtension.class)
class IdentityRegistryServiceTest {

    @Mock private Erc3643IdentityRegistryRepository registryRepo;
    @Mock private Erc3643SuiteRepository suiteRepo;
    @Mock private Erc3643ClaimTopicRepository claimTopicRepo;
    @Mock private AssetDeploymentRepository deploymentRepo;
    @Mock private OnChainIdService onChainIdService;
    @Mock private DurableEvmTransactionGateway evmTransactions;
    @Mock private BlockchainTransactionService blockchainTransactionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private LegalEntityRepository legalEntityRepo;

    private IdentityRegistryService service;

    private final UUID suiteId = UUID.randomUUID();
    private final UUID registryEntryId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IdentityRegistryService(registryRepo, suiteRepo, claimTopicRepo, deploymentRepo,
                onChainIdService, evmTransactions,
                blockchainTransactionService, eventPublisher, legalEntityRepo);
    }

    private Erc3643IdentityRegistry entry() {
        Erc3643IdentityRegistry e = new Erc3643IdentityRegistry();
        ReflectionTestUtils.setField(e, "id", registryEntryId);
        e.setSuiteId(suiteId);
        e.setWalletAddress("0x1234567890123456789012345678901234567890");
        e.setChainConfigId(chainConfigId);
        return e;
    }

    private Erc3643Suite deployedSuite() {
        Erc3643Suite suite = new Erc3643Suite();
        ReflectionTestUtils.setField(suite, "id", suiteId);
        suite.setAssetDeploymentId(deploymentId);
        suite.setIdentityRegistryAddress("0xregistry");
        return suite;
    }

    private AssetDeployment deployment() {
        AssetDeployment d = new AssetDeployment();
        d.setId(deploymentId);
        d.setAssetId(assetId);
        d.setChainConfigId(chainConfigId);
        d.setChain(Chain.ETHEREUM);
        d.setNetwork(Network.MAINNET);
        return d;
    }

    @Test
    @DisplayName("submits deleteIdentity non-blocking and records it with BlockchainTransactionService "
            + "— not the old blocking send()/waitForReceipt()")
    void removeInvestor_submitsNonBlockingAndTracksTx() {
        when(registryRepo.findById(registryEntryId)).thenReturn(Optional.of(entry()));
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));
        when(deploymentRepo.findById(deploymentId)).thenReturn(Optional.of(deployment()));
        when(evmTransactions.submit(eq(chainConfigId), eq("0xregistry"),
                any(Function.class), any())).thenReturn("0xdeletetx");

        UUID actorId = UUID.randomUUID();
        service.removeInvestor(suiteId, registryEntryId, actorId, "REGISTRY_ADMIN");

        verify(evmTransactions).submit(eq(chainConfigId), eq("0xregistry"),
                any(Function.class), any());
        verify(blockchainTransactionService).record(
                eq("0xdeletetx"), eq("deleteIdentity"), eq(deploymentId), eq(assetId),
                eq("ETHEREUM"), eq("MAINNET"), eq("0xregistry"),
                eq(Map.of("walletAddress", "0x1234567890123456789012345678901234567890")));
    }

    @Test
    @DisplayName("soft-deletes the entry and publishes InvestorRemovedEvent")
    void removeInvestor_softDeletesAndPublishesEvent() {
        Erc3643IdentityRegistry entry = entry();
        when(registryRepo.findById(registryEntryId)).thenReturn(Optional.of(entry));
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));
        when(deploymentRepo.findById(deploymentId)).thenReturn(Optional.of(deployment()));
        when(evmTransactions.submit(any(UUID.class), anyString(), any(Function.class), any()))
                .thenReturn("0xdeletetx");

        service.removeInvestor(suiteId, registryEntryId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(entry.getRemovedAt()).isNotNull();
        // The reorg-compensation gap this closes: without recording which tx did the removing,
        // Erc3643IdentityRegistryConfirmationListener has nothing to reconcile against.
        assertThat(entry.getRemovedByTx()).isEqualTo("0xdeletetx");
        verify(registryRepo).save(entry);
        verify(eventPublisher).publishEvent(any(InvestorRemovedEvent.class));
    }

    @Test
    @DisplayName("resets removalConfirmed so a re-removal (e.g. after IdentityRegistryRemovalRevertCompensator "
            + "restored the entry post-reorg) is re-polled by the confirmation listener")
    void removeInvestor_resetsRemovalConfirmedForARepeatRemoval() {
        Erc3643IdentityRegistry entry = entry();
        entry.setRemovalConfirmed(true); // left over from a first removal that was later reorg-reverted
        when(registryRepo.findById(registryEntryId)).thenReturn(Optional.of(entry));
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));
        when(deploymentRepo.findById(deploymentId)).thenReturn(Optional.of(deployment()));
        when(evmTransactions.submit(any(UUID.class), anyString(), any(Function.class), any()))
                .thenReturn("0xdeletetx2");

        service.removeInvestor(suiteId, registryEntryId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(entry.isRemovalConfirmed()).isFalse();
    }

    @Test
    @DisplayName("a submission failure propagates and does not soft-delete the entry")
    void removeInvestor_submissionFails_doesNotSoftDelete() {
        Erc3643IdentityRegistry entry = entry();
        when(registryRepo.findById(registryEntryId)).thenReturn(Optional.of(entry));
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));
        when(deploymentRepo.findById(deploymentId)).thenReturn(Optional.of(deployment()));
        when(evmTransactions.submit(any(UUID.class), anyString(), any(Function.class), any()))
                .thenThrow(new RuntimeException("RPC down"));

        assertThatThrownBy(() -> service.removeInvestor(suiteId, registryEntryId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(RuntimeException.class);

        assertThat(entry.getRemovedAt()).isNull();
        verify(registryRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("suite with no on-chain identity registry yet (still PENDING) soft-deletes without "
            + "attempting an on-chain call")
    void removeInvestor_pendingSuite_skipsOnchainCall() {
        Erc3643IdentityRegistry entry = entry();
        Erc3643Suite pendingSuite = deployedSuite();
        pendingSuite.setIdentityRegistryAddress("0x-PENDING-suite");
        when(registryRepo.findById(registryEntryId)).thenReturn(Optional.of(entry));
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(pendingSuite));

        service.removeInvestor(suiteId, registryEntryId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(entry.getRemovedAt()).isNotNull();
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
        verify(blockchainTransactionService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── registerInvestor: country is mandatory (T1-13) ─────────────────────

    private static final String WALLET = "0x1234567890123456789012345678901234567890";

    private void stubOnchainIdentity(UUID legalEntityId) {
        OnchainIdentity identity = new OnchainIdentity();
        ReflectionTestUtils.setField(identity, "id", UUID.randomUUID());
        identity.setIdentityAddress("0x00000000000000000000000000000000000000aa");
        when(onChainIdService.getOrCreate(eq(legalEntityId), eq(chainConfigId), any(), any())).thenReturn(identity);
    }

    private LegalEntity legalEntity(UUID id, String registrationCountry) {
        LegalEntity e = new LegalEntity();
        ReflectionTestUtils.setField(e, "id", id);
        e.setRegistrationCountry(registrationCountry);
        return e;
    }

    @Test
    @DisplayName("registerInvestor defaults a missing country to the entity's KYC registration country (DE -> 276)")
    void registerInvestor_defaultsCountryFromKyc() {
        UUID legalEntityId = UUID.randomUUID();
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));
        when(deploymentRepo.findById(deploymentId)).thenReturn(Optional.of(deployment()));
        when(legalEntityRepo.findById(legalEntityId)).thenReturn(Optional.of(legalEntity(legalEntityId, "DE")));
        stubOnchainIdentity(legalEntityId);
        when(evmTransactions.submit(eq(chainConfigId), eq("0xregistry"), any(Function.class), any()))
                .thenReturn("0xregistertx");
        when(registryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Erc3643IdentityRegistry saved = service.registerInvestor(
                suiteId, WALLET, legalEntityId, chainConfigId, null, UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(chainConfigId), eq("0xregistry"), fn.capture(), any());
        assertThat(((Uint16) fn.getValue().getInputParameters().get(2)).getValue().intValue()).isEqualTo(276);
        assertThat(saved.getCountryCode()).isEqualTo((short) 276);
    }

    @Test
    @DisplayName("registerInvestor refuses when no country is given and KYC has none — never registers country 0")
    void registerInvestor_refusesUnknownCountry() {
        UUID legalEntityId = UUID.randomUUID();
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));
        when(legalEntityRepo.findById(legalEntityId)).thenReturn(Optional.of(legalEntity(legalEntityId, null)));

        assertThatThrownBy(() -> service.registerInvestor(
                suiteId, WALLET, legalEntityId, chainConfigId, null, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countryCode");
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
        verify(registryRepo, never()).save(any());
    }

    @Test
    @DisplayName("registerInvestor refuses an explicit country 0")
    void registerInvestor_refusesCountryZero() {
        when(suiteRepo.findById(suiteId)).thenReturn(Optional.of(deployedSuite()));

        assertThatThrownBy(() -> service.registerInvestor(
                suiteId, WALLET, UUID.randomUUID(), chainConfigId, (short) 0, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
    }
}
