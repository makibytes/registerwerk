package de.makibytes.registerwerk.blockchain.internal.deploy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creates and manages Cairo ERC-20 token deployments on Starknet.
 *
 * <p>Token contracts are deployed via the Universal Deployer Contract (UDC), which performs
 * a deterministic CREATE2-style deployment of a pre-declared Cairo class.  The service
 * submits Invoke <b>v3</b> transactions (SNIP-8) signed with the STARK curve ECDSA algorithm —
 * Starknet 0.14 removed v0–v2 support entirely, so v3 with STRK-denominated resource bounds
 * is the only transaction version current sequencers accept.
 *
 * <h3>Signing</h3>
 * The STARK curve is a Weierstrass elliptic curve over the Starkware prime field.  Private-key
 * bytes are stored in the same AES-256-GCM keystore format as Solana wallets (raw 32-byte scalar).
 * Deterministic k-nonce generation uses RFC 6979 with SHA-256.
 *
 * <h3>Transaction hash</h3>
 * Invoke v3 hashes are computed with {@code poseidon_hash_many} (SNIP-8) — see
 * {@link StarkPoseidonHash}, pinned by unit test against independent starknet-rs vectors; the
 * full v3 layout (fee-field sub-hash, DA-mode packing) is pinned by
 * {@code StarknetInvokeV3HashTest} against a starknet.py reference vector.  The Pedersen hash
 * ({@link StarkPedersenHash}) remains in use for the deterministic UDC contract-address
 * precomputation ({@code compute_contract_address} is Pedersen-based in every protocol
 * version, including 0.14).
 *
 * <h3>Regulatory admin controls</h3>
 * <ul>
 *   <li>{@link #freezeAccount} — invokes the {@code pause} / {@code blacklist} selector on the
 *       deployed Cairo ERC-20 contract (eWpG §17, MiCAR Art. 36)</li>
 *   <li>{@link #unfreezeAccount} — reverses a previous freeze</li>
 * </ul>
 */
@Service
public class StarknetTokenService {

    private static final Logger log = LoggerFactory.getLogger(StarknetTokenService.class);

    /**
     * Result of a Cairo token deployment. {@code contractAddress} is the UDC-precomputed
     * felt address (see {@link #computeUdcContractAddress}) — known and persisted at
     * submission time, unlike EVM where the address is only known once the tx is mined.
     */
    public record StarknetDeployment(String txHash, String contractAddress) {}

    // ── STARK curve constants ────────────────────────────────────────────────

    /** Field prime: 2^251 + 17·2^192 + 1 */
    private static final BigInteger P = new BigInteger(
            "800000000000011000000000000000000000000000000000000000000000001", 16);

    /** Curve order N (number of points on the STARK curve). */
    private static final BigInteger N = new BigInteger(
            "800000000000010ffffffffffffffffb781126dcae7b2321e66a241adc64d2f", 16);

    /** Generator point G (affine coordinates). */
    private static final BigInteger GX = new BigInteger(
            "1ef15c18599971b7beced415a40f0c7deacfd9b0d1819e03d723d8bc943cfca", 16);
    private static final BigInteger GY = new BigInteger(
            "5668060aa49730b7be4801df46ec62de53ecd11abe43a32873000c36e8dc1f", 16);

    /** Weierstrass curve coefficient alpha (y² = x³ + alpha·x + beta). */
    private static final BigInteger ALPHA = BigInteger.ONE;

    // ── Starknet protocol constants ──────────────────────────────────────────

    /**
     * Universal Deployer Contract address on Starknet (both Mainnet and Sepolia).
     * Source: https://docs.starknet.io/tools/udc/
     */
    private static final String UDC_ADDRESS =
            "0x041a78e741e5af2fec34b695679bc6891742439f7afb8484ecd7766661ad02bf";

    /**
     * keccak252 selector for UDC.deployContract — derived via {@link #starknetKeccak(String)}
     * the same way every other entry-point selector in this file is (see {@link #invokeContract}),
     * rather than hardcoded, so it can't silently diverge from the actual selector.
     */
    private static final BigInteger UDC_DEPLOY_SELECTOR = starknetKeccak("deployContract");

    /**
     * Class hash of the Registerwerk EwpgERC20 Cairo contract ({@code contracts/cairo/src/erc20.cairo}).
     * Operators must ensure this class is declared on the target network before the first
     * deployment.  Override via the {@code registerwerk.chains.starknet.erc20-class-hash} property.
     */
    static final String DEFAULT_ERC20_CLASS_HASH =
            "0x0000000000000000000000000000000000000000000000000000000000000000"; // declare contracts/cairo EwpgERC20, then configure

    /**
     * Class hash of the Registerwerk EwpgERC3525 Cairo contract ({@code contracts/cairo/src/erc3525/EwpgERC3525.cairo}).
     * Must be declared ({@code starkli declare}) on the target network before the first ERC-3525
     * deployment. Override via the {@code registerwerk.chains.starknet.erc3525-class-hash}
     * property (same override mechanism as {@link #DEFAULT_ERC20_CLASS_HASH}).
     */
    static final String DEFAULT_ERC3525_CLASS_HASH =
            "0x0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * Starknet transaction type prefix: the ASCII bytes of "invoke" interpreted as a
     * big-endian integer (0x696e766f6b65) — per the Starknet transaction-hash
     * specification. Not a keccak selector.
     */
    private static final BigInteger INVOKE_PREFIX = new BigInteger("696e766f6b65", 16);

    /** Invoke v3 — the only version Starknet ≥ 0.14 sequencers accept. */
    private static final BigInteger INVOKE_VERSION = BigInteger.valueOf(3);

    /** One resource bound of an Invoke v3 transaction: name is "L1_GAS", "L2_GAS" or "L1_DATA". */
    record ResourceBound(String name, BigInteger maxAmount, BigInteger maxPricePerUnit) {

        /** SNIP-8 packing: {@code name << 192 | max_amount << 128 | max_price_per_unit}. */
        BigInteger toFelt() {
            BigInteger nameFelt = new BigInteger(1, name.getBytes(StandardCharsets.US_ASCII));
            return nameFelt.shiftLeft(128 + 64).add(maxAmount.shiftLeft(128)).add(maxPricePerUnit);
        }
    }

    /**
     * Static worst-case resource bounds (fri = 10⁻¹⁸ STRK). These are caps, not charges —
     * the sequencer bills actual consumption; a bound only rejects the transaction if
     * execution would exceed it. Sized for a UDC deploy or admin invoke: no L1 gas
     * (no L2→L1 messages), up to 1 STRK of L2 computation, 0.001 STRK of L1 data
     * availability. Same spirit as the fixed {@code max_fee} the v1 client used.
     */
    private static final ResourceBound L1_GAS_BOUND =
            new ResourceBound("L1_GAS", BigInteger.ZERO, new BigInteger("10000000000000"));
    private static final ResourceBound L2_GAS_BOUND =
            new ResourceBound("L2_GAS", new BigInteger("50000000"), new BigInteger("20000000000"));
    private static final ResourceBound L1_DATA_GAS_BOUND =
            new ResourceBound("L1_DATA", BigInteger.valueOf(10_000), new BigInteger("100000000000"));

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final ChainConfigRepository chainConfigRepository;
    private final WalletSigner walletSigner;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String erc20ClassHash;
    private final String erc3525ClassHash;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public StarknetTokenService(
            ChainConfigRepository chainConfigRepository,
            WalletSigner walletSigner,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${registerwerk.chains.starknet.erc20-class-hash:" + DEFAULT_ERC20_CLASS_HASH + "}")
            String erc20ClassHash,
            @org.springframework.beans.factory.annotation.Value(
                    "${registerwerk.chains.starknet.erc3525-class-hash:" + DEFAULT_ERC3525_CLASS_HASH + "}")
            String erc3525ClassHash,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.chainConfigRepository = chainConfigRepository;
        this.walletSigner = walletSigner;
        this.objectMapper = objectMapper;
        this.erc20ClassHash = erc20ClassHash;
        this.erc3525ClassHash = erc3525ClassHash;
        this.eventPublisher = eventPublisher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Fails fast with a clear message when the Sierra class was never declared/configured. */
    private static BigInteger requireClassHash(String hash, String standard, String property) {
        BigInteger value = parseHexFelt(hash);
        if (value.signum() == 0) {
            throw new IllegalStateException(
                    "Starknet " + standard + " class hash is not configured. Compile and declare " +
                    "contracts/cairo (scarb build + starkli declare), then set " + property + ".");
        }
        return value;
    }

    // ── Token creation ────────────────────────────────────────────────────────

    /**
     * Deploys a new Cairo ERC-20 contract on the specified Starknet network.
     *
     * <p>Calls {@code UDC.deployContract(classHash, salt, unique=0, calldata)} where the
     * constructor calldata encodes the asset name, symbol, decimals, initial supply, and
     * the registry operator as the initial recipient.
     *
     * @param assetId      Registerwerk asset UUID (encoded in the contract name)
     * @param network      MAINNET or TESTNET (Sepolia)
     * @param ownerAddress felt252 account address that will own the minted supply
     * @return future resolving to the Starknet invoke transaction hash (0x-prefixed)
     */
    public CompletableFuture<StarknetDeployment> createCairoErc20(UUID assetId, Network network, String ownerAddress) {
        log.info("Creating Cairo ERC-20: assetId={}, network={}", assetId, network);

        return CompletableFuture.supplyAsync(() -> {
            ChainConfig chain = resolveChainConfig(network);
            String rpcUrl = chain.getRpcUrl();

            byte[] privateKeyBytes = walletSigner.rawPrivateKeyBytesForChain(chain.getId());
            String accountAddress = walletSigner.chainAddressForWallet(chain.getId());

            BigInteger privKey = new BigInteger(1, privateKeyBytes);
            BigInteger senderFelt = parseHexFelt(accountAddress);

            CompletableFuture<BigInteger> nonceFuture =
                    CompletableFuture.supplyAsync(() -> fetchNonce(rpcUrl, accountAddress));
            CompletableFuture<BigInteger> chainIdFuture =
                    CompletableFuture.supplyAsync(() -> fetchChainId(rpcUrl));
            BigInteger nonce = nonceFuture.join();
            BigInteger chainId = chainIdFuture.join();

            // Build UDC deployContract calldata
            BigInteger salt = generateSalt(assetId);
            UdcCallBuild udcCall = buildUdcCalldata(assetId, salt, ownerAddress);

            BigInteger txHash = computeInvokeV3Hash(
                    senderFelt, udcCall.invokeCalldata(), chainId, nonce,
                    L1_GAS_BOUND, L2_GAS_BOUND, L1_DATA_GAS_BOUND);

            BigInteger[] sig = starkSign(privKey, txHash);

            String txHashHex = submitInvokeV3(rpcUrl, accountAddress, udcCall.invokeCalldata(), nonce, sig);
            String contractAddress = "0x" + udcCall.contractAddress().toString(16);
            log.info("Cairo ERC-20 deployment submitted: assetId={} txHash={} contractAddress={}",
                    assetId, txHashHex, contractAddress);
            return new StarknetDeployment(txHashHex, contractAddress);
        });
    }

    /**
     * Creates a Cairo ERC-3525 (Semi-Fungible Token) deployment on Starknet via UDC.
     *
     * <p>The ERC-3525 Cairo contract is the Carbonable implementation (Apache-2.0), compiled to Sierra
     * and declared under a fixed class hash. The class hash must be declared on the target network before
     * this method is called. Configure it via {@code registerwerk.chains.starknet.erc3525-class-hash}.
     *
     * <p>Like {@link #createCairoErc20}, this uses the UDC {@code deployContract} selector with the
     * ERC-3525 class hash. The token is deployed with the registry wallet as the initial owner.
     *
     * @param assetId      Registerwerk asset UUID
     * @param network      MAINNET or TESTNET
     * @param ownerAddress felt252 address of the initial owner
     * @return future resolving to the UDC deployment transaction hash
     */
    public CompletableFuture<StarknetDeployment> createCairoErc3525(UUID assetId, Network network, String ownerAddress) {
        log.info("Creating Cairo ERC-3525 (SFT): assetId={}, network={}", assetId, network);

        return CompletableFuture.supplyAsync(() -> {
            ChainConfig chain = resolveChainConfig(network);
            String rpcUrl = chain.getRpcUrl();

            byte[] privateKeyBytes = walletSigner.rawPrivateKeyBytesForChain(chain.getId());
            String accountAddress = walletSigner.chainAddressForWallet(chain.getId());

            BigInteger privKey = new BigInteger(1, privateKeyBytes);
            BigInteger senderFelt = parseHexFelt(accountAddress);

            CompletableFuture<BigInteger> nonceFuture =
                    CompletableFuture.supplyAsync(() -> fetchNonce(rpcUrl, accountAddress));
            CompletableFuture<BigInteger> chainIdFuture =
                    CompletableFuture.supplyAsync(() -> fetchChainId(rpcUrl));
            BigInteger nonce = nonceFuture.join();
            BigInteger chainId = chainIdFuture.join();

            // ERC-3525 calldata uses the same UDC pattern as ERC-20
            BigInteger salt = generateSalt(assetId);
            UdcCallBuild udcCall = buildErc3525UdcCalldata(assetId, salt, ownerAddress);

            BigInteger txHash = computeInvokeV3Hash(senderFelt, udcCall.invokeCalldata(), chainId, nonce,
                    L1_GAS_BOUND, L2_GAS_BOUND, L1_DATA_GAS_BOUND);
            BigInteger[] sig = starkSign(privKey, txHash);

            String txHashHex = submitInvokeV3(rpcUrl, accountAddress, udcCall.invokeCalldata(), nonce, sig);
            String contractAddress = "0x" + udcCall.contractAddress().toString(16);
            log.info("Cairo ERC-3525 deployment submitted: assetId={} txHash={} contractAddress={}",
                    assetId, txHashHex, contractAddress);
            return new StarknetDeployment(txHashHex, contractAddress);
        });
    }

    // ── Regulatory admin controls ─────────────────────────────────────────────

    /**
     * Invokes the {@code blacklist} (freeze) function on the deployed Cairo ERC-20, blocking
     * the holder from transferring tokens.
     *
     * <p>Legal basis: eWpG §17 (AWG sanctions), GwG §40 (AML), MiCAR Art. 36.
     *
     * @param contractAddress felt252 address of the deployed ERC-20 contract
     * @param holderAddress   felt252 address of the account to freeze
     * @param reason          short-string reason recorded on-chain alongside the freeze
     * @param network         MAINNET or TESTNET
     * @param deploymentId    Registerwerk deployment ID, for the audit record
     * @param actorId         operator who triggered this freeze
     * @param actorRole       the triggering actor's role, for the audit record
     * @return future resolving to the transaction hash
     */
    public CompletableFuture<String> freezeAccount(
            String contractAddress, String holderAddress, String reason, Network network,
            UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN freeze Starknet account={} contract={} network={}",
                holderAddress, contractAddress, network);
        // Cairo's freeze_address(account, reason) takes two felts; unfreeze_address takes one.
        return invokeContract(network, contractAddress, "freeze_address",
                        List.of(parseHexFelt(holderAddress), shortStringToFelt(reason)))
                .thenApply(txHash -> {
                    publishAdminAction(deploymentId, "freezeAccount",
                            Map.of("holderAddress", holderAddress, "reason", reason), actorId, actorRole);
                    return txHash;
                });
    }

    /**
     * Reverses a previous {@link #freezeAccount} by invoking the {@code remove_from_blacklist}
     * selector on the Cairo ERC-20 contract.
     *
     * @param contractAddress felt252 address of the deployed ERC-20 contract
     * @param holderAddress   felt252 address of the account to unfreeze
     * @param network         MAINNET or TESTNET
     * @param deploymentId    Registerwerk deployment ID, for the audit record
     * @param actorId         operator who triggered this unfreeze
     * @param actorRole       the triggering actor's role, for the audit record
     * @return future resolving to the transaction hash
     */
    public CompletableFuture<String> unfreezeAccount(
            String contractAddress, String holderAddress, Network network,
            UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN unfreeze Starknet account={} contract={} network={}",
                holderAddress, contractAddress, network);
        return invokeContract(network, contractAddress, "unfreeze_address", List.of(parseHexFelt(holderAddress)))
                .thenApply(txHash -> {
                    publishAdminAction(deploymentId, "unfreezeAccount",
                            Map.of("holderAddress", holderAddress), actorId, actorRole);
                    return txHash;
                });
    }

    /** Publishes the same {@code TokenAdminActionEvent} every EVM admin service uses, so freeze
     *  and unfreeze reach the audit trail. */
    private void publishAdminAction(UUID deploymentId, String methodName, Map<String, Object> params,
                                     UUID actorId, String actorRole) {
        eventPublisher.publishEvent(new de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent(
                deploymentId, methodName, actorId, actorRole, params));
    }

    // ── Core invoke helper ────────────────────────────────────────────────────

    /**
     * Package-accessible for {@link StarknetErc3525AdminService}.
     *
     * @param entryPointName the Cairo external function name (e.g. {@code pause_slot});
     *                       the selector is derived via starknet_keccak internally —
     *                       callers must never pass pre-hashed hex here
     */
    CompletableFuture<String> invokeContract(
            Network network, String contractAddress, String entryPointName, List<BigInteger> args) {
        return CompletableFuture.supplyAsync(() -> {
            ChainConfig chain = resolveChainConfig(network);
            String rpcUrl = chain.getRpcUrl();

            byte[] privateKeyBytes = walletSigner.rawPrivateKeyBytesForChain(chain.getId());
            String accountAddress = walletSigner.chainAddressForWallet(chain.getId());
            BigInteger privKey = new BigInteger(1, privateKeyBytes);
            BigInteger senderFelt = parseHexFelt(accountAddress);

            CompletableFuture<BigInteger> nonceFuture =
                    CompletableFuture.supplyAsync(() -> fetchNonce(rpcUrl, accountAddress));
            CompletableFuture<BigInteger> chainIdFuture =
                    CompletableFuture.supplyAsync(() -> fetchChainId(rpcUrl));
            BigInteger nonce = nonceFuture.join();
            BigInteger chainId = chainIdFuture.join();

            // SNIP-6 (Cairo 1 account) single-call calldata:
            // [calls_len, to, selector, calldata_len, ...calldata]
            BigInteger contractFelt = parseHexFelt(contractAddress);
            BigInteger selectorFelt = starknetKeccak(entryPointName);
            List<BigInteger> calldata = new ArrayList<>();
            calldata.add(BigInteger.ONE);     // calls_len
            calldata.add(contractFelt);
            calldata.add(selectorFelt);
            calldata.add(BigInteger.valueOf(args.size()));
            calldata.addAll(args);

            BigInteger txHash = computeInvokeV3Hash(
                    senderFelt, calldata, chainId, nonce,
                    L1_GAS_BOUND, L2_GAS_BOUND, L1_DATA_GAS_BOUND);
            BigInteger[] sig = starkSign(privKey, txHash);

            return submitInvokeV3(rpcUrl, accountAddress, calldata, nonce, sig);
        });
    }

    // ── RPC helpers ───────────────────────────────────────────────────────────

    private BigInteger fetchNonce(String rpcUrl, String accountAddress) {
        try {
            ObjectNode req = buildRpcRequest("starknet_getNonce",
                    objectMapper.createArrayNode()
                            .add("pending")
                            .add(accountAddress));
            JsonNode result = callRpc(rpcUrl, req);
            return parseHexFelt(result.asText());
        } catch (Exception e) {
            // Fail fast: submitting with a guessed nonce yields opaque sequencer
            // rejections; the RPC error is the actionable signal.
            throw new RuntimeException("Could not fetch Starknet nonce for "
                    + accountAddress + ": " + e.getMessage(), e);
        }
    }

    private BigInteger fetchChainId(String rpcUrl) {
        try {
            ObjectNode req = buildRpcRequest("starknet_chainId",
                    objectMapper.createArrayNode());
            JsonNode result = callRpc(rpcUrl, req);
            return parseHexFelt(result.asText());
        } catch (Exception e) {
            // SN_SEPOLIA chain_id = 0x534e5f5345504f4c4941
            log.warn("Could not fetch chainId, using SN_SEPOLIA: {}", e.getMessage());
            return new BigInteger("534e5f5345504f4c4941", 16);
        }
    }

    private String submitInvokeV3(
            String rpcUrl, String senderAddress, List<BigInteger> calldata,
            BigInteger nonce, BigInteger[] signature) {
        try {
            ArrayNode calldataArr = objectMapper.createArrayNode();
            calldata.forEach(f -> calldataArr.add("0x" + f.toString(16)));

            ArrayNode sigArr = objectMapper.createArrayNode();
            sigArr.add("0x" + signature[0].toString(16));
            sigArr.add("0x" + signature[1].toString(16));

            ObjectNode tx = objectMapper.createObjectNode();
            tx.put("type", "INVOKE");
            tx.put("version", "0x3");
            tx.put("sender_address", senderAddress);
            tx.set("calldata", calldataArr);
            tx.put("nonce", "0x" + nonce.toString(16));
            tx.set("signature", sigArr);
            ObjectNode bounds = objectMapper.createObjectNode();
            bounds.set("l1_gas", boundNode(L1_GAS_BOUND));
            bounds.set("l2_gas", boundNode(L2_GAS_BOUND));
            bounds.set("l1_data_gas", boundNode(L1_DATA_GAS_BOUND));
            tx.set("resource_bounds", bounds);
            tx.put("tip", "0x0");
            tx.set("paymaster_data", objectMapper.createArrayNode());
            tx.set("account_deployment_data", objectMapper.createArrayNode());
            tx.put("nonce_data_availability_mode", "L1");
            tx.put("fee_data_availability_mode", "L1");

            ArrayNode params = objectMapper.createArrayNode().add(tx);
            ObjectNode req = buildRpcRequest("starknet_addInvokeTransaction", params);
            JsonNode result = callRpc(rpcUrl, req);

            return result.path("transaction_hash").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit Starknet invoke transaction: " + e.getMessage(), e);
        }
    }

    private ObjectNode boundNode(ResourceBound bound) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("max_amount", "0x" + bound.maxAmount().toString(16));
        node.put("max_price_per_unit", "0x" + bound.maxPricePerUnit().toString(16));
        return node;
    }

    private JsonNode callRpc(String rpcUrl, ObjectNode request) throws Exception {
        String body = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(rpcUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new RuntimeException("Starknet RPC error: " + error);
        }
        return root.path("result");
    }

    private ObjectNode buildRpcRequest(String method, ArrayNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.set("params", params);
        req.put("id", 1);
        return req;
    }

    // ── Calldata builders ─────────────────────────────────────────────────────

    /**
     * Bundles the SNIP-6 {@code __execute__} calldata for a UDC deploy call together with the
     * address the UDC will deploy to, so callers don't have to recompute
     * {@link #computeUdcContractAddress} a second time from scratch.
     */
    private record UdcCallBuild(List<BigInteger> invokeCalldata, BigInteger contractAddress) {}

    private UdcCallBuild buildUdcCalldata(UUID assetId, BigInteger salt, String ownerAddress) {
        // UDC.deployContract(classHash, salt, unique, calldata_len, ...constructorCalldata)
        BigInteger classHash = requireClassHash(erc20ClassHash, "EwpgERC20",
                "registerwerk.chains.starknet.erc20-class-hash");
        BigInteger ownerFelt = ownerAddress.isBlank() ? BigInteger.ZERO : parseHexFelt(ownerAddress);

        // Constructor of contracts/cairo EwpgERC20: (name: felt252, symbol: felt252,
        // registry: ContractAddress). Supply is zero at deployment — issuance happens
        // later through registry-gated mint with whitelist + cap enforcement.
        String name   = "Registerwerk Asset";
        String symbol = assetId.toString().substring(0, 8).toUpperCase();
        BigInteger nameFelt   = shortStringToFelt(name);
        BigInteger symbolFelt = shortStringToFelt(symbol);

        List<BigInteger> constructorCalldata = List.of(nameFelt, symbolFelt, ownerFelt);
        BigInteger contractAddress = computeUdcContractAddress(salt, classHash, constructorCalldata);

        log.info("Precomputed Starknet ERC-20 address for asset {}: 0x{}",
                assetId, contractAddress.toString(16));

        return new UdcCallBuild(wrapUdcCall(classHash, salt, constructorCalldata), contractAddress);
    }

    /**
     * Wraps a UDC {@code deployContract(classHash, salt, unique = false, calldata)} call in
     * SNIP-6 (Cairo 1 account) {@code __execute__} calldata:
     * {@code [calls_len = 1, to = UDC, selector, calldata_len, ...udcArgs]}.
     */
    private static List<BigInteger> wrapUdcCall(
            BigInteger classHash, BigInteger salt, List<BigInteger> constructorCalldata) {
        BigInteger udcFelt = parseHexFelt(UDC_ADDRESS);
        BigInteger selectorFelt = UDC_DEPLOY_SELECTOR;

        List<BigInteger> udcArgs = new ArrayList<>();
        udcArgs.add(classHash);
        udcArgs.add(salt);
        udcArgs.add(BigInteger.ZERO);  // unique = false
        udcArgs.add(BigInteger.valueOf(constructorCalldata.size()));
        udcArgs.addAll(constructorCalldata);

        List<BigInteger> fullCalldata = new ArrayList<>();
        fullCalldata.add(BigInteger.ONE);   // calls_len
        fullCalldata.add(udcFelt);
        fullCalldata.add(selectorFelt);
        fullCalldata.add(BigInteger.valueOf(udcArgs.size()));
        fullCalldata.addAll(udcArgs);
        return fullCalldata;
    }

    /**
     * Builds UDC calldata for the Cairo ERC-3525 (SFT) constructor.
     *
     * <p>The EwpgERC3525 Cairo constructor takes
     * {@code (name: felt252, symbol: felt252, value_decimals: u8, registry_admin: ContractAddress,
     * asset_id: u256)} — six felts on the wire, since u256 serializes as (low, high).
     */
    private UdcCallBuild buildErc3525UdcCalldata(UUID assetId, BigInteger salt, String ownerAddress) {
        BigInteger classHash = requireClassHash(erc3525ClassHash, "EwpgERC3525",
                "registerwerk.chains.starknet.erc3525-class-hash");
        BigInteger ownerFelt = ownerAddress.isBlank() ? BigInteger.ZERO : parseHexFelt(ownerAddress);

        String name   = "Registerwerk Bond";
        String symbol = "RWB" + assetId.toString().substring(0, 4).toUpperCase();
        BigInteger nameFelt   = shortStringToFelt(name);
        BigInteger symbolFelt = shortStringToFelt(symbol);
        BigInteger decimalsFelt = BigInteger.valueOf(18);

        // Encode assetId as a Cairo u256 (low: u128, high: u128): combine the UUID into one
        // unsigned 128-bit BigInteger, then split with the same mask convention
        // StarknetErc3525AdminService.slotFelts() uses elsewhere in this module.
        byte[] assetIdBytes = new byte[16];
        long hiLong = assetId.getMostSignificantBits();
        long loLong = assetId.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) { assetIdBytes[8 + i] = (byte) (loLong & 0xFF); loLong >>= 8; }
        for (int i = 7; i >= 0; i--) { assetIdBytes[i]     = (byte) (hiLong & 0xFF); hiLong >>= 8; }
        BigInteger assetIdValue = new BigInteger(1, assetIdBytes);
        BigInteger mask128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        BigInteger assetIdLow  = assetIdValue.and(mask128);
        BigInteger assetIdHigh = assetIdValue.shiftRight(128).and(mask128);

        List<BigInteger> constructorCalldata = List.of(nameFelt, symbolFelt, decimalsFelt, ownerFelt, assetIdLow, assetIdHigh);
        BigInteger contractAddress = computeUdcContractAddress(salt, classHash, constructorCalldata);

        log.info("Precomputed Starknet ERC-3525 address for asset {}: 0x{}",
                assetId, contractAddress.toString(16));

        return new UdcCallBuild(wrapUdcCall(classHash, salt, constructorCalldata), contractAddress);
    }

    private BigInteger generateSalt(UUID assetId) {
        // Deterministic salt from the UUID — unique per asset, replayable
        byte[] uuidBytes = new byte[16];
        long hi = assetId.getMostSignificantBits();
        long lo = assetId.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) { uuidBytes[8 + i] = (byte)(lo & 0xFF); lo >>= 8; }
        for (int i = 7; i >= 0; i--) { uuidBytes[i]     = (byte)(hi & 0xFF); hi >>= 8; }
        return new BigInteger(1, uuidBytes).mod(N);
    }

    // ── Transaction hash (Invoke v3, SNIP-8) ─────────────────────────────────

    /**
     * Computes the Starknet Invoke v3 transaction hash per SNIP-8, with tip = 0, empty
     * paymaster / account-deployment data, and L1 data-availability for both nonce and fee
     * (the packed DA-modes felt is therefore 0). A sequencer recomputes exactly this hash
     * before verifying the signature — any deviation invalidates every transaction this
     * service submits. Package-visible so {@code StarknetInvokeV3HashTest} can pin it
     * against the starknet.py reference vector.
     */
    static BigInteger computeInvokeV3Hash(
            BigInteger senderAddress, List<BigInteger> calldata,
            BigInteger chainId, BigInteger nonce,
            ResourceBound l1Gas, ResourceBound l2Gas, ResourceBound l1DataGas) {

        BigInteger tip = BigInteger.ZERO;
        BigInteger feeFieldsHash = StarkPoseidonHash.hashMany(List.of(
                tip, l1Gas.toFelt(), l2Gas.toFelt(), l1DataGas.toFelt()));
        BigInteger emptyHash = StarkPoseidonHash.hashMany(List.of()); // paymaster / deployment data

        return StarkPoseidonHash.hashMany(List.of(
                INVOKE_PREFIX,
                INVOKE_VERSION,
                senderAddress,
                feeFieldsHash,
                emptyHash,          // paymaster_data
                chainId,
                nonce,
                BigInteger.ZERO,    // (nonce_da_mode << 32) | fee_da_mode — both L1 (0)
                emptyHash,          // account_deployment_data
                StarkPoseidonHash.hashMany(calldata)));
    }

    // ── Deterministic contract address ────────────────────────────────────────

    /** Pedersen prefix felt: ASCII "STARKNET_CONTRACT_ADDRESS". */
    private static final BigInteger CONTRACT_ADDRESS_PREFIX =
            new BigInteger(1, "STARKNET_CONTRACT_ADDRESS".getBytes(StandardCharsets.US_ASCII));

    /** Contract addresses live below 2^251 − 256. */
    private static final BigInteger ADDR_BOUND =
            BigInteger.ONE.shiftLeft(251).subtract(BigInteger.valueOf(256));

    /**
     * Precomputes the address the UDC will deploy to ({@code compute_contract_address},
     * Pedersen-based in every protocol version). With {@code unique = false} the deployer
     * felt is 0, so the address depends only on (salt, classHash, constructor calldata) —
     * the Starknet equivalent of the CREATE2 precomputation {@code AssetTokenFactory}
     * does on EVM chains. Logged at deploy time so operators know the token address
     * before the transaction is even confirmed.
     */
    static BigInteger computeUdcContractAddress(
            BigInteger salt, BigInteger classHash, List<BigInteger> constructorCalldata) {
        return StarkPedersenHash.hashOnElements(List.of(
                CONTRACT_ADDRESS_PREFIX,
                BigInteger.ZERO,   // deployer (UDC unique = false → origin-independent)
                salt,
                classHash,
                StarkPedersenHash.hashOnElements(constructorCalldata)))
                .mod(ADDR_BOUND);
    }

    // ── STARK ECDSA ───────────────────────────────────────────────────────────

    /**
     * Signs a message hash with the STARK curve ECDSA algorithm.
     *
     * <p>Uses RFC 6979 deterministic k generation with SHA-256 to prevent nonce reuse.
     *
     * @param privKey 32-byte STARK curve private key scalar
     * @param msgHash field element to sign
     * @return [r, s] signature pair
     */
    public static BigInteger[] starkSign(BigInteger privKey, BigInteger msgHash) {
        BigInteger k = rfc6979Nonce(privKey, msgHash);
        return starkSignWithK(privKey, msgHash, k);
    }

    public static BigInteger[] starkSignWithK(BigInteger privKey, BigInteger msgHash, BigInteger k) {
        BigInteger[] kG = ecMul(k, new BigInteger[]{GX, GY});
        BigInteger r = kG[0].mod(N);
        if (r.equals(BigInteger.ZERO)) {
            throw new IllegalStateException("STARK ECDSA: r == 0; choose a different k");
        }
        BigInteger kInv = k.modInverse(N);
        BigInteger s = kInv.multiply(msgHash.add(r.multiply(privKey))).mod(N);
        if (s.equals(BigInteger.ZERO)) {
            throw new IllegalStateException("STARK ECDSA: s == 0; choose a different k");
        }
        return new BigInteger[]{r, s};
    }

    /**
     * RFC 6979 deterministic nonce generation.
     * Uses HMAC-SHA256 over the private key and message hash to produce a deterministic k.
     */
    private static BigInteger rfc6979Nonce(BigInteger privKey, BigInteger msgHash) {
        byte[] x = toBytes32(privKey);
        byte[] h1 = toBytes32(msgHash);

        byte[] V = new byte[32];
        java.util.Arrays.fill(V, (byte) 0x01);
        byte[] K = new byte[32];
        java.util.Arrays.fill(K, (byte) 0x00);

        K = hmacSha256(K, concat(V, new byte[]{0x00}, x, h1));
        V = hmacSha256(K, V);
        K = hmacSha256(K, concat(V, new byte[]{0x01}, x, h1));
        V = hmacSha256(K, V);

        for (int attempt = 0; attempt < 100; attempt++) {
            V = hmacSha256(K, V);
            BigInteger k = new BigInteger(1, V).mod(N);
            if (k.compareTo(BigInteger.ONE) >= 0 && k.compareTo(N) < 0) {
                return k;
            }
            K = hmacSha256(K, concat(V, new byte[]{0x00}));
            V = hmacSha256(K, V);
        }
        throw new IllegalStateException("RFC 6979: failed to generate valid k after 100 attempts");
    }

    // ── EC arithmetic (Weierstrass, affine coordinates) ───────────────────────

    public static BigInteger[] ecAdd(BigInteger[] p1, BigInteger[] p2) {
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        if (p1[0].equals(p2[0])) {
            if (p1[1].equals(p2[1])) return ecDouble(p1);
            return null; // point at infinity
        }
        BigInteger lam = p2[1].subtract(p1[1])
                .multiply(p2[0].subtract(p1[0]).modInverse(P)).mod(P);
        BigInteger x = lam.pow(2).subtract(p1[0]).subtract(p2[0]).mod(P);
        BigInteger y = lam.multiply(p1[0].subtract(x)).subtract(p1[1]).mod(P);
        return new BigInteger[]{x.mod(P), y.mod(P)};
    }

    public static BigInteger[] ecDouble(BigInteger[] p) {
        if (p == null) return null;
        BigInteger lam = ALPHA.add(p[0].pow(2).multiply(BigInteger.valueOf(3)))
                .multiply(p[1].multiply(BigInteger.TWO).modInverse(P)).mod(P);
        BigInteger x = lam.pow(2).subtract(p[0].multiply(BigInteger.TWO)).mod(P);
        BigInteger y = lam.multiply(p[0].subtract(x)).subtract(p[1]).mod(P);
        return new BigInteger[]{x.mod(P), y.mod(P)};
    }

    public static BigInteger[] ecMul(BigInteger scalar, BigInteger[] point) {
        BigInteger[] result = null;
        BigInteger[] addend = point;
        scalar = scalar.mod(N);
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) {
                result = ecAdd(result, addend);
            }
            addend = ecDouble(addend);
            scalar = scalar.shiftRight(1);
        }
        return result;
    }

    // ── starknet_keccak ───────────────────────────────────────────────────────

    /**
     * starknet_keccak = keccak256(data) & ((1 << 250) − 1).
     * Used for computing function selectors and the "invoke" prefix felt252.
     */
    public static BigInteger starknetKeccak(String text) {
        return starknetKeccak(text.getBytes(StandardCharsets.UTF_8));
    }

    public static BigInteger starknetKeccak(byte[] data) {
        byte[] hash = keccak256(data);
        BigInteger result = new BigInteger(1, hash);
        return result.and(BigInteger.ONE.shiftLeft(250).subtract(BigInteger.ONE));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public static BigInteger parseHexFelt(String hex) {
        return de.makibytes.registerwerk.blockchain.api.StarknetFeltUtils.parseHexFelt(hex);
    }

    private static BigInteger shortStringToFelt(String s) {
        String truncated = s.length() > 31 ? s.substring(0, 31) : s;
        return new BigInteger(1, truncated.getBytes(StandardCharsets.US_ASCII));
    }

    /** Package-accessible wrapper for {@link StarknetErc3525AdminService}. */
    BigInteger shortStringToFeltPublic(String s) {
        return shortStringToFelt(s);
    }

    private ChainConfig resolveChainConfig(Network network) {
        ChainConfig.NetworkType netType = network == Network.MAINNET
                ? ChainConfig.NetworkType.MAINNET
                : ChainConfig.NetworkType.TESTNET;
        return chainConfigRepository
                .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET).stream()
                .filter(c -> c.getNetworkType() == netType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled Starknet chain config for " + network
                        + ". Add a Starknet node via the Operator Portal → Network Nodes."));
    }

    private static byte[] toBytes32(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) return raw;
        byte[] out = new byte[32];
        if (raw.length < 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            // strip leading sign byte (BigInteger may add one)
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] keccak256(byte[] data) {
        // Web3j's Hash.sha3 computes Keccak-256 (not SHA3-256)
        return org.web3j.crypto.Hash.sha3(data);
    }

}
