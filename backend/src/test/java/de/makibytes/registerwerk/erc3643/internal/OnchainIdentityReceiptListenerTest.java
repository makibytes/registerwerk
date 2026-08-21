package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@code OnchainIdentityReceiptListener} — it used to resolve a
 * pending ONCHAINID identity's real address on the first mined receipt, regardless of the
 * chain's confirmation depth or finality model. It now consults the same
 * {@code ChainConfig.FinalityModel}-aware decision every other confirmation-gated path in the
 * registry uses, so a reorg cannot leave an identity resolved to an address the chain has since
 * abandoned.
 */
@ExtendWith(MockitoExtension.class)
class OnchainIdentityReceiptListenerTest {

    @Mock private OnchainIdentityRepository identityRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private de.makibytes.registerwerk.finality.api.ChainEffectRecorder chainEffectRecorder;

    private BlockchainTxProperties txProperties;
    private OnchainIdentityReceiptListener listener;

    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        txProperties = new BlockchainTxProperties();
        txProperties.setDefaultConfirmations(12);
        EvmFinalityResolver finalityResolver = new EvmFinalityResolver(chainConfigRepository, txProperties);
        listener = new OnchainIdentityReceiptListener(
                identityRepository, chainConfigRepository, clientRegistry, finalityResolver, chainEffectRecorder);
    }

    private ChainConfig chain(ChainConfig.FinalityModel model) {
        ChainConfig c = new ChainConfig();
        c.setId(chainConfigId);
        c.setIdentifier("ETHEREUM_MAINNET");
        c.setFinalityModel(model);
        return c;
    }

    private OnchainIdentity pendingIdentity(String txHash) {
        OnchainIdentity identity = new OnchainIdentity();
        identity.setChainConfigId(chainConfigId);
        identity.setIdentityAddress("0x-PENDING-" + txHash);
        identity.setDeployedByTx(txHash);
        return identity;
    }

    private Web3j web3jWithReceipt(TransactionReceipt receipt) throws Exception {
        Web3j web3j = mock(Web3j.class, Answers.RETURNS_DEEP_STUBS);
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).thenReturn(web3j);
        EthGetTransactionReceipt response = mock(EthGetTransactionReceipt.class);
        when(response.getTransactionReceipt()).thenReturn(Optional.of(receipt));
        when(web3j.ethGetTransactionReceipt(any()).send()).thenReturn(response);
        return web3j;
    }

    private static TransactionReceipt minedReceipt(String status, long blockNumber) {
        TransactionReceipt r = new TransactionReceipt();
        r.setStatus(status);
        r.setBlockNumber(Numeric.encodeQuantity(BigInteger.valueOf(blockNumber)));
        r.setContractAddress("0xrealaddress");
        return r;
    }

    @Test
    @DisplayName("DEPTH_BASED: mined but below confirmation depth leaves the identity PENDING")
    void resolvePendingIdentities_depthBased_belowDepth_staysPending() throws Exception {
        OnchainIdentity identity = pendingIdentity("0xtx1");
        when(identityRepository.findByIdentityAddressStartingWithAndDeployedByTxIsNotNull("0x-PENDING-"))
                .thenReturn(List.of(identity));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(105)); // depth=6 < 12

        listener.resolvePendingIdentities();

        assertThat(identity.getIdentityAddress()).isEqualTo("0x-PENDING-0xtx1");
        verify(identityRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEPTH_BASED: mined at/past confirmation depth resolves the real address")
    void resolvePendingIdentities_depthBased_atDepth_resolvesAddress() throws Exception {
        OnchainIdentity identity = pendingIdentity("0xtx1");
        when(identityRepository.findByIdentityAddressStartingWithAndDeployedByTxIsNotNull("0x-PENDING-"))
                .thenReturn(List.of(identity));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(111)); // depth=12

        listener.resolvePendingIdentities();

        assertThat(identity.getIdentityAddress()).isEqualTo("0xrealaddress");
        verify(identityRepository).save(identity);
    }

    @Test
    @DisplayName("TAG_BASED: mined but below the node's finalized tag stays PENDING, "
            + "even past the depth threshold")
    void resolvePendingIdentities_tagBased_belowFinalizedTag_staysPending() throws Exception {
        OnchainIdentity identity = pendingIdentity("0xtx1");
        when(identityRepository.findByIdentityAddressStartingWithAndDeployedByTxIsNotNull("0x-PENDING-"))
                .thenReturn(List.of(identity));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.TAG_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.TAG_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));

        EthBlock finalizedResponse = mock(EthBlock.class, Answers.RETURNS_DEEP_STUBS);
        when(finalizedResponse.hasError()).thenReturn(false);
        when(finalizedResponse.getBlock().getNumber()).thenReturn(BigInteger.valueOf(90)); // < 100
        when(web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send())
                .thenReturn(finalizedResponse);

        listener.resolvePendingIdentities();

        assertThat(identity.getIdentityAddress()).isEqualTo("0x-PENDING-0xtx1");
        verify(identityRepository, never()).save(any());
        verify(web3j, never()).ethBlockNumber();
    }

    @Test
    @DisplayName("INSTANT: first receipt resolves the address immediately, no depth or tag lookup")
    void resolvePendingIdentities_instant_resolvesImmediately() throws Exception {
        OnchainIdentity identity = pendingIdentity("0xtx1");
        when(identityRepository.findByIdentityAddressStartingWithAndDeployedByTxIsNotNull("0x-PENDING-"))
                .thenReturn(List.of(identity));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));

        listener.resolvePendingIdentities();

        assertThat(identity.getIdentityAddress()).isEqualTo("0xrealaddress");
        verify(identityRepository).save(identity);
        verify(web3j, never()).ethBlockNumber();
        verify(web3j, never()).ethGetBlockByNumber(any(), anyBoolean());
    }

    @Test
    @DisplayName("reverted on-chain marks FAILED regardless of depth, without consulting finality")
    void resolvePendingIdentities_reverted_marksFailed() throws Exception {
        OnchainIdentity identity = pendingIdentity("0xtx1");
        when(identityRepository.findByIdentityAddressStartingWithAndDeployedByTxIsNotNull("0x-PENDING-"))
                .thenReturn(List.of(identity));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        web3jWithReceipt(minedReceipt("0x0", 100));

        listener.resolvePendingIdentities();

        assertThat(identity.getIdentityAddress()).isEqualTo("0x-FAILED-0xtx1");
        verify(identityRepository).save(identity);
    }
}
