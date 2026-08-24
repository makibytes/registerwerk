package de.makibytes.registerwerk.blockchain.internal.deploy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import de.makibytes.registerwerk.finality.api.ChainSubmissionExecutor;

/**
 * Creates and administers Stellar assets (classic trustline-based tokens) via the Horizon REST API.
 *
 * <h3>Asset representation</h3>
 * A Stellar asset is identified by its <em>asset code</em> (up to 12 characters) and the
 * <em>issuer account</em> public key.  This service issues new assets by submitting a
 * {@code ManageData} transaction that records the Registerwerk asset ID in the issuer account
 * metadata, followed by an optional {@code Payment} transaction to distribute initial supply.
 *
 * <h3>Signing</h3>
 * Stellar uses Ed25519.  Private key seeds (32 bytes) are stored in the same AES-256-GCM
 * keystore format as Solana wallets.  Public key derivation and signing use BouncyCastle's
 * {@link Ed25519PrivateKeyParameters} / {@link Ed25519Signer}, which is available as a
 * transitive dependency of {@code spring-security-crypto}.
 *
 * <h3>XDR encoding</h3>
 * A minimal inline XDR writer handles the specific transaction types issued by this service.
 * Field types follow the Stellar XDR spec (SEP-0040 / stellar-core XDR definitions).
 *
 * <h3>Regulatory admin controls</h3>
 * <ul>
 *   <li>{@link #clawbackAsset} — submits a {@code Clawback} operation (SEP-0035 / CAP-0035)
 *       which lets the issuer reclaim tokens from a holder's account (eWpG §24, MiCAR Art. 36)</li>
 *   <li>{@link #setAuthorizationFlags} — submits {@code SetTrustLineFlags} (CAP-0018) to revoke
 *       or restore transfer authorisation for a specific trustline (AWG §17, GwG §40)</li>
 * </ul>
 */
@Service
public class StellarAssetService {

    private static final Logger log = LoggerFactory.getLogger(StellarAssetService.class);

    /**
     * Result of a Stellar asset issuance. {@code issuerAddress} is the issuing account's
     * G-address — known synchronously from the configured wallet before the transaction is
     * even built, unlike EVM where a contract address is only known once the tx is mined.
     */
    public record StellarDeployment(String txHash, String issuerAddress) {}

    // ── Stellar XDR constants ─────────────────────────────────────────────────

    /** XDR: PublicKeyType = KEY_TYPE_ED25519 */
    private static final int KEY_TYPE_ED25519 = 0;

    /** XDR: MemoType = MEMO_NONE */
    private static final int MEMO_NONE = 0;

    /** XDR: PreconditionsType = PRECOND_NONE */
    private static final int PRECOND_NONE = 0;

    /** XDR: OperationType = MANAGE_DATA (10) */
    private static final int OP_MANAGE_DATA = 10;

    /** XDR: OperationType = CLAWBACK (19 — CAP-0035) */
    private static final int OP_CLAWBACK = 19;

    /** XDR: OperationType = SET_TRUST_LINE_FLAGS (21 — CAP-0018) */
    private static final int OP_SET_TRUST_LINE_FLAGS = 21;

    /** XDR: EnvelopeType = ENVELOPE_TYPE_TX (2) */
    private static final int ENVELOPE_TYPE_TX = 2;

    /** XDR: AssetType = ASSET_TYPE_CREDIT_ALPHANUM12 (2) */
    private static final int ASSET_TYPE_ALPHANUM12 = 2;

    /** Minimum fee per operation in stroops. */
    private static final int BASE_FEE_STROOPS = 100;

    // ── Stellar network passphrases (used in transaction hash computation) ────

    private static final String PASSPHRASE_MAINNET =
            "Public Global Stellar Network ; September 2015";
    private static final String PASSPHRASE_TESTNET =
            "Test SDF Network ; September 2015";

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final ChainConfigRepository chainConfigRepository;
    private final WalletSigner walletSigner;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ChainSubmissionExecutor submissions;

