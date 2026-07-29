package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentContent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDocumentSource;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.kyc.api.S3DocumentStorageAdapter;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fetches term sheet documents from deployed token contracts via:
 * <ul>
 *   <li>ERC-1643 {@code getDocument(TERM_SHEET)} for ERC-20, ERC-721, ERC-1155, CONF_ERC20, CONF_ERC3643</li>
 *   <li>Companion {@code EwpgDocumentStore.getDocument(TERM_SHEET)} for ERC-3643 (T-REX proxy)</li>
 *   <li>Solana Metaplex metadata URI for SPL tokens (read-only, URI from metadata account)</li>
 * </ul>
 * After reading the on-chain URI the content is downloaded and stored in the database so
 * it is available to BaFin without depending on external availability.
 */
@Service
@Transactional
public class TermSheetOnChainFetchService {

    private static final Logger log = LoggerFactory.getLogger(TermSheetOnChainFetchService.class);

    /** keccak256("TERM_SHEET") — must match the TERM_SHEET constant in IEwpgDocumentManagement.sol */
    private static final String TERM_SHEET_HEX =
        Numeric.toHexString(Hash.sha3("TERM_SHEET".getBytes(StandardCharsets.UTF_8)));

    private static final int MAX_INLINE_BYTES = 5 * 1024 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final BlockchainClientRegistry clientRegistry;
    private final AssetRepository assetRepository;
    private final AssetDocumentRepository assetDocumentRepository;
    private final S3DocumentStorageAdapter s3Adapter;
    private final ContractAddressConfig contractAddressConfig;
    private final HttpClient httpClient;

    @Value("${registerwerk.ipfs.gateway:https://ipfs.io/ipfs/}")
    private String ipfsGateway;

    public TermSheetOnChainFetchService(
            BlockchainClientRegistry clientRegistry,
            AssetRepository assetRepository,
            AssetDocumentRepository assetDocumentRepository,
            S3DocumentStorageAdapter s3Adapter,
            ContractAddressConfig contractAddressConfig) {
        this.clientRegistry = clientRegistry;
        this.assetRepository = assetRepository;
        this.assetDocumentRepository = assetDocumentRepository;
        this.s3Adapter = s3Adapter;
        this.contractAddressConfig = contractAddressConfig;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");
    private static final int MAX_REDIRECTS = 5;

    static void rejectInternalAddress(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("SSRF blocked: URL has no host — " + uri);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("SSRF blocked: disallowed scheme — " + uri);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                    throw new IllegalArgumentException(
                            "SSRF blocked: resolved to non-routable address " + addr.getHostAddress() + " — " + uri);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("SSRF blocked: cannot resolve host — " + uri, e);
        }
    }

    /**
     * Reads the term sheet URI and hash from the deployed contract, downloads the content,
     * and persists an {@link AssetDocument} record.
     *
     * @param deployment the confirmed on-chain deployment
     * @param service    the {@link TermSheetService} used for persistence
     * @return the persisted {@link AssetDocument}
     */
    public AssetDocument fetch(AssetDeployment deployment, TermSheetService service) {
        var asset = assetRepository.findById(deployment.getAssetId())
            .orElseThrow(() -> new EntityNotFoundException("Asset", deployment.getAssetId()));

        TokenStandard standard = asset.getTokenStandard();
        if (deployment.getChain() == Chain.SOLANA) {
            return fetchSolana(deployment, service);
        }
        return fetchEvm(deployment, standard, service);
    }

    private AssetDocument fetchEvm(AssetDeployment deployment, TokenStandard standard,
                                   TermSheetService service) {
        Web3j web3j = clientRegistry.getEvmClient(
            new ChainDescriptor(deployment.getChain(), deployment.getNetwork()));

        String contractAddress;
        if (standard == TokenStandard.ERC3643) {
            contractAddress = resolveErc3643DocStore(deployment, web3j);
        } else {
            contractAddress = deployment.getContractAddress();
        }

        log.info("Fetching term sheet from EVM contract: {}", contractAddress);

        OnChainDocumentRef ref = callGetDocument(web3j, contractAddress);
        if (ref == null || ref.uri().isBlank()) {
            throw new IllegalStateException(
                "No TERM_SHEET document set on contract " + contractAddress);
        }

        log.info("Resolved on-chain term sheet URI: {}", ref.uri());
        byte[] content = downloadUri(ref.uri());
        String mimeType = detectMimeType(ref.uri(), content);

        return persist(deployment, AssetDocumentSource.ONCHAIN_ERC1643,
            ref, content, mimeType, service);
    }

