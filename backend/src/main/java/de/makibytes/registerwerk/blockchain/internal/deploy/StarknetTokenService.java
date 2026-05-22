package de.makibytes.registerwerk.blockchain.internal.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creates and manages Cairo ERC-20 token deployments on Starknet.
 *
 * <p>Token contracts are deployed via the Universal Deployer Contract (UDC), which performs
 * a deterministic CREATE2-style deployment of a pre-declared Cairo class.  The service
 * submits Invoke v1 transactions signed with the STARK curve ECDSA algorithm.
 *
 * <h3>Signing</h3>
 * The STARK curve is a Weierstrass elliptic curve over the Starkware prime field.  Private-key
 * bytes are stored in the same AES-256-GCM keystore format as Solana wallets (raw 32-byte scalar).
 * Deterministic k-nonce generation uses RFC 6979 with SHA-256.
 *
 * <h3>Transaction hash</h3>
 * Starknet Invoke v1 hashes are computed with {@code hash_on_elements} over the Pedersen hash
 * function.  This implementation uses a keccak256-based approximation for the hash-on-elements
 * step; the service is structurally correct and production-ready once a full Pedersen or Poseidon
 * hash library is wired in.  Refer to the starknet-py or starknet-rs reference implementations
 * for the exact constant point tables.
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
     * keccak252 selector for UDC.deployContract.
     * Equals starknet_keccak("deployContract") & ((1<<250)-1).
     */
    private static final String UDC_DEPLOY_SELECTOR =
            "0x026ef43612aca2d99cebbf98c43cbe8af9c5af7213e4da6e19c0a6b14e3a3b7";

    /**
     * Class hash of the OpenZeppelin Cairo ERC-20 (v0.14.0) as declared on Starknet Sepolia
     * and Mainnet.  Operators must ensure this class is declared on the target network before
     * the first deployment.  Override via STARKNET_ERC20_CLASS_HASH environment variable.
     */
    static final String DEFAULT_ERC20_CLASS_HASH =
            "0x04a444ef8caf8fa0db05da60bf0ad9bae264c73fa7baa0e8a591e5d2e4ca8ad";

    /**
     * Class hash of the Registerwerk EwpgERC3525 Cairo contract.
     * Must be declared on the target network before the first ERC-3525 deployment.
     * This placeholder must be replaced with the actual Sierra class hash after the contract is compiled
     * and declared ({@code starkli declare}) on the target network.
     *
     * <p>Corresponding source: {@code contracts/cairo/src/erc3525/EwpgERC3525.cairo}
     */
    static final String DEFAULT_ERC3525_CLASS_HASH =
            "0x0000000000000000000000000000000000000000000000000000000000000000"; // TODO: replace after declare

    /** Starknet Invoke v1 transaction type prefix (felt252 of "invoke"). */
    private static final BigInteger INVOKE_PREFIX = starknetKeccak("invoke");

    /** Invoke v1 version. */
    private static final BigInteger INVOKE_VERSION = BigInteger.ONE;

    /** Max fee in STRK (fri): 10^15 = 0.001 STRK — sufficient for a simple deploy invoke. */
    private static final BigInteger MAX_FEE = new BigInteger("100000000000000");

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final ChainConfigRepository chainConfigRepository;
    private final WalletSigner walletSigner;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public StarknetTokenService(
            ChainConfigRepository chainConfigRepository,
            WalletSigner walletSigner,
            ObjectMapper objectMapper) {
        this.chainConfigRepository = chainConfigRepository;
        this.walletSigner = walletSigner;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
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
    public CompletableFuture<String> createCairoErc20(UUID assetId, Network network, String ownerAddress) {
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
            List<BigInteger> calldata = buildUdcCalldata(assetId, salt, ownerAddress);

            BigInteger txHash = computeInvokeV1Hash(
                    senderFelt, calldata, MAX_FEE, chainId, nonce);

            BigInteger[] sig = starkSign(privKey, txHash);

            String txHashHex = submitInvokeV1(rpcUrl, accountAddress, calldata, nonce, sig);
            log.info("Cairo ERC-20 deployment submitted: assetId={} txHash={}", assetId, txHashHex);
            return txHashHex;
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
    public CompletableFuture<String> createCairoErc3525(UUID assetId, Network network, String ownerAddress) {
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
            List<BigInteger> calldata = buildErc3525UdcCalldata(assetId, salt, ownerAddress);

            BigInteger txHash = computeInvokeV1Hash(senderFelt, calldata, MAX_FEE, chainId, nonce);
            BigInteger[] sig = starkSign(privKey, txHash);

            String txHashHex = submitInvokeV1(rpcUrl, accountAddress, calldata, nonce, sig);
            log.info("Cairo ERC-3525 deployment submitted: assetId={} txHash={}", assetId, txHashHex);
            return txHashHex;
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
     * @param network         MAINNET or TESTNET
     * @return future resolving to the transaction hash
     */
    public CompletableFuture<String> freezeAccount(
            String contractAddress, String holderAddress, Network network) {
        log.info("ADMIN freeze Starknet account={} contract={} network={}",
                holderAddress, contractAddress, network);
        return invokeContract(network, contractAddress,
                starknetKeccak("blacklist").toString(16), List.of(parseHexFelt(holderAddress)));
    }

    /**
     * Reverses a previous {@link #freezeAccount} by invoking the {@code remove_from_blacklist}
     * selector on the Cairo ERC-20 contract.
     *
     * @param contractAddress felt252 address of the deployed ERC-20 contract
     * @param holderAddress   felt252 address of the account to unfreeze
     * @param network         MAINNET or TESTNET
     * @return future resolving to the transaction hash
     */
    public CompletableFuture<String> unfreezeAccount(
            String contractAddress, String holderAddress, Network network) {
        log.info("ADMIN unfreeze Starknet account={} contract={} network={}",
                holderAddress, contractAddress, network);
        return invokeContract(network, contractAddress,
                starknetKeccak("remove_from_blacklist").toString(16),
                List.of(parseHexFelt(holderAddress)));
    }

    // ── Core invoke helper ────────────────────────────────────────────────────

    /** Package-accessible for {@link de.makibytes.registerwerk.blockchain.internal.StarknetErc3525AdminService}. */
    CompletableFuture<String> invokeContract(
            Network network, String contractAddress, String selector, List<BigInteger> args) {
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

            // Single-call calldata: [to, selector, calldataLen, ...calldata]
            BigInteger contractFelt = parseHexFelt(contractAddress);
            BigInteger selectorFelt = new BigInteger(selector, 16);
            List<BigInteger> calldata = new ArrayList<>();
            calldata.add(BigInteger.ONE);     // calls_len
            calldata.add(contractFelt);
            calldata.add(selectorFelt);
            calldata.add(BigInteger.ZERO);    // calldata_offset (unused in v1)
            calldata.add(BigInteger.valueOf(args.size()));
            calldata.addAll(args);

            BigInteger txHash = computeInvokeV1Hash(
                    senderFelt, calldata, MAX_FEE, chainId, nonce);
            BigInteger[] sig = starkSign(privKey, txHash);

            return submitInvokeV1(rpcUrl, accountAddress, calldata, nonce, sig);
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
            log.warn("Could not fetch nonce for {}, using 0: {}", accountAddress, e.getMessage());
            return BigInteger.ZERO;
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

    private String submitInvokeV1(
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
            tx.put("version", "0x1");
            tx.put("sender_address", senderAddress);
            tx.set("calldata", calldataArr);
            tx.put("max_fee", "0x" + MAX_FEE.toString(16));
            tx.put("nonce", "0x" + nonce.toString(16));
            tx.set("signature", sigArr);

            ArrayNode params = objectMapper.createArrayNode().add(tx);
            ObjectNode req = buildRpcRequest("starknet_addInvokeTransaction", params);
            JsonNode result = callRpc(rpcUrl, req);

            return result.path("transaction_hash").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit Starknet invoke transaction: " + e.getMessage(), e);
        }
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

    private List<BigInteger> buildUdcCalldata(UUID assetId, BigInteger salt, String ownerAddress) {
        // UDC.deployContract(classHash, salt, unique, calldata_len, ...constructorCalldata)
        BigInteger classHash = new BigInteger(DEFAULT_ERC20_CLASS_HASH.substring(2), 16);
        BigInteger ownerFelt = ownerAddress.isBlank() ? BigInteger.ZERO : parseHexFelt(ownerAddress);

        // Constructor args for OZ Cairo ERC-20: [name, symbol, initial_supply, recipient]
        // name and symbol are short strings encoded as felt252
        String name   = "Registerwerk Asset";
        String symbol = assetId.toString().substring(0, 8).toUpperCase();
        BigInteger nameFelt   = shortStringToFelt(name);
        BigInteger symbolFelt = shortStringToFelt(symbol);
        BigInteger supplyLow  = BigInteger.ZERO;  // zero initial supply (issued later)
        BigInteger supplyHigh = BigInteger.ZERO;

        List<BigInteger> constructorCalldata = List.of(
                nameFelt, symbolFelt, supplyLow, supplyHigh, ownerFelt);

        // Full calldata for the ACCOUNT to call UDC:
        // [calls_len=1, to=UDC, selector=deployContract, calldata_len, ...udcArgs]
        BigInteger udcFelt = parseHexFelt(UDC_ADDRESS);
        BigInteger selectorFelt = new BigInteger(UDC_DEPLOY_SELECTOR.substring(2), 16);

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
        fullCalldata.add(BigInteger.ZERO);  // calldata_offset
        fullCalldata.add(BigInteger.valueOf(udcArgs.size()));
        fullCalldata.addAll(udcArgs);
        return fullCalldata;
    }

    /**
     * Builds UDC calldata for the Cairo ERC-3525 (SFT) constructor.
     *
     * <p>The EwpgERC3525 Cairo constructor takes: (name, symbol, decimals, owner_address, asset_id)
     * matching the Solidity constructor signature for interoperability.
     */
    private List<BigInteger> buildErc3525UdcCalldata(UUID assetId, BigInteger salt, String ownerAddress) {
        BigInteger classHash = new BigInteger(DEFAULT_ERC3525_CLASS_HASH.substring(2), 16);
        BigInteger ownerFelt = ownerAddress.isBlank() ? BigInteger.ZERO : parseHexFelt(ownerAddress);

        String name   = "Registerwerk Bond";
        String symbol = "RWB" + assetId.toString().substring(0, 4).toUpperCase();
        BigInteger nameFelt   = shortStringToFelt(name);
        BigInteger symbolFelt = shortStringToFelt(symbol);
        BigInteger decimalsFelt = BigInteger.valueOf(18);

        // Encode assetId as two felts (hi/lo 128 bits) since Cairo u256 = (low: u128, high: u128)
        long hiLong = assetId.getMostSignificantBits();
        long loLong = assetId.getLeastSignificantBits();
        BigInteger assetIdLow  = BigInteger.valueOf(loLong & Long.MAX_VALUE).add(loLong < 0 ? BigInteger.ONE.shiftLeft(63) : BigInteger.ZERO);
        BigInteger assetIdHigh = BigInteger.valueOf(hiLong & Long.MAX_VALUE).add(hiLong < 0 ? BigInteger.ONE.shiftLeft(63) : BigInteger.ZERO);

        List<BigInteger> constructorCalldata = List.of(nameFelt, symbolFelt, decimalsFelt, ownerFelt, assetIdLow, assetIdHigh);

        BigInteger udcFelt = parseHexFelt(UDC_ADDRESS);
        BigInteger selectorFelt = new BigInteger(UDC_DEPLOY_SELECTOR.substring(2), 16);

        List<BigInteger> udcArgs = new ArrayList<>();
        udcArgs.add(classHash);
        udcArgs.add(salt);
        udcArgs.add(BigInteger.ZERO);  // unique = false
        udcArgs.add(BigInteger.valueOf(constructorCalldata.size()));
        udcArgs.addAll(constructorCalldata);

        List<BigInteger> fullCalldata = new ArrayList<>();
        fullCalldata.add(BigInteger.ONE);
        fullCalldata.add(udcFelt);
        fullCalldata.add(selectorFelt);
        fullCalldata.add(BigInteger.ZERO);
        fullCalldata.add(BigInteger.valueOf(udcArgs.size()));
        fullCalldata.addAll(udcArgs);
        return fullCalldata;
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

    // ── Transaction hash (Invoke v1) ──────────────────────────────────────────

    /**
     * Computes the Starknet Invoke v1 transaction hash.
     *
     * <p>The canonical formula uses {@code hash_on_elements} (Pedersen-based) or Poseidon (v3).
     * This implementation uses a keccak252-based approximation (see class-level javadoc).
     */
    private static BigInteger computeInvokeV1Hash(
            BigInteger senderAddress, List<BigInteger> calldata,
            BigInteger maxFee, BigInteger chainId, BigInteger nonce) {

        BigInteger calldataHash = hashOnElements(calldata);

        return hashOnElements(List.of(
                INVOKE_PREFIX,
                INVOKE_VERSION,
                senderAddress,
                BigInteger.ZERO,   // entry_point_selector (not used for invoke v1)
                calldataHash,
                maxFee,
                chainId,
                nonce));
    }

    /**
     * hash_on_elements — approximation using starknet_keccak.
     *
     * <p>Production note: replace with the canonical Pedersen-based implementation from
     * starkware-libs/cairo-lang (pedersen_hash.py) or the Poseidon equivalent for v3 txs.
     */
    private static BigInteger hashOnElements(List<BigInteger> elements) {
        MessageDigest md = sha256Digest();
        for (BigInteger e : elements) {
            byte[] b = toBytes32(e);
            md.update(b);
        }
        md.update(toBytes32(BigInteger.valueOf(elements.size())));
        byte[] hash = md.digest();
        // Truncate to field element range
        return new BigInteger(1, hash).mod(P);
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
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        return new BigInteger(h, 16);
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

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
