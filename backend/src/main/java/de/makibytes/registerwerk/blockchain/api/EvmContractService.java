package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.util.concurrent.ConcurrentHashMap;

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
 * <p>All signing uses the registry operator wallet resolved from the {@link WalletSigner}
 * for the relevant chain. Configure wallets via the Operator Portal → Wallets.
 */
@Service
public class EvmContractService {

    private static final Logger log = LoggerFactory.getLogger(EvmContractService.class);

    private static final BigInteger CALL_GAS_LIMIT   = BigInteger.valueOf(500_000L);
    private static final BigInteger DEPLOY_GAS_LIMIT = BigInteger.valueOf(5_000_000L);
    private static final int        RECEIPT_POLL_ATTEMPTS = 60;

    private final BlockchainClientRegistry clientRegistry;
    private final ChainConfigRepository    chainConfigRepository;
    private final WalletSigner             walletSigner;

    /** eth_chainId per Web3j client — chain clients are long-lived singletons. */
    private final ConcurrentHashMap<Web3j, Long> chainIdCache = new ConcurrentHashMap<>();

    /**
     * Per-sender submission locks: nonce fetch + sign + send must be atomic per wallet.
     *
     * <p><strong>Single-instance only.</strong> These locks live in this JVM. With more than
     * one backend replica submitting from the same operator wallet, two instances can read the
     * same pending nonce and one transaction silently replaces the other. Before scaling out,
     * serialise submissions per wallet across instances (a single submitter, an advisory DB
     * lock, or a shared nonce allocator). The login throttle has the same per-instance caveat.
     */
    private final ConcurrentHashMap<String, Object> senderLocks = new ConcurrentHashMap<>();

    public EvmContractService(BlockchainClientRegistry clientRegistry,
                               ChainConfigRepository chainConfigRepository,
                               WalletSigner walletSigner) {
        this.clientRegistry       = clientRegistry;
        this.chainConfigRepository = chainConfigRepository;
        this.walletSigner          = walletSigner;
    }

    // ── Credential helpers ────────────────────────────────────────────────────

    /** Returns credentials for the default wallet of the given chain config. */
    public EvmSigner signer(UUID chainConfigId) {
        return walletSigner.evmSignerForChain(chainConfigId);
    }

    /** Returns credentials for the default wallet of the given chain descriptor. */
    public EvmSigner signer(ChainDescriptor descriptor) {
        return walletSigner.evmSignerForDescriptor(descriptor);
    }

