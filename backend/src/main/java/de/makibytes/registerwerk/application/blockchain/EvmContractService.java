package de.makibytes.registerwerk.application.blockchain;

import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.chain.ChainConfig;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.ChainConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared infrastructure for EVM smart-contract interactions.
 *
 * <p>Handles credentials, gas pricing, nonce retrieval, raw-transaction building,
 * receipt polling, and ABI-encoded function calls / deployments.
 *
 * <p>All signing uses the registry operator wallet configured via
 * {@code registerwerk.wallet.private-key}.
 */
@Service
public class EvmContractService {

    private static final Logger log = LoggerFactory.getLogger(EvmContractService.class);

    /** Gas limit used for regular contract write calls. */
    private static final BigInteger CALL_GAS_LIMIT = BigInteger.valueOf(500_000L);

    /** Gas limit used for contract deployments (higher budget needed). */
    private static final BigInteger DEPLOY_GAS_LIMIT = BigInteger.valueOf(5_000_000L);

    /** How many 2-second polls to attempt before timing out. */
    private static final int RECEIPT_POLL_ATTEMPTS = 60;

    @Value("${registerwerk.wallet.private-key:}")
    private String privateKeyHex;

    private final BlockchainClientRegistry clientRegistry;
    private final ChainConfigRepository chainConfigRepository;

    public EvmContractService(BlockchainClientRegistry clientRegistry,
                               ChainConfigRepository chainConfigRepository) {
        this.clientRegistry = clientRegistry;
        this.chainConfigRepository = chainConfigRepository;
    }

    // ── Credential helpers ────────────────────────────────────────────────────

    /**
     * Returns {@link Credentials} for the registry operator wallet.
     *
     * @throws IllegalStateException if the private key is not configured
     */
    public Credentials credentials() {
        if (privateKeyHex == null || privateKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "registerwerk.wallet.private-key is not configured; cannot sign transactions");
        }
        return Credentials.create(privateKeyHex);
    }

    // ── Client helpers ────────────────────────────────────────────────────────

    /**
     * Returns the Web3j client for the given chain config ID.
     */
    public Web3j evmClient(UUID chainConfigId) {
        ChainConfig config = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        return clientRegistry.getEvmClientByIdentifier(config.getIdentifier());
    }

    /**
     * Returns the chain ID for the given chain config.
     */
    public long chainId(UUID chainConfigId) {
        ChainConfig config = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        return config.getChainId() != null ? config.getChainId() : 1L;
    }

    // ── Transaction sending ───────────────────────────────────────────────────

    /**
     * Encodes {@code function}, sends a signed raw transaction to {@code contractAddress},
     * and waits for the receipt.
     *
     * @return mined {@link TransactionReceipt}
     * @throws RuntimeException wrapping any IO or timeout error
     */
    public TransactionReceipt send(Web3j web3j, Credentials creds, String contractAddress,
                                   Function function) {
        return send(web3j, creds, contractAddress, FunctionEncoder.encode(function), CALL_GAS_LIMIT);
    }

    /**
     * Sends a raw ABI-encoded call to {@code contractAddress} with a custom gas limit.
     */
    public TransactionReceipt send(Web3j web3j, Credentials creds, String contractAddress,
                                   String encodedData, BigInteger gasLimit) {
        try {
            BigInteger gasPrice = gasPrice(web3j);
            BigInteger nonce = nonce(web3j, creds.getAddress());

            RawTransaction tx = RawTransaction.createTransaction(
                    nonce, gasPrice, gasLimit, contractAddress, encodedData);

            byte[] signed = TransactionEncoder.signMessage(tx, creds);
            EthSendTransaction sent = web3j
                    .ethSendRawTransaction(Numeric.toHexString(signed))
                    .send();

            if (sent.hasError()) {
                throw new RuntimeException("Transaction failed: " + sent.getError().getMessage());
            }
            return waitForReceipt(web3j, sent.getTransactionHash());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("EVM transaction error: " + e.getMessage(), e);
        }
    }

    /**
     * Deploys a contract from raw creation bytecode + ABI-encoded constructor arguments.
     *
     * @param binary             hex-encoded contract bytecode (with or without "0x" prefix)
     * @param encodedConstructor ABI-encoded constructor arguments (may be null for no-arg)
     * @return address of the newly deployed contract
     */
    public String deploy(Web3j web3j, Credentials creds, String binary,
                         String encodedConstructor) {
        String data = Numeric.cleanHexPrefix(binary)
                + (encodedConstructor != null ? Numeric.cleanHexPrefix(encodedConstructor) : "");
        try {
            BigInteger gasPrice = gasPrice(web3j);
            BigInteger nonce = nonce(web3j, creds.getAddress());

            RawTransaction tx = RawTransaction.createContractTransaction(
                    nonce, gasPrice, DEPLOY_GAS_LIMIT, BigInteger.ZERO, data);

            byte[] signed = TransactionEncoder.signMessage(tx, creds);
            EthSendTransaction sent = web3j
                    .ethSendRawTransaction(Numeric.toHexString(signed))
                    .send();

            if (sent.hasError()) {
                throw new RuntimeException("Deploy failed: " + sent.getError().getMessage());
            }

            TransactionReceipt receipt = waitForReceipt(web3j, sent.getTransactionHash());
            String contractAddress = receipt.getContractAddress();
            if (contractAddress == null) {
                throw new RuntimeException(
                        "Receipt missing contractAddress for tx=" + receipt.getTransactionHash());
            }
            return contractAddress;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Contract deployment error: " + e.getMessage(), e);
        }
    }

    // ── Read calls ────────────────────────────────────────────────────────────

    /**
     * Executes an {@code eth_call} (read-only) and decodes the response into the expected
     * output types declared in {@code function}.
     *
     * @return decoded output values
     */
    public List<Type> call(Web3j web3j, String contractAddress, Function function) {
        try {
            String encoded = FunctionEncoder.encode(function);
            EthCall result = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, contractAddress, encoded),
                    DefaultBlockParameterName.LATEST).send();

            if (result.hasError()) {
                throw new RuntimeException("eth_call error: " + result.getError().getMessage());
            }
            return FunctionReturnDecoder.decode(result.getValue(), function.getOutputParameters());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("eth_call error: " + e.getMessage(), e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private BigInteger gasPrice(Web3j web3j) throws Exception {
        EthGasPrice gp = web3j.ethGasPrice().send();
        if (gp.hasError()) {
            return BigInteger.valueOf(20_000_000_000L); // 20 Gwei fallback
        }
        // Add 20 % tip to land quickly
        return gp.getGasPrice().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
    }

    private BigInteger nonce(Web3j web3j, String address) throws Exception {
        EthGetTransactionCount cnt = web3j.ethGetTransactionCount(
                address, DefaultBlockParameterName.PENDING).send();
        return cnt.getTransactionCount();
    }

    /**
     * Polls until the transaction is mined or times out.
     */
    public TransactionReceipt waitForReceipt(Web3j web3j, String txHash) throws Exception {
        log.debug("Waiting for receipt of tx={}", txHash);
        for (int i = 0; i < RECEIPT_POLL_ATTEMPTS; i++) {
            Optional<TransactionReceipt> receipt =
                    web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) {
                log.debug("Receipt found after {} polls for tx={}", i + 1, txHash);
                return receipt.get();
            }
            Thread.sleep(2_000);
        }
        throw new RuntimeException(
                "Transaction not mined within " + (RECEIPT_POLL_ATTEMPTS * 2) + "s: " + txHash);
    }
}