    public StellarAssetService(
            ChainConfigRepository chainConfigRepository,
            WalletSigner walletSigner,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            ChainSubmissionExecutor submissions) {
        this.chainConfigRepository = chainConfigRepository;
        this.walletSigner = walletSigner;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.submissions = submissions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ── Asset creation ────────────────────────────────────────────────────────

    /**
     * Creates a Stellar asset by recording the Registerwerk asset ID in the issuer account's
     * ManageData storage.  The asset code is derived from the first 12 chars of the asset UUID
     * (upper-cased).  The transaction is signed with the issuer's Ed25519 private key.
     *
     * @param assetId      Registerwerk asset UUID
     * @param network      MAINNET or TESTNET
     * @param issuerAddress Stellar G-address of the issuing account (used for logging; resolved
     *                      from the configured wallet if blank)
     * @return future resolving to the Stellar transaction hash and the issuer G-address
     */
    public CompletableFuture<StellarDeployment> createStellarAsset(UUID assetId, Network network, String issuerAddress) {
        log.info("Creating Stellar asset: assetId={}, network={}", assetId, network);

        return submitOnChain(network, chain -> {
            String horizonUrl = chain.getRpcUrl();

            IssuerKeys keys = loadIssuerKeys(chain);
            long sequence = fetchSequenceNumber(horizonUrl, keys.address());

            // ManageData: key = "registerwerk-asset", value = assetId bytes
            byte[] key   = "registerwerk-asset".getBytes(StandardCharsets.UTF_8);
            byte[] value = assetId.toString().getBytes(StandardCharsets.UTF_8);

            byte[] txXdr = buildManageDataTx(keys.publicKeyBytes(), sequence, key, value);

            String passphrase = network == Network.MAINNET ? PASSPHRASE_MAINNET : PASSPHRASE_TESTNET;
            byte[] txHash = computeTxHash(passphrase, txXdr);

            byte[] signature = ed25519Sign(keys.privKey(), txHash);

            byte[] envelope = buildEnvelope(txXdr, keys.publicKeyBytes(), signature);
            String txHashHex = submitTransaction(horizonUrl, envelope);

            log.info("Stellar asset registered: assetId={} txHash={} issuer={}", assetId, txHashHex, keys.address());
            return new StellarDeployment(txHashHex, keys.address());
        });
    }

    // ── Regulatory admin controls ─────────────────────────────────────────────

    /**
     * Claws back a Stellar asset from the specified account (CAP-0035 Clawback).
     *
     * <p>Requires the issuer account to have the {@code AUTH_CLAWBACK_ENABLED_FLAG} set.
     * Legal basis: eWpG §24 (forced transfer), MiCAR Art. 36.
     *
     * @param assetId        Registerwerk asset UUID (determines the asset code)
     * @param accountAddress Stellar G-address of the account to claw back from
     * @param amount         amount to claw back, in the asset's human-readable units (e.g.
     *                       "100.50") — a clawback amount can never exceed the actual trustline
     *                       balance or Stellar rejects it as {@code CLAWBACK_UNDERFUNDED};
     *                       callers must supply the real amount to reclaim (e.g. the holder's
     *                       current registered balance) rather than this method guessing at a
     *                       Horizon balance lookup
     * @param network        MAINNET or TESTNET
     * @param deploymentId   Registerwerk deployment ID, for the audit record
     * @param actorId        operator who triggered this clawback
     * @param actorRole      the triggering actor's role, for the audit record
     * @return future resolving to the transaction hash
     */
    public CompletableFuture<String> clawbackAsset(UUID assetId, String accountAddress, BigDecimal amount,
                                                     Network network, UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN clawback Stellar asset={} from account={} amount={} network={}",
                assetId, accountAddress, amount, network);

        return submitOnChain(network, chain -> {
            String horizonUrl = chain.getRpcUrl();

            IssuerKeys keys = loadIssuerKeys(chain);
            long sequence = fetchSequenceNumber(horizonUrl, keys.address());

            String assetCode = deriveAssetCode(assetId);
            byte[] targetPubKey = strKeyDecode(accountAddress);
            long amountStroops = toStroops(amount);

            byte[] txXdr = buildClawbackTx(keys.publicKeyBytes(), sequence, assetCode, keys.publicKeyBytes(),
                    targetPubKey, amountStroops);

            String passphrase = network == Network.MAINNET ? PASSPHRASE_MAINNET : PASSPHRASE_TESTNET;
            byte[] txHash = computeTxHash(passphrase, txXdr);
            byte[] signature = ed25519Sign(keys.privKey(), txHash);
            byte[] envelope = buildEnvelope(txXdr, keys.publicKeyBytes(), signature);

            String txHashHex = submitTransaction(horizonUrl, envelope);
            publishAdminAction(deploymentId, "clawbackAsset",
                    Map.of("accountAddress", accountAddress, "amount", amount.toPlainString()), actorId, actorRole);
            return txHashHex;
        });
    }