    /**
     * Returns credentials from any configured EVM default wallet.
     * Used for chain-agnostic operations and as a fallback.
     */
    public EvmSigner signer() {
        return walletSigner.evmSignerForAnyEvm();
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
     * Encodes {@code function}, submits a signed raw transaction to {@code contractAddress},
     * and returns the transaction hash immediately <em>without</em> waiting for a receipt.
     *
     * <p>Use this for fire-and-track admin operations. The caller should persist a
     * {@link de.makibytes.registerwerk.domain.blockchain.BlockchainTransaction} record and let
     * {@link de.makibytes.registerwerk.application.blockchain.BlockchainTransactionService}
     * poll for the receipt asynchronously.
     *
     * @return EVM transaction hash (0x-prefixed, 66 characters)
     */
    public String submit(Web3j web3j, EvmSigner signer, String contractAddress, Function function) {
        return submit(web3j, signer, contractAddress, FunctionEncoder.encode(function), CALL_GAS_LIMIT);
    }

    /**
     * Submits a raw ABI-encoded call to {@code contractAddress} and returns the transaction hash.
     */
    public String submit(Web3j web3j, EvmSigner signer, String contractAddress,
                         String encodedData, BigInteger gasLimit) {
        // Per-sender lock: two concurrent submissions would otherwise read the same
        // pending nonce and one transaction would silently replace the other.
        synchronized (senderLock(signer.address())) {
            try {
                BigInteger gasPrice = gasPrice(web3j);
                BigInteger nonce = nonce(web3j, signer.address());

                RawTransaction tx = RawTransaction.createTransaction(
                        nonce, gasPrice, gasLimit, contractAddress, encodedData);

                byte[] signed = signer.signTransaction(tx, resolveChainId(web3j));
                EthSendTransaction sent = web3j
                        .ethSendRawTransaction(Numeric.toHexString(signed))
                        .send();

                if (sent.hasError()) {
                    throw new RuntimeException("Transaction submission error: " + sent.getError().getMessage());
                }
                return sent.getTransactionHash();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("EVM transaction submit error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Encodes {@code function}, sends a signed raw transaction to {@code contractAddress},
     * and waits for the receipt.
     *
     * @return mined {@link TransactionReceipt}
     * @throws RuntimeException wrapping any IO or timeout error
     */
    public TransactionReceipt send(Web3j web3j, EvmSigner signer, String contractAddress,
                                   Function function) {
        return send(web3j, signer, contractAddress, FunctionEncoder.encode(function), CALL_GAS_LIMIT);
    }

    /**
     * Sends a raw ABI-encoded call to {@code contractAddress} with a custom gas limit.
     */
    public TransactionReceipt send(Web3j web3j, EvmSigner signer, String contractAddress,
                                   String encodedData, BigInteger gasLimit) {
        try {
            EthSendTransaction sent;
            synchronized (senderLock(signer.address())) {
                BigInteger gasPrice = gasPrice(web3j);
                BigInteger nonce = nonce(web3j, signer.address());

                RawTransaction tx = RawTransaction.createTransaction(
                        nonce, gasPrice, gasLimit, contractAddress, encodedData);

                byte[] signed = signer.signTransaction(tx, resolveChainId(web3j));
                sent = web3j
                        .ethSendRawTransaction(Numeric.toHexString(signed))
                        .send();
            }

            if (sent.hasError()) {
                throw new RuntimeException("Transaction failed: " + sent.getError().getMessage());
            }
            TransactionReceipt receipt = waitForReceipt(web3j, sent.getTransactionHash());
            if (!receipt.isStatusOK()) {
                // A mined-but-reverted transaction also yields a receipt — callers of
                // send() expect on-chain success, so a revert must surface as an error.
                throw new RuntimeException("Transaction reverted on-chain: tx="
                        + receipt.getTransactionHash() + " status=" + receipt.getStatus());
            }
            return receipt;
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
    public String deploy(Web3j web3j, EvmSigner signer, String binary,
                         String encodedConstructor) {
        String data = Numeric.cleanHexPrefix(binary)
                + (encodedConstructor != null ? Numeric.cleanHexPrefix(encodedConstructor) : "");
        try {
            EthSendTransaction sent;
            synchronized (senderLock(signer.address())) {
                BigInteger gasPrice = gasPrice(web3j);
                BigInteger nonce = nonce(web3j, signer.address());

                RawTransaction tx = RawTransaction.createContractTransaction(
                        nonce, gasPrice, DEPLOY_GAS_LIMIT, BigInteger.ZERO, data);

                byte[] signed = signer.signTransaction(tx, resolveChainId(web3j));
                sent = web3j
                        .ethSendRawTransaction(Numeric.toHexString(signed))
                        .send();
            }

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

    /**
     * Resolves and caches the chain ID for EIP-155 replay-protected signing.
     * Legacy (unprotected) signatures would be (a) rejected by chains that enforce
     * EIP-155 and (b) replayable across every chain where the registry wallet
     * exists — a forcedTransfer signed for one network must never be valid on another.
     */
    private long resolveChainId(Web3j web3j) {
        return chainIdCache.computeIfAbsent(web3j, client -> {
            try {
                return client.ethChainId().send().getChainId().longValue();
            } catch (Exception e) {
                throw new RuntimeException("Could not resolve chain ID for EIP-155 signing: "
                        + e.getMessage(), e);
            }
        });
    }

    private Object senderLock(String address) {
        return senderLocks.computeIfAbsent(address.toLowerCase(java.util.Locale.ROOT), k -> new Object());
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
