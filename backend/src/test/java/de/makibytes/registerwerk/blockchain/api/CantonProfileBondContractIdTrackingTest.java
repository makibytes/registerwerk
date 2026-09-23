package de.makibytes.registerwerk.blockchain.api;

import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import de.makibytes.registerwerk.blockchain.events.CantonBondContractIdRepairedEvent;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.ChainSubmissionExecutor;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.WalletStorage.CantonContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1-10: every Registerwerk bond lifecycle choice is consuming, so {@code PayCoupon} /
 * {@code FixRate} archive the stored contract and create a successor. These tests pin that the
 * backend follows the successor id (including across queued submissions) and self-heals a
 * stale id from the active contract set.
 */
class CantonProfileBondContractIdTrackingTest {

    private final UUID deploymentId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    private final CantonLedgerClient client = mock(CantonLedgerClient.class);
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CountDownLatch gate = new CountDownLatch(1);
    private final Object serial = new Object();

    /** The persisted contract id — findById hands out a fresh detached copy each time, as JPA would. */
    private volatile String storedCid = "C0";
    private final List<String> exercisedCids = new CopyOnWriteArrayList<>();
    private final AtomicInteger created = new AtomicInteger();

    private CantonBondService service;

    @BeforeEach
    void setUp() {
        BlockchainClientRegistry registry = mock(BlockchainClientRegistry.class);
        when(registry.getCantonClientByIdentifier("CANTON_DEVNET")).thenReturn(client);

        ChainConfig chain = new ChainConfig();
        chain.setId(chainConfigId);
        chain.setIdentifier("CANTON_DEVNET");
        chain.setEnabled(true);
        ChainConfigRepository chains = mock(ChainConfigRepository.class);
        when(chains.findById(chainConfigId)).thenReturn(Optional.of(chain));
        when(chains.findByIdentifier("CANTON_DEVNET")).thenReturn(Optional.of(chain));

        WalletSigner signer = mock(WalletSigner.class);
        when(signer.cantonContextForChain(chainConfigId)).thenReturn(new CantonContext("Registry::1220", "jwt"));

        AssetLookupPort assets = mock(AssetLookupPort.class);
        when(assets.findById(assetId)).thenReturn(Optional.of(new AssetLookupPort.AssetInfo(
                assetId, "Bond", null, TokenStandard.DAML_BOND_FIXED, "CANTON", "DEVNET",
                null, null, "ACTIVE")));

        when(deployments.findById(deploymentId)).thenAnswer(inv -> Optional.of(copyOfStored()));
        when(deployments.save(any(AssetDeployment.class))).thenAnswer(inv -> {
            AssetDeployment saved = inv.getArgument(0);
            storedCid = saved.getContractAddress();
            return saved;
        });

        // Per-chain serialization like the real executor; held closed until the test opens the gate.
        ChainSubmissionExecutor submissions = new ChainSubmissionExecutor() {
            @Override
            public <T> T execute(UUID id, Supplier<T> submission) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (serial) {
                    return submission.get();
                }
            }
        };

        when(client.submitAndWaitForCreatedContract(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<Command> cmds = inv.getArgument(1);
            String cid = ((ExerciseCommand) cmds.getFirst()).getContractId();
            exercisedCids.add(cid);
            if (!cid.equals(liveCid())) {
                throw new IllegalStateException("NOT_FOUND: CONTRACT_NOT_FOUND(11,abc): Contract could not be found");
            }
            int n = created.incrementAndGet();
            return new CantonLedgerClient.CommittedContract("upd-" + n, "C" + n);
        });

        service = new CantonBondService(registry, deployments, assets, mock(AssetBondTermsRepository.class),
                events, chains, signer, submissions);
    }

    /** The ledger's single active contract: C0 until the first recreating choice, then C{n}. */
    private String liveCid() {
        return "C" + created.get();
    }

    private AssetDeployment copyOfStored() {
        AssetDeployment dep = new AssetDeployment();
        ReflectionTestUtils.setField(dep, "id", deploymentId);
        dep.setAssetId(assetId);
        dep.setChain(Chain.CANTON);
        dep.setNetwork(Network.TESTNET);
        dep.setChainConfigId(chainConfigId);
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setContractAddress(storedCid);
        return dep;
    }

    @Test
    void secondCouponTargetsTheContractCreatedByTheFirst_evenWhenQueuedBeforeItRan() {
        CompletableFuture<String> first = service.payCoupon(
                deploymentId, Instant.parse("2027-06-30T00:00:00Z"), new BigDecimal("2.10"), null);
        CompletableFuture<String> second = service.payCoupon(
                deploymentId, Instant.parse("2028-06-30T00:00:00Z"), new BigDecimal("2.10"), null);
        gate.countDown();

        // Both were enqueued before either ran; which one wins the chain lock is not specified.
        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder("upd-1", "upd-2");
        assertThat(exercisedCids).containsExactly("C0", "C1");
        assertThat(storedCid).isEqualTo("C2");
        verify(client, never()).findActiveContract(anyString(), any(), anyString());
    }

    @Test
    void staleStoredIdIsRepairedFromTheActiveContractSetAndRetriedOnce() {
        gate.countDown();
        created.set(3);                 // ledger moved on to C3 while the DB still says C0
        when(client.findActiveContract(eq("Registry::1220"), any(), eq(assetId.toString())))
                .thenReturn(Optional.of("C3"));

        String updateId = service.payCoupon(
                deploymentId, Instant.parse("2027-06-30T00:00:00Z"), new BigDecimal("2.10"), null).join();

        assertThat(updateId).isEqualTo("upd-4");
        assertThat(exercisedCids).containsExactly("C0", "C3");
        assertThat(storedCid).isEqualTo("C4");
        verify(events).publishEvent(new CantonBondContractIdRepairedEvent(
                deploymentId, "C0", "C3", "CONTRACT_NOT_FOUND"));
    }

    @Test
    void startupReconciliationBackfillsAStaleStoredId() {
        gate.countDown();
        when(deployments.findByChainAndNetwork(eq(Chain.CANTON), any())).thenReturn(List.of());
        when(client.findActiveContract(eq("Registry::1220"), any(), eq(assetId.toString())))
                .thenReturn(Optional.of("C7"));

        Optional<String> live = service.reconcileContractId(deploymentId).join();

        assertThat(live).contains("C7");
        assertThat(storedCid).isEqualTo("C7");
        verify(events).publishEvent(new CantonBondContractIdRepairedEvent(deploymentId, "C0", "C7", "STARTUP"));
    }

    @Test
    void terminalRedeemKeepsTheStoredId() {
        gate.countDown();
        when(client.submitAndWait(anyString(), anyList())).thenReturn("upd-redeem");

        assertThat(service.redeem(deploymentId, Instant.parse("2033-06-30T00:00:00Z"), null).join())
                .isEqualTo("upd-redeem");
        assertThat(storedCid).isEqualTo("C0");
        verify(deployments, never()).save(any());
    }
}