    /** Stellar amounts on the wire (XDR {@code Int64}) are in stroops — 10,000,000 per unit
     *  (7 decimal places), per the Stellar protocol. Horizon's own JSON responses do this
     *  conversion for you, but XDR operations built directly (as here) must do it themselves. */
    private static long toStroops(BigDecimal amount) {
        return amount.movePointRight(7).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /**
     * Revokes or restores the {@code AUTHORIZED} flag on a trustline using {@code SetTrustLineFlags}
     * (CAP-0018).  Revoking blocks the holder from transferring the asset.
     *
     * <p>Legal basis: AWG §17 (sanctions list enforcement), GwG §40 (AML asset freeze).
     *
     * @param assetId        Registerwerk asset UUID
     * @param accountAddress Stellar G-address of the trustor account
     * @param authorize      {@code true} to restore authorization, {@code false} to revoke it
     * @param network        MAINNET or TESTNET
     * @param deploymentId   Registerwerk deployment ID, for the audit record
     * @param actorId        operator who triggered this change
     * @param actorRole      the triggering actor's role, for the audit record
     * @return future resolving to the transaction hash
     */
    public CompletableFuture<String> setAuthorizationFlags(
            UUID assetId, String accountAddress, boolean authorize, Network network,
            UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN {} Stellar trustline: asset={} account={} network={}",
                authorize ? "authorize" : "revoke", assetId, accountAddress, network);

        return submitOnChain(network, chain -> {
            String horizonUrl = chain.getRpcUrl();

            IssuerKeys keys = loadIssuerKeys(chain);
            long sequence = fetchSequenceNumber(horizonUrl, keys.address());

            String assetCode = deriveAssetCode(assetId);
            byte[] trustorPubKey = strKeyDecode(accountAddress);
            // AUTHORIZED_FLAG = 0x1; to revoke: clearFlags=1, setFlags=0; to restore: clearFlags=0, setFlags=1
            int clearFlags = authorize ? 0 : 1;
            int setFlags   = authorize ? 1 : 0;

            byte[] txXdr = buildSetTrustLineFlagsTx(
                    keys.publicKeyBytes(), sequence, assetCode, keys.publicKeyBytes(), trustorPubKey, clearFlags, setFlags);

            String passphrase = network == Network.MAINNET ? PASSPHRASE_MAINNET : PASSPHRASE_TESTNET;
            byte[] txHash = computeTxHash(passphrase, txXdr);
            byte[] signature = ed25519Sign(keys.privKey(), txHash);
            byte[] envelope = buildEnvelope(txXdr, keys.publicKeyBytes(), signature);

            String txHashHex = submitTransaction(horizonUrl, envelope);
            publishAdminAction(deploymentId, "setAuthorizationFlags",
                    Map.of("accountAddress", accountAddress, "authorize", authorize), actorId, actorRole);
            return txHashHex;
        });
    }