    private String resolveErc3643DocStore(AssetDeployment deployment, Web3j web3j) {
        String chainKey = deployment.getChain().name().toLowerCase() + "-"
            + deployment.getNetwork().name().toLowerCase();
        String trexFactoryAddress = contractAddressConfig.requireTrexFactory(chainKey);

        byte[] assetIdBytes = hexToBytes32(deployment.getAssetId().toString().replace("-", ""));

        // docStoreByAssetId(bytes32) returns address
        Function fn = new Function("docStoreByAssetId",
            List.of(new Bytes32(assetIdBytes)),
            List.of(new TypeReference<org.web3j.abi.datatypes.Address>() {}));

        try {
            EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(null, trexFactoryAddress,
                    FunctionEncoder.encode(fn)),
                DefaultBlockParameterName.LATEST).send();

            if (response.isReverted()) {
                throw new IllegalStateException("docStoreByAssetId reverted: " + response.getRevertReason());
            }
            List<Type> decoded = FunctionReturnDecoder.decode(
                response.getValue(), fn.getOutputParameters());
            if (decoded.isEmpty()) {
                throw new IllegalStateException("Empty response from docStoreByAssetId");
            }
            return decoded.get(0).getValue().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to query ERC-3643 document store address", e);
        }
    }

    private OnChainDocumentRef callGetDocument(Web3j web3j, String contractAddress) {
        Function fn = new Function("getDocument",
            List.of(new Bytes32(Numeric.hexStringToByteArray(TERM_SHEET_HEX))),
            Arrays.asList(
                new TypeReference<Utf8String>() {},
                new TypeReference<Bytes32>() {},
                new TypeReference<Uint256>() {}));

        try {
            EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(null, contractAddress,
                    FunctionEncoder.encode(fn)),
                DefaultBlockParameterName.LATEST).send();

            if (response.isReverted()) {
                throw new IllegalStateException("getDocument reverted: " + response.getRevertReason());
            }
            if (response.getValue() == null || response.getValue().equals("0x")) {
                return null;
            }
            List<Type> decoded = FunctionReturnDecoder.decode(
                response.getValue(), fn.getOutputParameters());
            if (decoded.size() < 2) return null;

            String uri = (String) decoded.get(0).getValue();
            byte[] hashBytes = (byte[]) decoded.get(1).getValue();
            String hashHex = Numeric.toHexString(hashBytes);
            return new OnChainDocumentRef(uri, hashHex);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call getDocument on " + contractAddress, e);
        }
    }

    private AssetDocument fetchSolana(AssetDeployment deployment, TermSheetService service) {
        log.info("Fetching Solana term sheet for contract: {}", deployment.getContractAddress());

        RpcClient rpcClient = clientRegistry.getSolanaClient(
            new ChainDescriptor(deployment.getChain(), deployment.getNetwork()));

        String metadataUri = fetchSolanaMetadataUri(rpcClient, deployment.getContractAddress());
        if (metadataUri == null || metadataUri.isBlank()) {
            throw new IllegalStateException("No metadata URI on Solana token: " + deployment.getContractAddress());
        }

        byte[] metadataJson = downloadUri(metadataUri);
        String termSheetUri = extractTermSheetUriFromMetadata(metadataJson);
        if (termSheetUri == null) {
            termSheetUri = metadataUri;
        }

        byte[] content = downloadUri(termSheetUri);
        String mimeType = detectMimeType(termSheetUri, content);
        OnChainDocumentRef ref = new OnChainDocumentRef(termSheetUri, null);
        return persist(deployment, AssetDocumentSource.ONCHAIN_SOLANA, ref, content, mimeType, service);
    }

    private String fetchSolanaMetadataUri(RpcClient rpcClient, String mintAddress) {
        try {
            var accountInfo = rpcClient.getApi().getAccountInfo(new PublicKey(mintAddress));
            if (accountInfo == null) return null;

            byte[] data = Base64.getDecoder()
                .decode(accountInfo.getValue().getData().get(0));
            if (data.length < 300) return null;

            return parseMetaplexUri(data);
        } catch (Exception e) {
            log.warn("Failed to fetch Solana metadata URI for {}: {}", mintAddress, e.getMessage());
            return null;
        }
    }

    private String parseMetaplexUri(byte[] data) {
        try {
            int offset = 1 + 32 + 32; // key + update_authority + mint
            int nameLen = readLe32(data, offset); offset += 4 + nameLen;
            int symLen = readLe32(data, offset); offset += 4 + symLen;
            int uriLen = readLe32(data, offset); offset += 4;
            if (offset + uriLen > data.length) return null;
            return new String(data, offset, uriLen, StandardCharsets.UTF_8).stripTrailing().replace("\0", "");
        } catch (Exception e) {
            return null;
        }
    }

    private int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    private String extractTermSheetUriFromMetadata(byte[] json) {
        String text = new String(json, StandardCharsets.UTF_8);
        for (String key : List.of("\"term_sheet\"", "\"termsheet\"", "\"TERM_SHEET\"")) {
            int idx = text.indexOf(key);
            if (idx >= 0) {
                int colon = text.indexOf(':', idx);
                int quote = text.indexOf('"', colon + 1);
                int end = text.indexOf('"', quote + 1);
                if (quote > 0 && end > quote) {
                    return text.substring(quote + 1, end);
                }
            }
        }
        return null;
    }

    private byte[] downloadUri(String uri) {
        String resolvedUrl = resolveUri(uri);
        log.debug("Downloading term sheet content from: {}", resolvedUrl);
        try {
            if (resolvedUrl.startsWith("data:")) {
                return decodeDataUri(resolvedUrl);
            }
            URI target = URI.create(resolvedUrl);
            rejectInternalAddress(target);

            for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
                var request = HttpRequest.newBuilder()
                        .uri(target)
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) {
                        throw new IllegalStateException("Redirect " + status + " without Location header");
                    }
                    target = target.resolve(location);
                    rejectInternalAddress(target);
                    continue;
                }

                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(
                            "HTTP " + status + " fetching term sheet: " + target);
                }
                log.info("Downloaded {} bytes from {}", response.body().length, target);
                return response.body();
            }
            throw new IllegalStateException("Too many redirects fetching term sheet: " + resolvedUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading term sheet", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download term sheet from " + resolvedUrl, e);
        }
    }

    private String resolveUri(String uri) {
        if (uri.startsWith("ipfs://")) {
            return ipfsGateway + uri.substring("ipfs://".length());
        }
        if (uri.startsWith("ar://")) {
            return "https://arweave.net/" + uri.substring("ar://".length());
        }
        return uri;
    }

    private byte[] decodeDataUri(String dataUri) {
        int commaIdx = dataUri.indexOf(',');
        if (commaIdx < 0) throw new IllegalArgumentException("Malformed data URI");
        String header = dataUri.substring(5, commaIdx);
        String data = dataUri.substring(commaIdx + 1);
        if (header.contains(";base64")) {
            return Base64.getDecoder().decode(data);
        }
        return data.getBytes(StandardCharsets.UTF_8);
    }

    private String detectMimeType(String uri, byte[] content) {
        try {
            String path = URI.create(resolveUri(uri)).getPath();
            if (path != null) {
                String lp = path.toLowerCase();
                if (lp.endsWith(".pdf"))  return "application/pdf";
                if (lp.endsWith(".html") || lp.endsWith(".htm")) return "text/html";
                if (lp.endsWith(".json")) return "application/json";
                if (lp.endsWith(".xml"))  return "application/xml";
                if (lp.endsWith(".txt"))  return "text/plain";
                if (lp.endsWith(".md"))   return "text/markdown";
                if (lp.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse URI for MIME-type detection by extension: {}", uri, e);
        }

        if (content.length >= 4) {
            if (content[0] == 0x25 && content[1] == 0x50 && content[2] == 0x44 && content[3] == 0x46) {
                return "application/pdf";
            }
            if (content[0] == 0x3C) return "text/html";
            if (content[0] == 0x7B) return "application/json";
            if (content.length >= 5 && new String(content, 0, 5, StandardCharsets.UTF_8).startsWith("<?xml")) {
                return "application/xml";
            }
        }
        return "application/octet-stream";
    }

    private AssetDocument persist(AssetDeployment deployment, AssetDocumentSource source,
                                  OnChainDocumentRef ref, byte[] content, String mimeType,
                                  TermSheetService service) {
        AssetDocument doc = new AssetDocument();
        doc.setAssetId(deployment.getAssetId());
        doc.setDocumentType(AssetDocumentType.TERM_SHEET);
        doc.setSource(source);
        doc.setMimeType(mimeType);
        doc.setFileName(deriveFileName(ref.uri(), mimeType));
        doc.setChain(deployment.getChain());
        doc.setNetwork(deployment.getNetwork());
        doc.setOnchainDocName(TERM_SHEET_HEX);
        doc.setOnchainUri(ref.uri());
        doc.setFetchedAt(Instant.now());
        doc.setSizeBytes((long) content.length);
        if (ref.hash() != null) doc.setContentHash(ref.hash());

        if (content.length <= MAX_INLINE_BYTES) {
            doc.setStorageRef("inline");
            AssetDocument saved = service.saveDocument(doc);
            AssetDocumentContent cnt = new AssetDocumentContent();
            cnt.setId(saved.getId());
            cnt.setContent(content);
            service.saveContent(cnt);
            return saved;
        } else {
            UUID assetId = deployment.getAssetId();
            String s3Key = "termsheets/" + assetId + "/" + UUID.randomUUID() + "/" + doc.getFileName();
            doc.setStorageRef(s3Key);
            AssetDocument saved = service.saveDocument(doc);
            s3Adapter.upload(s3Key, content, mimeType);
            return saved;
        }
    }

    private String deriveFileName(String uri, String mimeType) {
        try {
            String path = URI.create(resolveUri(uri)).getPath();
            if (path != null && !path.isBlank() && path.contains("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank()) return name;
            }
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse URI to derive file name: {}", uri, e);
        }
        return switch (mimeType) {
            case "application/pdf" -> "term_sheet.pdf";
            case "text/html" -> "term_sheet.html";
            case "application/json" -> "term_sheet.json";
            case "application/xml", "text/xml" -> "term_sheet.xml";
            default -> "term_sheet.bin";
        };
    }

    private static byte[] hexToBytes32(String hex) {
        byte[] bytes = Numeric.hexStringToByteArray(hex.startsWith("0x") ? hex : "0x" + hex);
        if (bytes.length == 32) return bytes;
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        return padded;
    }

    private record OnChainDocumentRef(String uri, String hash) {}
}
