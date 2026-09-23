package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T1-17: factory deployments adopt our own contract at {@code predictAddress} instead of failing. */
class EvmFactoryDeploymentSupportTest {

    private static final String FACTORY = "0x00000000000000000000000000000000000000fa";
    private static final String PREDICTED = "0x00000000000000000000000000000000000000cc";
    private static final String REGISTRY = "0x00000000000000000000000000000000000000aa";
    private static final String CREATION_TX = "0x" + "11".repeat(32);
    private static final ChainDescriptor CHAIN = new ChainDescriptor(Chain.ETHEREUM, Network.TESTNET);
    private static final UUID ASSET_ID = UUID.fromString("7f1c2a4e-0b7d-4a51-9e0c-2f4d8b6a1c33");

    private EvmContractService evmContractService;
    private Web3j web3j;
    private EvmSigner signer;
    private EvmFactoryDeploymentSupport support;

    private String onChainRegistry = REGISTRY;
    private String code = "0x";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        evmContractService = mock(EvmContractService.class);
        web3j = mock(Web3j.class);
        signer = mock(EvmSigner.class);
        when(signer.address()).thenReturn(REGISTRY);
        support = new EvmFactoryDeploymentSupport(evmContractService);

        when(evmContractService.call(eq(web3j), any(String.class), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            return switch (fn.getName()) {
                case "predictAddress" -> List.<Type>of(new Address(PREDICTED));
                case "assetId" -> List.<Type>of(new Bytes32(EvmUtils.uuidToBytes32(ASSET_ID)));
                case "registry" -> List.<Type>of(new Address(onChainRegistry));
                default -> throw new IllegalArgumentException(fn.getName());
            };
        });

        Request<?, EthGetCode> codeRequest = mock(Request.class);
        when(codeRequest.send()).thenAnswer(inv -> {
            EthGetCode r = new EthGetCode();
            r.setResult(code);
            return r;
        });
        doReturn(codeRequest).when(web3j).ethGetCode(eq(PREDICTED), any());

        Log creation = new Log();
        creation.setTransactionHash(CREATION_TX);
        creation.setTopics(List.of(EvmFactoryDeploymentSupport.TOKEN_DEPLOYED_TOPIC,
                Numeric.toHexString(EvmUtils.uuidToBytes32(ASSET_ID)),
                Numeric.toHexStringWithPrefixZeroPadded(BigInteger.ZERO, 64),
                "0x000000000000000000000000" + Numeric.cleanHexPrefix(PREDICTED)));
        EthLog.LogObject logObject = new EthLog.LogObject();
        logObject.setTransactionHash(creation.getTransactionHash());
        logObject.setTopics(creation.getTopics());
        EthLog ethLog = new EthLog();
        ethLog.setResult(List.of(logObject));
        Request<?, EthLog> logsRequest = mock(Request.class);
        when(logsRequest.send()).thenReturn(ethLog);
        doReturn(logsRequest).when(web3j).ethGetLogs(any());
    }

    private TokenDeploymentResult deploy() {
        return support.deploy(CHAIN, web3j, signer, FACTORY, BigInteger.ZERO, "Bond", "BND", ASSET_ID, null);
    }

    @Test
    void existingMatchingContractIsAdoptedWithoutSending() {
        code = "0x6080";

        TokenDeploymentResult result = deploy();

        assertThat(result.contractAddress()).isEqualTo(PREDICTED);
        assertThat(result.txHash()).isEqualTo(CREATION_TX);
        verify(evmContractService, never()).send(any(UUID.class), any(), any(), any(), any(Function.class));
    }

    @Test
    void existingContractWithForeignRegistryFailsLoudlyWithoutSending() {
        code = "0x6080";
        onChainRegistry = "0x00000000000000000000000000000000000000bb";

        assertThatThrownBy(this::deploy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREDICTED)
                .hasMessageContaining("not ours");
        verify(evmContractService, never()).send(any(UUID.class), any(), any(), any(), any(Function.class));
    }

    @Test
    void emptySlotSendsAndReadsAddressFromReceipt() {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash("0x" + "22".repeat(32));
        Log event = new Log();
        event.setTopics(List.of(EvmFactoryDeploymentSupport.TOKEN_DEPLOYED_TOPIC,
                Numeric.toHexString(EvmUtils.uuidToBytes32(ASSET_ID)),
                Numeric.toHexStringWithPrefixZeroPadded(BigInteger.ZERO, 64),
                "0x000000000000000000000000" + Numeric.cleanHexPrefix(PREDICTED)));
        receipt.setLogs(List.of(event));
        when(evmContractService.send(any(), eq(web3j), eq(signer), eq(FACTORY), any(Function.class)))
                .thenReturn(receipt);

        TokenDeploymentResult result = deploy();

        assertThat(result.contractAddress()).isEqualTo(PREDICTED);
        assertThat(result.txHash()).isEqualTo(receipt.getTransactionHash());
    }

    @Test
    void revertAfterFrontRunAdoptsTheSquattedContract() {
        when(evmContractService.send(any(), eq(web3j), eq(signer), eq(FACTORY), any(Function.class)))
                .thenAnswer(inv -> {
                    code = "0x6080"; // front-runner's identical tx landed first
                    throw new RuntimeException("Transaction reverted on-chain: CREATE2 failed");
                });

        TokenDeploymentResult result = deploy();

        assertThat(result.contractAddress()).isEqualTo(PREDICTED);
        assertThat(result.txHash()).isEqualTo(CREATION_TX);
    }

    @Test
    void revertWithEmptySlotRethrowsOriginalFailure() {
        RuntimeException failure = new RuntimeException("Transaction reverted on-chain: out of gas");
        when(evmContractService.send(any(), eq(web3j), eq(signer), eq(FACTORY), any(Function.class)))
                .thenThrow(failure);

        assertThatThrownBy(this::deploy).isSameAs(failure);
    }
}
