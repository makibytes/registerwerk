package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.internal.NonceCoordinator;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthFeeHistory;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas;
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
 *
 * <p><strong>Multi-replica safety.</strong> {@code submit}/{@code send}/{@code deploy} decide the
 * nonce, sign, and broadcast inside a single {@link NonceCoordinator#withNonce} call, which holds
 * a Postgres advisory lock per {@code (chainId, senderAddress)} for that whole critical section —
 * safe across every backend replica, not just within one JVM. See {@link NonceCoordinator}'s
 * class Javadoc for why a durable lease table exists alongside the lock.
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
    private final NonceCoordinator         nonceCoordinator;
    private final ChainQuarantinePort       chainQuarantine;

    /** eth_chainId per Web3j client — chain clients are long-lived singletons. */
    private final ConcurrentHashMap<Web3j, Long> chainIdCache = new ConcurrentHashMap<>();

    public EvmContractService(BlockchainClientRegistry clientRegistry,
                               ChainConfigRepository chainConfigRepository,
                               WalletSigner walletSigner,
                               NonceCoordinator nonceCoordinator,
                               ChainQuarantinePort chainQuarantine) {
        this.clientRegistry       = clientRegistry;
        this.chainConfigRepository = chainConfigRepository;
        this.walletSigner          = walletSigner;
        this.nonceCoordinator      = nonceCoordinator;
        this.chainQuarantine       = chainQuarantine;
    }

    // ── Credential helpers ────────────────────────────────────────────────────

    /** Returns credentials for the default wallet of the given chain config. */
    public EvmSigner signer(UUID chainConfigId) {
        return walletSigner.evmSignerForChain(chainConfigId);
    }

    /** Returns credentials for the default wallet of the given chain descriptor. */
    public EvmSigner signer(ChainDescriptor descriptor) {
        return signer(chainConfigId(descriptor));
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

    /** Resolves the stable database identity required by every state-changing EVM call. */
    public UUID chainConfigId(ChainDescriptor descriptor) {
        var matches = chainConfigRepository
                .findByIdentifierStartingWith(descriptor.chain().name() + "_").stream()
                .filter(ChainConfig::isEnabled)
                .filter(config -> config.getChainType() == ChainConfig.ChainType.EVM)
                .filter(config -> config.getNetworkType().name()
                        .equals(descriptor.network().name()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one enabled EVM ChainConfig for "
                    + descriptor + ", found " + matches.size());
        }
        return matches.getFirst().getId();
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
    @Deprecated(forRemoval = true)
    public String submit(Web3j web3j, EvmSigner signer, String contractAddress, Function function) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM submission");
    }

    /**
     * Chain-aware immediate submission boundary. The chain row lock is retained through the
     * RPC call, making submission and quarantine activation strictly ordered across replicas.
     */
    @Transactional
    public String submit(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, Function function) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        return submitEncoded(web3j, signer, contractAddress,
                FunctionEncoder.encode(function), CALL_GAS_LIMIT);
    }

    /**
     * Submits a raw ABI-encoded call to {@code contractAddress} and returns the transaction hash.
     */
    @Deprecated(forRemoval = true)
    public String submit(Web3j web3j, EvmSigner signer, String contractAddress,
                         String encodedData, BigInteger gasLimit) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM submission");
    }

    @Transactional
    public String submit(UUID chainConfigId, Web3j web3j, EvmSigner signer, String contractAddress,
            String encodedData, BigInteger gasLimit) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        return submitEncoded(web3j, signer, contractAddress, encodedData, gasLimit);
    }

    private String submitEncoded(Web3j web3j, EvmSigner signer, String contractAddress,
                         String encodedData, BigInteger gasLimit) {
        try {
            long chainId = resolveChainId(web3j);
            BigInteger effectiveGasLimit = estimateGasLimit(web3j, signer.address(), contractAddress,
                    encodedData, gasLimit);
            Fees fees = resolveFees(web3j);
            // Fleet-wide nonce coordination (NonceCoordinator): two concurrent submissions —
            // whether on this instance or another replica — would otherwise read the same
            // pending nonce and one transaction would silently replace the other.
            EthSendTransaction sent = nonceCoordinator.withNonce(chainId, signer.address(),
                    () -> nonce(web3j, signer.address()),
                    nonce -> {
                        RawTransaction tx = buildTransaction(
                                chainId, nonce, effectiveGasLimit, contractAddress, encodedData, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        EthSendTransaction result = web3j
                                .ethSendRawTransaction(Numeric.toHexString(signed))
                                .send();
                        if (result.hasError()) {
                            // Thrown inside the coordinator's callback: a failed submission must
                            // not advance the nonce lease, so the same nonce is retried next time.
                            throw new RuntimeException(
                                    "Transaction submission error: " + result.getError().getMessage());
                        }
                        return result;
                    });
            return sent.getTransactionHash();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("EVM transaction submit error: " + e.getMessage(), e);
        }
    }

    /** A deterministic signed transaction prepared without contacting {@code eth_sendRawTransaction}. */
    public record PreparedRawTransaction(
            String txHash, String signedPayload, long chainId, String senderAddress, BigInteger nonce) {}

    /**
     * Reserves a nonce and signs a transaction in the caller's database transaction. No broadcast
     * occurs here. Persist the returned payload in that same transaction, then use
     * {@link #broadcastPrepared} after commit. This eliminates the uncloseable "RPC succeeded,
     * tx hash persistence failed" window of sign-and-submit APIs.
     */
    @Transactional
    public PreparedRawTransaction prepareDurable(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, Function function) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        try {
            String encodedData = FunctionEncoder.encode(function);
            long chainId = resolveChainId(web3j);
            BigInteger effectiveGasLimit = estimateGasLimit(web3j, signer.address(), contractAddress,
                    encodedData, CALL_GAS_LIMIT);
            Fees fees = resolveFees(web3j);
            return nonceCoordinator.withReservedNonce(chainId, signer.address(),
                    () -> nonce(web3j, signer.address()), nonce -> {
                        RawTransaction tx = buildTransaction(
                                chainId, nonce, effectiveGasLimit, contractAddress, encodedData, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        String payload = Numeric.toHexString(signed);
                        String txHash = Numeric.toHexString(Hash.sha3(signed));
                        return new PreparedRawTransaction(
                                txHash, payload, chainId, signer.address(), nonce);
                    });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("EVM durable transaction preparation error: " + e.getMessage(), e);
        }
    }

    /**
     * Broadcasts an already-persisted signed payload. Repeating this method can never create a
     * second semantic transaction: the same signed bytes always have the same sender, nonce and
     * hash. An ambiguous provider/network failure is deliberately propagated so the outbox retries
     * those exact bytes.
     */
    @Transactional
    public String broadcastPrepared(UUID chainConfigId, Web3j web3j,
            String signedPayload, String expectedTxHash) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        try {
            EthSendTransaction sent = web3j.ethSendRawTransaction(signedPayload).send();
            if (sent.hasError()) {
                throw new RuntimeException(
                        "Prepared transaction submission error: " + sent.getError().getMessage());
            }
            String returnedHash = sent.getTransactionHash();
            if (returnedHash == null || !returnedHash.equalsIgnoreCase(expectedTxHash)) {
                throw new IllegalStateException("RPC returned tx hash " + returnedHash
                        + " for prepared transaction " + expectedTxHash);
            }
            return returnedHash;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("EVM prepared transaction submit error: " + e.getMessage(), e);
        }
    }

    /**
     * Encodes {@code function}, sends a signed raw transaction to {@code contractAddress},
     * and waits for the receipt.
     *
     * @return mined {@link TransactionReceipt}
     * @throws RuntimeException wrapping any IO or timeout error
     */
    @Deprecated(forRemoval = true)
    public TransactionReceipt send(Web3j web3j, EvmSigner signer, String contractAddress,
                                   Function function) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM submission");
    }

    /** Chain-aware synchronous submission retaining the quarantine lock through its receipt. */
    @Transactional
    public TransactionReceipt send(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, Function function) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        return sendEncoded(web3j, signer, contractAddress,
                FunctionEncoder.encode(function), CALL_GAS_LIMIT);
    }

    /**
     * Sends a raw ABI-encoded call to {@code contractAddress} with a custom gas limit.
     */
    @Deprecated(forRemoval = true)
    public TransactionReceipt send(Web3j web3j, EvmSigner signer, String contractAddress,
                                   String encodedData, BigInteger gasLimit) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM submission");
    }

    @Transactional
    public TransactionReceipt send(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, String encodedData, BigInteger gasLimit) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        return sendEncoded(web3j, signer, contractAddress, encodedData, gasLimit);
    }

    private TransactionReceipt sendEncoded(Web3j web3j, EvmSigner signer, String contractAddress,
                                   String encodedData, BigInteger gasLimit) {
        try {
            long chainId = resolveChainId(web3j);
            BigInteger effectiveGasLimit = estimateGasLimit(web3j, signer.address(), contractAddress,
                    encodedData, gasLimit);
            Fees fees = resolveFees(web3j);
            EthSendTransaction sent = nonceCoordinator.withNonce(chainId, signer.address(),
                    () -> nonce(web3j, signer.address()),
                    nonce -> {
                        RawTransaction tx = buildTransaction(
                                chainId, nonce, effectiveGasLimit, contractAddress, encodedData, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        EthSendTransaction result = web3j
                                .ethSendRawTransaction(Numeric.toHexString(signed))
                                .send();
                        if (result.hasError()) {
                            throw new RuntimeException("Transaction failed: " + result.getError().getMessage());
                        }
                        return result;
                    });

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
    @Deprecated(forRemoval = true)
    public String deploy(Web3j web3j, EvmSigner signer, String binary,
                         String encodedConstructor) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM deployment");
    }

    @Transactional
    public String deploy(UUID chainConfigId, Web3j web3j, EvmSigner signer, String binary,
                         String encodedConstructor) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        return deployEncoded(web3j, signer, binary, encodedConstructor);
    }

    private String deployEncoded(Web3j web3j, EvmSigner signer, String binary,
                         String encodedConstructor) {
        String data = Numeric.cleanHexPrefix(binary)
                + (encodedConstructor != null ? Numeric.cleanHexPrefix(encodedConstructor) : "");
        try {
            long chainId = resolveChainId(web3j);
            BigInteger effectiveGasLimit = estimateDeployGasLimit(web3j, signer.address(), data, DEPLOY_GAS_LIMIT);
            Fees fees = resolveFees(web3j);
            EthSendTransaction sent = nonceCoordinator.withNonce(chainId, signer.address(),
                    () -> nonce(web3j, signer.address()),
                    nonce -> {
                        // "" (empty, not null) signals contract creation to RawTransaction's RLP
                        // encoding — the same convention RawTransaction.createContractTransaction
                        // uses internally for the legacy path.
                        RawTransaction tx = buildTransaction(chainId, nonce, effectiveGasLimit, "", data, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        EthSendTransaction result = web3j
                                .ethSendRawTransaction(Numeric.toHexString(signed))
                                .send();
                        if (result.hasError()) {
                            throw new RuntimeException("Deploy failed: " + result.getError().getMessage());
                        }
                        return result;
                    });

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

    /** Either a legacy (type-0) gasPrice or an EIP-1559 (type-2) fee pair — never both. */
    private record Fees(boolean eip1559, BigInteger gasPrice,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas) {
        static Fees legacy(BigInteger gasPrice) {
            return new Fees(false, gasPrice, null, null);
        }
        static Fees eip1559(BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas) {
            return new Fees(true, null, maxPriorityFeePerGas, maxFeePerGas);
        }
    }

    /**
     * Resolves EIP-1559 (type-2) fees when the chain supports {@code eth_feeHistory}, falling
     * back to the legacy {@link #gasPrice} heuristic otherwise. Some configured chains (older
     * testnets, certain L2s, confidential-EVM sidecars) do not implement the London fee-market
     * RPC methods, so this must degrade gracefully rather than assume every chain supports it.
     */
    private Fees resolveFees(Web3j web3j) {
        try {
            EthFeeHistory feeHistoryResponse =
                    web3j.ethFeeHistory(1, DefaultBlockParameterName.LATEST, List.of()).send();
            if (feeHistoryResponse.hasError()) {
                throw new RuntimeException(feeHistoryResponse.getError().getMessage());
            }
            List<BigInteger> baseFees = feeHistoryResponse.getFeeHistory().getBaseFeePerGas();
            if (baseFees == null || baseFees.isEmpty()) {
                throw new RuntimeException("eth_feeHistory returned no baseFeePerGas");
            }
            // The last entry is the projected base fee for the NEXT block — the one this
            // transaction will actually land in.
            BigInteger nextBaseFee = baseFees.get(baseFees.size() - 1);

            BigInteger tip;
            try {
                EthMaxPriorityFeePerGas tipResponse = web3j.ethMaxPriorityFeePerGas().send();
                tip = (!tipResponse.hasError() && tipResponse.getMaxPriorityFeePerGas() != null)
                        ? tipResponse.getMaxPriorityFeePerGas()
                        : BigInteger.valueOf(1_500_000_000L); // 1.5 gwei — conservative default tip
            } catch (Exception e) {
                tip = BigInteger.valueOf(1_500_000_000L);
            }

            // 2x base fee + tip: base fee can rise at most 12.5% per block, so this comfortably
            // covers several consecutive full blocks before the transaction would need re-pricing
            // — the same headroom heuristic widely used by wallet/client libraries.
            BigInteger maxFeePerGas = nextBaseFee.multiply(BigInteger.TWO).add(tip);
            return Fees.eip1559(tip, maxFeePerGas);
        } catch (Exception e) {
            log.debug("EIP-1559 fee data unavailable ({}); falling back to legacy gasPrice.", e.getMessage());
            try {
                return Fees.legacy(gasPrice(web3j));
            } catch (Exception ex) {
                return Fees.legacy(BigInteger.valueOf(20_000_000_000L));
            }
        }
    }

    /** {@code to} = {@code ""} (not null) signals contract creation, matching
     *  {@link RawTransaction}'s own convention for the legacy path. */
    private RawTransaction buildTransaction(long chainId, BigInteger nonce, BigInteger gasLimit,
            String to, String data, Fees fees) {
        if (fees.eip1559()) {
            return RawTransaction.createTransaction(chainId, nonce, gasLimit, to, BigInteger.ZERO, data,
                    fees.maxPriorityFeePerGas(), fees.maxFeePerGas());
        }
        return RawTransaction.createTransaction(nonce, fees.gasPrice(), gasLimit, to, data);
    }

    /**
     * Estimates the gas limit for a contract call via {@code eth_estimateGas}, replacing the
     * previous fixed {@code CALL_GAS_LIMIT}. {@code fallback} (the caller-supplied or default
     * limit) is used whenever estimation fails or errors — e.g. the node doesn't support the
     * call, or execution depends on state that makes a dry-run estimate unreliable — so a
     * transaction is never blocked on {@code eth_estimateGas} being available.
     */
    private BigInteger estimateGasLimit(Web3j web3j, String from, String to, String data, BigInteger fallback) {
        try {
            EthEstimateGas result = web3j.ethEstimateGas(
                    Transaction.createFunctionCallTransaction(from, null, null, null, to, data)).send();
            if (result.hasError()) {
                log.debug("eth_estimateGas error ({}); using fallback gas limit {}.",
                        result.getError().getMessage(), fallback);
                return fallback;
            }
            return withSafetyMargin(result.getAmountUsed());
        } catch (Exception e) {
            log.debug("eth_estimateGas failed ({}); using fallback gas limit {}.", e.getMessage(), fallback);
            return fallback;
        }
    }

    /** Same as {@link #estimateGasLimit} but for contract creation (no {@code to} address). */
    private BigInteger estimateDeployGasLimit(Web3j web3j, String from, String initCode, BigInteger fallback) {
        try {
            EthEstimateGas result = web3j.ethEstimateGas(
                    Transaction.createContractTransaction(from, null, null, null, BigInteger.ZERO, initCode))
                    .send();
            if (result.hasError()) {
                log.debug("eth_estimateGas (deploy) error ({}); using fallback gas limit {}.",
                        result.getError().getMessage(), fallback);
                return fallback;
            }
            return withSafetyMargin(result.getAmountUsed());
        } catch (Exception e) {
            log.debug("eth_estimateGas (deploy) failed ({}); using fallback gas limit {}.", e.getMessage(), fallback);
            return fallback;
        }
    }

    /**
     * 20% headroom over a raw {@code eth_estimateGas} result: the estimate reflects state at
     * call time, which can shift by the time the transaction actually executes (e.g. a
     * compliance check takes a costlier branch, or a storage slot that was cold becomes warm
     * from an intervening transaction) — landing exactly on the estimate risks an out-of-gas
     * revert.
     */
    private BigInteger withSafetyMargin(BigInteger estimated) {
        return estimated.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
    }

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

    private BigInteger nonce(Web3j web3j, String address) throws Exception {
        EthGetTransactionCount cnt = web3j.ethGetTransactionCount(
                address, DefaultBlockParameterName.PENDING).send();
        return cnt.getTransactionCount();
    }

    /**
     * Polls until the transaction is mined or times out — deliberately not confirmation-depth or
     * {@code FinalityModel}-aware, unlike {@code BlockchainTransactionService.pollPendingTransactions}.
     *
     * <p>This is safe only because every caller that needs the mined result to be authoritative
     * re-verifies it asynchronously through an already model-aware path before treating it as
     * final: the deployment factory flow ({@code send}/{@code deploy} here) only extracts a
     * tx hash/address and hands off to {@code AssetDeploymentService.syncFromChain}; tracked
     * corrections ({@code Erc3643LifecycleService.submitToSuite} and similar) use the
     * non-blocking {@link #submit} and {@code BlockchainTransactionService.record} instead of
     * this method entirely. A caller that instead treats this method's return as final truth
     * with no downstream re-verification (e.g. writing removal/compliance state immediately
     * after it returns) reintroduces exactly the reorg gap those two paths were fixed to close —
     * see {@code IdentityRegistryService.removeInvestor} for a known instance of that.
     *
     * <p>Blocking here for full confirmation depth was deliberately rejected: on a
     * {@code DEPTH_BASED} chain like Polygon (128 confirmations) that would turn a
     * synchronous admin HTTP call into a multi-minute one, for no benefit where the downstream
     * re-verification above already exists.
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