    /** Publishes the same {@code TokenAdminActionEvent} every EVM admin service uses, so
     *  clawback and trustline-authorization changes reach the audit trail. */
    private void publishAdminAction(UUID deploymentId, String methodName, Map<String, Object> params,
                                     UUID actorId, String actorRole) {
        eventPublisher.publishEvent(new de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent(
                deploymentId, methodName, actorId, actorRole, params));
    }

    private <T> CompletableFuture<T> submitOnChain(
            Network network, Function<ChainConfig, T> submission) {
        ChainConfig chain = resolveChainConfig(network);
        return CompletableFuture.supplyAsync(() -> submissions.execute(
                chain.getId(), () -> submission.apply(chain)));
    }

    // ── Credential helper ─────────────────────────────────────────────────────

    private record IssuerKeys(Ed25519PrivateKeyParameters privKey, byte[] publicKeyBytes, String address) {}

    private IssuerKeys loadIssuerKeys(ChainConfig chain) {
        byte[] seed = walletSigner.rawPrivateKeyBytesForChain(chain.getId());
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed);
        byte[] publicKeyBytes = privKey.generatePublicKey().getEncoded();
        String address = walletSigner.chainAddressForWallet(chain.getId());
        return new IssuerKeys(privKey, publicKeyBytes, address);
    }

    // ── Horizon REST helpers ──────────────────────────────────────────────────

    /** Fails fast on any fetch error rather than falling back to a guessed sequence number,
     *  which would just be submitted as a doomed transaction — mirrors the Starknet
     *  nonce-fetch path's own "fail fast" convention. */
    private long fetchSequenceNumber(String horizonUrl, String accountId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(horizonUrl + "/accounts/" + accountId))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode seqNode = root.path("sequence");
            if (seqNode.isMissingNode()) {
                throw new IllegalStateException(
                        "Horizon response for account " + accountId + " has no 'sequence' field: " + response.body());
            }
            return seqNode.asLong() + 1; // submit with next sequence
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to fetch Stellar sequence number for account " + accountId, e);
        }
    }

    private String submitTransaction(String horizonUrl, byte[] envelopeXdr) {
        try {
            String encoded = Base64.getEncoder().encodeToString(envelopeXdr);
            String body = "tx=" + URLEncoder.encode(encoded, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(horizonUrl + "/transactions"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("hash")) {
                return root.path("hash").asText();
            }
            String detail = root.path("extras").path("result_codes").toString();
            throw new RuntimeException("Horizon rejected transaction: " + detail);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit Stellar transaction: " + e.getMessage(), e);
        }
    }

    // ── XDR builders ──────────────────────────────────────────────────────────

    private byte[] buildManageDataTx(byte[] srcPubKey, long sequence, byte[] key, byte[] value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            writeMuxedAccount(out, srcPubKey);         // sourceAccount
            writeInt32(out, BASE_FEE_STROOPS);         // fee
            writeInt64(out, sequence);                 // seqNum
            writeInt32(out, PRECOND_NONE);             // preconditions
            writeInt32(out, MEMO_NONE);                // memo

            writeInt32(out, 1);                        // operations.len = 1
            writeInt32(out, 0);                        // no sourceAccount override
            writeInt32(out, OP_MANAGE_DATA);           // operationType
            writeVarBytes(out, key);                   // dataName
            writeInt32(out, 1);                        // dataValue present
            writeVarBytes(out, value);                 // dataValue

            writeInt32(out, 0);                        // ext = 0
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build ManageData XDR", e);
        }
    }

    private byte[] buildClawbackTx(byte[] srcPubKey, long sequence,
            String assetCode, byte[] issuerPubKey, byte[] fromPubKey, long amountStroops) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            writeMuxedAccount(out, srcPubKey);
            writeInt32(out, BASE_FEE_STROOPS);
            writeInt64(out, sequence);
            writeInt32(out, PRECOND_NONE);
            writeInt32(out, MEMO_NONE);

            writeInt32(out, 1);
            writeInt32(out, 0);
            writeInt32(out, OP_CLAWBACK);
            writeAsset(out, assetCode, issuerPubKey);  // asset
            writeMuxedAccount(out, fromPubKey);        // from
            // Stellar has no "clawback all" sentinel; the amount must not exceed the actual
            // trustline balance or the operation fails with CLAWBACK_UNDERFUNDED.
            writeInt64(out, amountStroops);

            writeInt32(out, 0);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Clawback XDR", e);
        }
    }

    private byte[] buildSetTrustLineFlagsTx(byte[] srcPubKey, long sequence,
            String assetCode, byte[] issuerPubKey, byte[] trustorPubKey,
            int clearFlags, int setFlags) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            writeMuxedAccount(out, srcPubKey);
            writeInt32(out, BASE_FEE_STROOPS);
            writeInt64(out, sequence);
            writeInt32(out, PRECOND_NONE);
            writeInt32(out, MEMO_NONE);

            writeInt32(out, 1);
            writeInt32(out, 0);
            writeInt32(out, OP_SET_TRUST_LINE_FLAGS);
            writePublicKey(out, trustorPubKey);        // trustor
            writeAsset(out, assetCode, issuerPubKey); // asset
            writeInt32(out, clearFlags);               // clearFlags
            writeInt32(out, setFlags);                 // setFlags

            writeInt32(out, 0);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SetTrustLineFlags XDR", e);
        }
    }

    private byte[] buildEnvelope(byte[] txXdr, byte[] publicKeyBytes, byte[] signature) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            writeInt32(out, ENVELOPE_TYPE_TX);
            out.write(txXdr);

            // Decorations (signatures)
            writeInt32(out, 1);  // signatures.len
            // hint = last 4 bytes of public key
            out.write(publicKeyBytes, publicKeyBytes.length - 4, 4);
            writeVarBytes(out, signature);

            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build transaction envelope", e);
        }
    }

    // ── XDR primitives ────────────────────────────────────────────────────────

    private static void writeInt32(DataOutputStream out, int v) throws Exception {
        out.writeInt(v);
    }

    private static void writeInt64(DataOutputStream out, long v) throws Exception {
        out.writeLong(v);
    }

    private static void writeVarBytes(DataOutputStream out, byte[] data) throws Exception {
        out.writeInt(data.length);
        out.write(data);
        // XDR padding to 4-byte boundary
        int pad = (4 - (data.length % 4)) % 4;
        for (int i = 0; i < pad; i++) out.writeByte(0);
    }

    private static void writePublicKey(DataOutputStream out, byte[] pubKeyBytes) throws Exception {
        out.writeInt(KEY_TYPE_ED25519);
        out.write(pubKeyBytes);
    }

    private static void writeMuxedAccount(DataOutputStream out, byte[] pubKeyBytes) throws Exception {
        // MuxedAccountType = KEY_TYPE_ED25519 (0)
        out.writeInt(KEY_TYPE_ED25519);
        out.write(pubKeyBytes);
    }

    private static void writeAsset(DataOutputStream out, String assetCode, byte[] issuerPubKey)
            throws Exception {
        out.writeInt(ASSET_TYPE_ALPHANUM12);
        byte[] codeBytes = new byte[12];
        byte[] ascii = assetCode.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, codeBytes, 0, Math.min(ascii.length, 12));
        out.write(codeBytes);
        writePublicKey(out, issuerPubKey);
    }

    // ── Transaction hash ──────────────────────────────────────────────────────

    /**
     * Stellar transaction hash = SHA-256(SHA-256(networkPassphrase) || envelopeType || txXdr).
     */
    public static byte[] computeTxHash(String networkPassphrase, byte[] txXdr) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] passphraseHash = sha256.digest(
                    networkPassphrase.getBytes(StandardCharsets.UTF_8));

            sha256.reset();
            sha256.update(passphraseHash);
            // EnvelopeType = ENVELOPE_TYPE_TX (2) as uint32 big-endian
            sha256.update(new byte[]{0, 0, 0, ENVELOPE_TYPE_TX});
            sha256.update(txXdr);
            return sha256.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Ed25519 signing ───────────────────────────────────────────────────────

    public static byte[] ed25519Sign(Ed25519PrivateKeyParameters privKey, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    // ── StrKey encoding / decoding (Stellar base32 address format) ───────────

    private static final char[] BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] BASE32_INVERSE;

    static {
        BASE32_INVERSE = new int[256];
        java.util.Arrays.fill(BASE32_INVERSE, -1);
        for (int i = 0; i < BASE32_CHARS.length; i++) {
            BASE32_INVERSE[BASE32_CHARS[i]] = i;
        }
    }

    /**
     * Decodes a Stellar StrKey G-address to the raw 32-byte Ed25519 public key.
     * Format: base32( [0x06 << 3] || pubkey[32] || CRC16-XModem[2] )
     */
    public static byte[] strKeyDecode(String gAddress) {
        // Pad to multiple of 8 chars for base32
        String padded = gAddress;
        int pad = (8 - padded.length() % 8) % 8;
        padded = padded + "=".repeat(pad);

        // Decode base32
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char c : padded.toCharArray()) {
            if (c == '=') break;
            int v = BASE32_INVERSE[c];
            if (v < 0) throw new IllegalArgumentException("Invalid StrKey character: " + c);
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                baos.write(buffer >> bitsLeft);
                buffer &= (1 << bitsLeft) - 1;
            }
        }

        byte[] decoded = baos.toByteArray();
        // decoded[0] = version byte (0x30 for account / G-address)
        // decoded[1..32] = raw public key
        // decoded[33..34] = CRC16 checksum (not verified here)
        if (decoded.length < 35) {
            throw new IllegalArgumentException("Invalid Stellar G-address length: " + gAddress);
        }
        byte[] pubKey = new byte[32];
        System.arraycopy(decoded, 1, pubKey, 0, 32);
        return pubKey;
    }

    /**
     * Encodes a raw 32-byte Ed25519 public key as a Stellar StrKey G-address.
     */
    public static String strKeyEncode(byte[] pubKeyBytes) {
        // Version byte for account: 6 << 3 = 0x30
        byte[] payload = new byte[35];
        payload[0] = 0x30;
        System.arraycopy(pubKeyBytes, 0, payload, 1, 32);
        // CRC16-XModem checksum
        int crc = crc16Xmodem(payload, 33);
        payload[33] = (byte) (crc & 0xFF);
        payload[34] = (byte) ((crc >> 8) & 0xFF);

        // Encode as base32 (no padding)
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : payload) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_CHARS[(buffer >> bitsLeft) & 0x1F]);
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_CHARS[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }

    private static int crc16Xmodem(byte[] data, int length) {
        int crc = 0x0000;
        for (int i = 0; i < length; i++) {
            crc ^= ((data[i] & 0xFF) << 8);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private ChainConfig resolveChainConfig(Network network) {
        ChainConfig.NetworkType netType = network == Network.MAINNET
                ? ChainConfig.NetworkType.MAINNET
                : ChainConfig.NetworkType.TESTNET;
        return chainConfigRepository
                .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STELLAR).stream()
                .filter(c -> c.getNetworkType() == netType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled Stellar chain config for " + network
                        + ". Add a Stellar Horizon node via the Operator Portal → Network Nodes."));
    }

    private static String deriveAssetCode(UUID assetId) {
        return de.makibytes.registerwerk.blockchain.api.StellarUtils.deriveAssetCode(assetId);
    }
}
