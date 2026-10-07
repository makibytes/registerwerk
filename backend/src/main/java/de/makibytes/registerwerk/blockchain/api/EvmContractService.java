package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.events.TxSubmissionRefusedEvent;
import de.makibytes.registerwerk.blockchain.internal.NonceCoordinator;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.shared.TransientChainException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.protocol.core.Response;
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
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.transaction.type.Transaction1559;
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

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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

    private final ApplicationEventPublisher eventPublisher;
    private final IsolatedTransactionExecutor isolatedTransactions;
    private final MeterRegistry             meters;
    private final EvmSubmissionSettings     settings;

    /**
     * Bounds concurrent immediate submit/send/deploy calls (P4B-1): each holds the caller's pooled
     * connection for the whole call and needs a second one inside {@code NonceCoordinator.withNonce},
     * so unbounded concurrency can deadlock the pool (hold-and-wait).
     */
    private final Semaphore immediateSlots;
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    public EvmContractService(BlockchainClientRegistry clientRegistry,
                               ChainConfigRepository chainConfigRepository,
                               WalletSigner walletSigner,
                               NonceCoordinator nonceCoordinator,
                               ChainQuarantinePort chainQuarantine,
                               ApplicationEventPublisher eventPublisher,
                               IsolatedTransactionExecutor isolatedTransactions,
                               MeterRegistry meters,
                               EvmSubmissionSettings settings) {
        this.clientRegistry       = clientRegistry;
        this.chainConfigRepository = chainConfigRepository;
        this.walletSigner          = walletSigner;
        this.nonceCoordinator      = nonceCoordinator;
        this.chainQuarantine       = chainQuarantine;
        this.eventPublisher        = eventPublisher;
        this.isolatedTransactions  = isolatedTransactions;
        this.meters                = meters;
        this.settings              = settings;
        this.immediateSlots        = new Semaphore(settings.immediateSubmitPermits(), true);
    }

    @Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /** Without audit/metrics wiring and with default settings (unit tests, tooling). */
    public EvmContractService(BlockchainClientRegistry clientRegistry,
                               ChainConfigRepository chainConfigRepository,
                               WalletSigner walletSigner,
                               NonceCoordinator nonceCoordinator,
                               ChainQuarantinePort chainQuarantine) {
        this(clientRegistry, chainConfigRepository, walletSigner, nonceCoordinator, chainQuarantine,
                null, null, null, EvmSubmissionSettings.defaults());
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
     * Returns the pinned chain ID ({@code chain_config.chain_id}) for the given chain config.
     *
     * @throws ChainIdMismatchException if no chain id is pinned - there is deliberately no default
     */
    public long chainId(UUID chainConfigId) {
        ChainConfig config = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        if (config.getChainId() == null) {
            throw ChainIdMismatchException.unpinned(config.getIdentifier());
        }
        return config.getChainId();
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
    public String submit(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, Function function) {
        return withImmediateSlot("submit", () -> {
            chainQuarantine.requireSubmissionAllowed(chainConfigId);
            return submitEncoded(chainConfigId, web3j, signer, contractAddress,
                    FunctionEncoder.encode(function), CALL_GAS_LIMIT);
        });
    }

    /**
     * Submits a raw ABI-encoded call to {@code contractAddress} and returns the transaction hash.
     */
    @Deprecated(forRemoval = true)
    public String submit(Web3j web3j, EvmSigner signer, String contractAddress,
                         String encodedData, BigInteger gasLimit) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM submission");
    }

    public String submit(UUID chainConfigId, Web3j web3j, EvmSigner signer, String contractAddress,
            String encodedData, BigInteger gasLimit) {
        return withImmediateSlot("submit", () -> {
            chainQuarantine.requireSubmissionAllowed(chainConfigId);
            return submitEncoded(chainConfigId, web3j, signer, contractAddress, encodedData, gasLimit);
        });
    }

    private String submitEncoded(UUID chainConfigId, Web3j web3j, EvmSigner signer, String contractAddress,
                         String encodedData, BigInteger gasLimit) {
        try {
            SigningContext ctx = signingContext(chainConfigId, web3j);
            long chainId = ctx.chainId();
            BigInteger effectiveGasLimit = boundedGasLimit(ctx,
                    estimateGasLimit(web3j, signer.address(), contractAddress, encodedData, gasLimit));
            Fees fees = resolveFees(web3j, ctx);
            // Fleet-wide nonce coordination (NonceCoordinator): two concurrent submissions —
            // whether on this instance or another replica — would otherwise read the same
            // pending nonce and one transaction would silently replace the other.
            EthSendTransaction sent = nonceCoordinator.withNonce(chainId, signer.address(),
                    nonceSource(web3j, ctx.identifier(), signer.address()),
                    nonce -> {
                        RawTransaction tx = buildTransaction(
                                chainId, nonce, effectiveGasLimit, contractAddress, encodedData, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        registerDirect(chainId, signer.address(), nonce, signed, "SUBMIT");
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
            SigningContext ctx = signingContext(chainConfigId, web3j);
            long chainId = ctx.chainId();
            BigInteger effectiveGasLimit = boundedGasLimit(ctx,
                    estimateGasLimit(web3j, signer.address(), contractAddress, encodedData, CALL_GAS_LIMIT));
            Fees fees = resolveFees(web3j, ctx);
            return nonceCoordinator.withReservedNonce(chainId, signer.address(),
                    nonceSource(web3j, ctx.identifier(), signer.address()), nonce -> {
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

    /** Which replacement {@link #resign} builds: same call at a higher fee, or a 0-value self-send. */
    public enum ReplacementKind { REPRICE, CANCEL }

    /** Fee-relevant fields of a signed payload; {@code maxFeePerGas} carries the gas price of a legacy tx. */
    public record PayloadInfo(boolean eip1559, long nonce, BigInteger gasLimit,
            BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas, String to) {}

    /** Decodes a persisted signed payload without touching the network (operator views, bump maths). */
    public static PayloadInfo describe(String signedPayload) {
        RawTransaction raw = TransactionDecoder.decode(signedPayload);
        if (raw.getTransaction() instanceof Transaction1559 t) {
            return new PayloadInfo(true, raw.getNonce().longValueExact(), raw.getGasLimit(),
                    t.getMaxFeePerGas(), t.getMaxPriorityFeePerGas(), raw.getTo());
        }
        return new PayloadInfo(false, raw.getNonce().longValueExact(), raw.getGasLimit(),
                raw.getGasPrice(), null, raw.getTo());
    }

    /**
     * Re-signs an outbox payload at its <em>same nonce</em> (P4B-4). {@link ReplacementKind#REPRICE}
     * keeps recipient, calldata, value and gas limit and raises the fees; {@link ReplacementKind#CANCEL}
     * replaces it with a 0-value self-send of 21000 gas so the nonce is consumed without effect. Fees are
     * {@code max(fresh network fees, old fees * (100 + minBumpPercent) / 100)} - nodes only accept a
     * replacement that is at least 10 % dearer in both fee fields - and are checked against the same fee
     * ceilings as any signing ({@link FeeAboveCeilingException}). No nonce is reserved: the lease is
     * untouched because the nonce was already taken by the payload being replaced. The caller persists the
     * result before any broadcast, exactly like {@link #prepareDurable}.
     */
    public PreparedRawTransaction resign(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String originalSignedPayload, ReplacementKind kind, int minBumpPercent) {
        chainQuarantine.requireSubmissionAllowed(chainConfigId);
        try {
            RawTransaction original = TransactionDecoder.decode(originalSignedPayload);
            SigningContext ctx = signingContext(chainConfigId, web3j);
            PayloadInfo info = describe(originalSignedPayload);
            Fees fresh = resolveUncappedFees(web3j);
            BigInteger factor = BigInteger.valueOf(100L + Math.max(minBumpPercent, 10));
            Fees fees;
            if (info.eip1559()) {
                BigInteger freshTip = fresh.eip1559() ? fresh.maxPriorityFeePerGas() : BigInteger.ZERO;
                BigInteger freshMax = fresh.eip1559() ? fresh.maxFeePerGas() : fresh.gasPrice();
                BigInteger tip = freshTip.max(bump(info.maxPriorityFeePerGas(), factor));
                BigInteger max = freshMax.max(bump(info.maxFeePerGas(), factor)).max(tip);
                fees = Fees.eip1559(tip, max);
            } else {
                BigInteger freshPrice = fresh.eip1559() ? fresh.maxFeePerGas() : fresh.gasPrice();
                fees = Fees.legacy(freshPrice.max(bump(info.maxFeePerGas(), factor)));
            }
            fees = enforceFeeCeiling(ctx, fees);

            String to = kind == ReplacementKind.CANCEL ? signer.address() : original.getTo();
            String data = kind == ReplacementKind.CANCEL ? "" : original.getData();
            BigInteger gasLimit = kind == ReplacementKind.CANCEL ? BigInteger.valueOf(21_000L) : original.getGasLimit();
            BigInteger value = kind == ReplacementKind.CANCEL ? BigInteger.ZERO : original.getValue();
            RawTransaction tx = fees.eip1559()
                    ? RawTransaction.createTransaction(ctx.chainId(), original.getNonce(), gasLimit, to, value,
                            data, fees.maxPriorityFeePerGas(), fees.maxFeePerGas())
                    : RawTransaction.createTransaction(original.getNonce(), fees.gasPrice(), gasLimit, to, value, data);
            byte[] signed = signer.signTransaction(tx, ctx.chainId());
            return new PreparedRawTransaction(Numeric.toHexString(Hash.sha3(signed)), Numeric.toHexString(signed),
                    ctx.chainId(), signer.address(), original.getNonce());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("EVM replacement transaction error: " + e.getMessage(), e);
        }
    }

    private static BigInteger bump(BigInteger value, BigInteger factorPercent) {
        // round up so that a 10 % floor is never missed by integer truncation
        return value.multiply(factorPercent).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));
    }

    /** {@code eth_getTransactionCount} at the given tag (LATEST = mined; FINALIZED = irreversible if supported). */
    public BigInteger transactionCount(Web3j web3j, String address, DefaultBlockParameterName tag) throws Exception {
        EthGetTransactionCount cnt = web3j.ethGetTransactionCount(address, tag).send();
        if (cnt.hasError()) {
            throw new RuntimeException(cnt.getError().getMessage());
        }
        return cnt.getTransactionCount();
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
    public TransactionReceipt send(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, Function function) {
        return withImmediateSlot("send", () -> {
            chainQuarantine.requireSubmissionAllowed(chainConfigId);
            return sendEncoded(chainConfigId, web3j, signer, contractAddress,
                    FunctionEncoder.encode(function), CALL_GAS_LIMIT);
        });
    }

    /**
     * Sends a raw ABI-encoded call to {@code contractAddress} with a custom gas limit.
     */
    @Deprecated(forRemoval = true)
    public TransactionReceipt send(Web3j web3j, EvmSigner signer, String contractAddress,
                                   String encodedData, BigInteger gasLimit) {
        throw new IllegalStateException("chainConfigId is required for quarantine-safe EVM submission");
    }

    public TransactionReceipt send(UUID chainConfigId, Web3j web3j, EvmSigner signer,
            String contractAddress, String encodedData, BigInteger gasLimit) {
        return withImmediateSlot("send", () -> {
            chainQuarantine.requireSubmissionAllowed(chainConfigId);
            return sendEncoded(chainConfigId, web3j, signer, contractAddress, encodedData, gasLimit);
        });
    }

    private TransactionReceipt sendEncoded(UUID chainConfigId, Web3j web3j, EvmSigner signer,
                                   String contractAddress, String encodedData, BigInteger gasLimit) {
        try {
            SigningContext ctx = signingContext(chainConfigId, web3j);
            long chainId = ctx.chainId();
            BigInteger effectiveGasLimit = boundedGasLimit(ctx,
                    estimateGasLimit(web3j, signer.address(), contractAddress, encodedData, gasLimit));
            Fees fees = resolveFees(web3j, ctx);
            EthSendTransaction sent = nonceCoordinator.withNonce(chainId, signer.address(),
                    nonceSource(web3j, ctx.identifier(), signer.address()),
                    nonce -> {
                        RawTransaction tx = buildTransaction(
                                chainId, nonce, effectiveGasLimit, contractAddress, encodedData, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        registerDirect(chainId, signer.address(), nonce, signed, "SEND");
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

    public String deploy(UUID chainConfigId, Web3j web3j, EvmSigner signer, String binary,
                         String encodedConstructor) {
        return withImmediateSlot("deploy", () -> {
            chainQuarantine.requireSubmissionAllowed(chainConfigId);
            return deployEncoded(chainConfigId, web3j, signer, binary, encodedConstructor);
        });
    }

    private String deployEncoded(UUID chainConfigId, Web3j web3j, EvmSigner signer, String binary,
                         String encodedConstructor) {
        String data = Numeric.cleanHexPrefix(binary)
                + (encodedConstructor != null ? Numeric.cleanHexPrefix(encodedConstructor) : "");
        try {
            SigningContext ctx = signingContext(chainConfigId, web3j);
            long chainId = ctx.chainId();
            BigInteger effectiveGasLimit = boundedGasLimit(ctx,
                    estimateDeployGasLimit(web3j, signer.address(), data, DEPLOY_GAS_LIMIT));
            Fees fees = resolveFees(web3j, ctx);
            EthSendTransaction sent = nonceCoordinator.withNonce(chainId, signer.address(),
                    nonceSource(web3j, ctx.identifier(), signer.address()),
                    nonce -> {
                        // "" (empty, not null) signals contract creation to RawTransaction's RLP
                        // encoding — the same convention RawTransaction.createContractTransaction
                        // uses internally for the legacy path.
                        RawTransaction tx = buildTransaction(chainId, nonce, effectiveGasLimit, "", data, fees);
                        byte[] signed = signer.signTransaction(tx, chainId);
                        registerDirect(chainId, signer.address(), nonce, signed, "DEPLOY");
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

    /**
     * Pre-flight: runs {@code function} as an {@code eth_call} from the chain's default signer
     * (the account a subsequent {@code submit} would send from) and reports whether it would
     * revert. Only a UX/pre-check aid — the on-chain execution stays authoritative.
     *
     * @return the revert reason (the RPC error message, which carries the contract's
     *         {@code require} string) if the call would revert; empty if it would succeed or the
     *         simulation itself could not be run (node unreachable, non-revert RPC error)
     */
    public Optional<String> simulateRevert(UUID chainConfigId, String contractAddress, Function function) {
        try {
            Web3j web3j = evmClient(chainConfigId);
            String from = signer(chainConfigId).address();
            EthCall result = web3j.ethCall(
                    Transaction.createEthCallTransaction(from, contractAddress, FunctionEncoder.encode(function)),
                    DefaultBlockParameterName.LATEST).send();
            if (result.hasError()) {
                String message = result.getError().getMessage();
                if (message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("revert")) {
                    return Optional.empty();
                }
                // A custom error (e.g. NavNotStruckAfterDealingPoint) travels as revert data, which many nodes
                // keep out of the message: append it so VaultRevertReasons can recognise the error.
                String data = result.getError().getData();
                return Optional.of(data != null && !data.isBlank() && !message.contains(data)
                        ? message + " " + data : message);
            }
            if (result.isReverted()) {
                return Optional.of(String.valueOf(result.getRevertReason()));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Pre-flight simulation of {} on {} unavailable: {}", function.getName(), contractAddress, e.getMessage());
            return Optional.empty();
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
     *
     * <p>The resulting fees are checked against the chain's fee ceiling ({@link SigningContext}):
     * a node reporting an absurd base fee must never lead to a signed transaction (P4B-2).
     */
    private Fees resolveFees(Web3j web3j, SigningContext ctx) {
        return enforceFeeCeiling(ctx, crossCheckFees(resolveUncappedFees(web3j), web3j, ctx.identifier()));
    }

    /**
     * Second-node fee cross-check (P4B-2 interim, K5): when another healthy node exists its fees are
     * fetched too and the result is {@code min(A, B * 1.5)}, so one node inflating fee data cannot make
     * the registry overpay by more than 50 % over an independent view. A second node that cannot answer
     * (or answers in the other fee model) leaves {@code A} untouched; a low outlier is bounded by the 1.5
     * factor and never triggers a fallback default.
     */
    private Fees crossCheckFees(Fees a, Web3j primary, String identifier) {
        try {
            List<BlockchainClientRegistry.EvmNodeClient> healthy = clientRegistry.evmNodeClients(identifier).stream()
                    .filter(BlockchainClientRegistry.EvmNodeClient::healthy).collect(java.util.stream.Collectors.toList());
            if (healthy.size() < 2) return a;
            // A direct client is skipped by identity; the failover client is served by the top-ranked node.
            if (!healthy.removeIf(n -> n.client() == primary)) healthy.removeFirst();
            for (BlockchainClientRegistry.EvmNodeClient other : healthy) {
                Optional<Fees> b = fetchFees(other.client());
                if (b.isPresent()) return minWithHeadroom(a, b.get());
            }
        } catch (RuntimeException e) {
            log.debug("Fee cross-check skipped: {}", e.getMessage());
        }
        return a;
    }

    private static Fees minWithHeadroom(Fees a, Fees b) {
        BigInteger num = BigInteger.valueOf(3), den = BigInteger.TWO;
        if (a.eip1559() && b.eip1559()) {
            BigInteger tip = a.maxPriorityFeePerGas().min(b.maxPriorityFeePerGas().multiply(num).divide(den));
            BigInteger max = a.maxFeePerGas().min(b.maxFeePerGas().multiply(num).divide(den)).max(tip);
            return Fees.eip1559(tip, max);
        }
        if (!a.eip1559() && !b.eip1559()) {
            return Fees.legacy(a.gasPrice().min(b.gasPrice().multiply(num).divide(den)));
        }
        return a;
    }

    /** Fees as {@link #resolveUncappedFees} would compute them, but empty instead of falling back to defaults. */
    private Optional<Fees> fetchFees(Web3j web3j) {
        try {
            EthFeeHistory history = web3j.ethFeeHistory(1, DefaultBlockParameterName.LATEST, List.of()).send();
            if (!history.hasError() && history.getFeeHistory().getBaseFeePerGas() != null
                    && !history.getFeeHistory().getBaseFeePerGas().isEmpty()) {
                List<BigInteger> baseFees = history.getFeeHistory().getBaseFeePerGas();
                BigInteger tip = BigInteger.valueOf(1_500_000_000L);
                try {
                    EthMaxPriorityFeePerGas t = web3j.ethMaxPriorityFeePerGas().send();
                    if (!t.hasError() && t.getMaxPriorityFeePerGas() != null) tip = t.getMaxPriorityFeePerGas();
                } catch (Exception ignored) {
                    // default tip
                }
                return Optional.of(Fees.eip1559(tip, baseFees.get(baseFees.size() - 1).multiply(BigInteger.TWO).add(tip)));
            }
            EthGasPrice gp = web3j.ethGasPrice().send();
            if (!gp.hasError() && gp.getGasPrice() != null) {
                return Optional.of(Fees.legacy(gp.getGasPrice().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN)));
            }
        } catch (Exception e) {
            log.debug("Second-node fee lookup failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Fees resolveUncappedFees(Web3j web3j) {
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

    private Fees enforceFeeCeiling(SigningContext ctx, Fees fees) {
        if (fees.eip1559()) {
            if (fees.maxPriorityFeePerGas().compareTo(ctx.maxPriorityFeePerGas()) > 0) {
                throw refuse(ctx, "TIP_CEILING", new FeeAboveCeilingException(
                        "maxPriorityFeePerGas", fees.maxPriorityFeePerGas(), ctx.maxPriorityFeePerGas()));
            }
            if (fees.maxFeePerGas().compareTo(ctx.maxFeePerGas()) > 0) {
                throw refuse(ctx, "FEE_CEILING", new FeeAboveCeilingException(
                        "maxFeePerGas", fees.maxFeePerGas(), ctx.maxFeePerGas()));
            }
        } else if (fees.gasPrice().compareTo(ctx.maxFeePerGas()) > 0) {
            throw refuse(ctx, "FEE_CEILING", new FeeAboveCeilingException(
                    "gasPrice", fees.gasPrice(), ctx.maxFeePerGas()));
        }
        return fees;
    }

    /** Refuses a gas limit above the block-gas ceiling (estimate or caller/fallback value alike). */
    private BigInteger boundedGasLimit(SigningContext ctx, BigInteger gasLimit) {
        if (gasLimit.compareTo(settings.maxGasLimit()) > 0) {
            throw refuse(ctx, "GAS_CEILING",
                    new FeeAboveCeilingException("gasLimit", gasLimit, settings.maxGasLimit()));
        }
        return gasLimit;
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
     * Estimates the gas limit for a contract call via {@code eth_estimateGas}. {@code fallback}
     * (the caller-supplied or default limit) is used only when estimation is <em>unavailable</em>:
     * the node does not implement the method, or the transport failed. A node answer saying the
     * call would revert fails here, before signing, with the decoded reason (P4B-3) - broadcasting
     * a doomed transaction with a fallback gas limit only burns fees and a nonce.
     */
    private BigInteger estimateGasLimit(Web3j web3j, String from, String to, String data, BigInteger fallback) {
        return estimate("call", fallback, () -> web3j.ethEstimateGas(
                Transaction.createFunctionCallTransaction(from, null, null, null, to, data)).send());
    }

    /** Same as {@link #estimateGasLimit} but for contract creation (no {@code to} address). */
    private BigInteger estimateDeployGasLimit(Web3j web3j, String from, String initCode, BigInteger fallback) {
        return estimate("deploy", fallback, () -> web3j.ethEstimateGas(
                Transaction.createContractTransaction(from, null, null, null, BigInteger.ZERO, initCode))
                .send());
    }

    @FunctionalInterface
    private interface EstimateCall {
        EthEstimateGas send() throws Exception;
    }

    private BigInteger estimate(String what, BigInteger fallback, EstimateCall call) {
        EthEstimateGas result;
        try {
            result = call.send();
        } catch (Exception e) {
            log.warn("eth_estimateGas ({}) transport failure ({}); using fallback gas limit {}.",
                    what, e.getMessage(), fallback);
            return fallback;
        }
        if (result.hasError()) {
            Response.Error error = result.getError();
            if (isMethodNotFound(error)) {
                log.warn("eth_estimateGas ({}) unsupported by node ({}); using fallback gas limit {}.",
                        what, error.getMessage(), fallback);
                return fallback;
            }
            if (isRevert(error)) {
                throw new ChainRevertException(decodeRevertReason(error));
            }
            throw new TransientChainException("eth_estimateGas (" + what + ") was rejected by the node: "
                    + error.getMessage());
        }
        return withSafetyMargin(result.getAmountUsed());
    }

    private static boolean isMethodNotFound(Response.Error error) {
        if (error.getCode() == -32601) {
            return true;
        }
        String m = lower(error.getMessage());
        return m.contains("method not found") || m.contains("does not exist")
                || m.contains("not supported") || m.contains("unsupported method");
    }

    private static boolean isRevert(Response.Error error) {
        String m = lower(error.getMessage());
        String data = error.getData() == null ? "" : error.getData().toLowerCase(Locale.ROOT);
        return error.getCode() == 3 || m.contains("revert") || m.contains("always failing transaction")
                || data.startsWith(ERROR_STRING_SELECTOR) || data.startsWith(PANIC_SELECTOR);
    }

    private static final String ERROR_STRING_SELECTOR = "0x08c379a0";
    private static final String PANIC_SELECTOR = "0x4e487b71";

    /** Error(string) and Panic(uint256) are decoded; any other selector is a custom error, kept raw. */
    static String decodeRevertReason(Response.Error error) {
        String data = error.getData() == null ? "" : error.getData().trim();
        if (data.length() >= 2 && data.startsWith("\"") && data.endsWith("\"")) {
            data = data.substring(1, data.length() - 1);
        }
        String lowered = data.toLowerCase(Locale.ROOT);
        try {
            if (lowered.startsWith(ERROR_STRING_SELECTOR) && lowered.length() > 10) {
                List<TypeReference<Type>> outputs = List.of(
                        (TypeReference<Type>) (TypeReference<?>) new TypeReference<Utf8String>() {});
                List<Type> decoded = FunctionReturnDecoder.decode("0x" + lowered.substring(10), outputs);
                if (!decoded.isEmpty()) {
                    return String.valueOf(decoded.get(0).getValue());
                }
            }
            if (lowered.startsWith(PANIC_SELECTOR) && lowered.length() >= 74) {
                return "panic code 0x" + new BigInteger(lowered.substring(lowered.length() - 64), 16).toString(16);
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw message
        }
        String message = error.getMessage() == null ? "execution reverted" : error.getMessage();
        if (lowered.startsWith("0x") && lowered.length() >= 10 && !lowered.startsWith(ERROR_STRING_SELECTOR)) {
            return message + " (custom error " + lowered.substring(0, 10) + ")";
        }
        return message;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    /** The pinned chain id and fee ceilings that govern one signing operation. */
    private record SigningContext(UUID chainConfigId, String identifier, long chainId,
            BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas) {}

    /**
     * Establishes the EIP-155 signing chain id from the pinned {@code chain_config.chain_id}, never
     * from the RPC node (P4C-1): a compromised or misconfigured node answering a foreign
     * {@code eth_chainId} would otherwise make the registry sign for the wrong chain - and a
     * CREATE2/forced-transfer signed for one network must never be valid on another. The node is
     * still asked, but only to <em>verify</em> the pin; any disagreement refuses signing. Also loads
     * the chain's fee ceilings (per-chain override or global default).
     */
    private SigningContext signingContext(UUID chainConfigId, Web3j web3j) {
        ChainConfig config = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        SigningContext unpinned = new SigningContext(chainConfigId, config.getIdentifier(), -1,
                settings.defaultMaxFeePerGasWei(), settings.defaultMaxPriorityFeePerGasWei());
        Long pinned = config.getChainId();
        if (pinned == null) {
            throw refuse(unpinned, "CHAIN_ID_UNPINNED", ChainIdMismatchException.unpinned(config.getIdentifier()));
        }
        long reported;
        try {
            reported = web3j.ethChainId().send().getChainId().longValue();
        } catch (Exception e) {
            throw new TransientChainException("Could not verify the chain id of '" + config.getIdentifier()
                    + "' against its RPC node: " + e.getMessage());
        }
        if (reported != pinned) {
            // The offending node is not unhealthy-marked here; RpcNodeHealthService owns node state
            // (K5 verifies eth_chainId on every health round). Signing is refused regardless.
            throw refuse(unpinned, "CHAIN_ID_MISMATCH",
                    ChainIdMismatchException.mismatch(config.getIdentifier(), pinned, reported));
        }
        return new SigningContext(chainConfigId, config.getIdentifier(), pinned,
                config.getMaxFeePerGasWei() != null ? config.getMaxFeePerGasWei()
                        : settings.defaultMaxFeePerGasWei(),
                config.getMaxPriorityFeePerGasWei() != null ? config.getMaxPriorityFeePerGasWei()
                        : settings.defaultMaxPriorityFeePerGasWei());
    }

    /** Logs, counts and audits a hard pre-signing refusal, then returns the exception to throw. */
    private <E extends RuntimeException> E refuse(SigningContext ctx, String kind, E exception) {
        log.warn("EVM submission refused on {} ({}): {}", ctx.identifier(), kind, exception.getMessage());
        if (meters != null) {
            meters.counter("registerwerk.tx.submission.refused", "kind", kind, "chain", ctx.identifier())
                    .increment();
        }
        if (eventPublisher != null) {
            Map<String, Object> details = Map.of("chain", String.valueOf(ctx.identifier()),
                    "reason", String.valueOf(exception.getMessage()));
            TxSubmissionRefusedEvent event = new TxSubmissionRefusedEvent(ctx.chainConfigId(), kind, details);
            try {
                // REQUIRES_NEW: the caller's transaction is about to roll back, which would discard
                // an AFTER_COMMIT audit event published inside it.
                if (isolatedTransactions != null) {
                    isolatedTransactions.run(() -> eventPublisher.publishEvent(event));
                } else {
                    eventPublisher.publishEvent(event);
                }
            } catch (RuntimeException e) {
                log.error("Could not record refusal audit event {} for {}: {}", kind, ctx.identifier(),
                        e.getMessage());
            }
        }
        return exception;
    }

    /**
     * Permit FIRST, transaction second: the public submit/send/deploy methods are deliberately not
     * {@code @Transactional}. Waiters must queue on the semaphore without holding a pooled connection
     * (a method-level transaction would have begun - and with a non-lazy pool taken - its connection
     * before the permit was acquired, so a burst of waiters could starve the running holders). The body
     * then runs in its own transaction (joining a caller's transaction if there is one, in which case the
     * caller's connection is the caller's responsibility).
     */
    private <T> T withImmediateSlot(String operation, Supplier<T> body) {
        boolean acquired;
        try {
            acquired = immediateSlots.tryAcquire(settings.acquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientChainException("Interrupted while waiting for an immediate EVM " + operation
                    + " slot");
        }
        if (!acquired) {
            if (meters != null) {
                meters.counter("registerwerk.tx.immediate.rejected", "operation", operation).increment();
            }
            throw new TransientChainException("All " + settings.immediateSubmitPermits()
                    + " immediate EVM submission slots are busy; retry shortly");
        }
        try {
            return transactionManager == null
                    ? body.get()
                    : new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                            .execute(status -> body.get());
        } finally {
            immediateSlots.release();
        }
    }

    /**
     * Registers a direct send's nonce and hash with the coordinator before it is broadcast (H10), so that lease
     * repair can never hand the nonce out again while the transaction is alive.
     */
    private void registerDirect(long chainId, String sender, BigInteger nonce, byte[] signed, String kind) {
        nonceCoordinator.registerDirectSubmission(chainId, sender, nonce, Numeric.toHexString(Hash.sha3(signed)), kind);
    }

    /**
     * The chain reading handed to the {@link NonceCoordinator}: the ordinary failover read for
     * {@code max(lease, chain)}, plus what lease repair needs before it may reuse a nonce (H10) - the pending
     * count of <em>every</em> routable node and whether any node still knows a given transaction.
     */
    NonceCoordinator.ChainNonceSource nonceSource(Web3j web3j, String identifier, String address) {
        return NonceCoordinator.ChainNonceSource.of(
                () -> nonce(web3j, address),
                () -> authoritativePendingNonce(identifier, address),
                hash -> anyNodeKnows(identifier, hash));
    }

    /**
     * Highest {@code eth_getTransactionCount(PENDING)} over all routable nodes of the chain (healthy or not: a node
     * that is down may be the one that holds the transaction). Empty when the chain has no node pool or any node
     * fails to answer - the caller then refuses to repair.
     */
    Optional<BigInteger> authoritativePendingNonce(String identifier, String address) {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = clientRegistry.evmNodeClients(identifier);
        if (nodes.isEmpty()) return Optional.empty();
        BigInteger highest = null;
        for (BlockchainClientRegistry.EvmNodeClient node : nodes) {
            try {
                EthGetTransactionCount cnt = node.client().ethGetTransactionCount(
                        address, DefaultBlockParameterName.PENDING).send();
                if (cnt.hasError() || cnt.getTransactionCount() == null) return Optional.empty();
                highest = highest == null ? cnt.getTransactionCount() : highest.max(cnt.getTransactionCount());
            } catch (Exception e) {
                log.debug("Pending-nonce read from node {} of {} failed: {}", node.nodeId(), identifier, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.ofNullable(highest);
    }

    /** True when any routable node knows {@code txHash} (pending or mined); true as well when it cannot tell. */
    boolean anyNodeKnows(String identifier, String txHash) {
        List<BlockchainClientRegistry.EvmNodeClient> nodes = clientRegistry.evmNodeClients(identifier);
        if (nodes.isEmpty()) return true;
        for (BlockchainClientRegistry.EvmNodeClient node : nodes) {
            try {
                var answer = node.client().ethGetTransactionByHash(txHash).send();
                if (answer.hasError() || answer.getTransaction().isPresent()) return true;
            } catch (Exception e) {
                log.debug("Transaction lookup of {} on node {} failed: {}", txHash, node.nodeId(), e.getMessage());
                return true;
            }
        }
        return false;
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
