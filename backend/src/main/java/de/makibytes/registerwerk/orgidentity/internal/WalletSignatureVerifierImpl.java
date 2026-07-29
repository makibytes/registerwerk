package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.orgidentity.api.WalletSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Bytes4;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Checks the claimed wallet's on-chain code to decide the verification path: a plain EOA
 * (no code) is verified by ECDSA recovery against the EIP-191 message hash; a smart-contract
 * wallet (an ERC-4337 account, an EIP-7702-delegated EOA, a Safe, …) is verified via its own
 * ERC-1271 {@code isValidSignature(bytes32,bytes)}. Both paths fail closed: any eth_call
 * error, a missing magic-value match, or an ECDSA mismatch throws.
 */
@Component
class WalletSignatureVerifierImpl implements WalletSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WalletSignatureVerifierImpl.class);

    /** bytes4(keccak256("isValidSignature(bytes32,bytes)")), per ERC-1271. */
    private static final String ERC1271_MAGIC_VALUE = "1626ba7e";

    private final EvmContractService evmContractService;

    WalletSignatureVerifierImpl(EvmContractService evmContractService) {
        this.evmContractService = evmContractService;
    }

    @Override
    public void verifyPersonalSign(UUID chainConfigId, String message, String signatureHex, String claimedWallet) {
        byte[] digest = Sign.getEthereumMessageHash(message.getBytes(StandardCharsets.UTF_8));
        Web3j web3j = evmContractService.evmClient(chainConfigId);

        if (isContract(web3j, claimedWallet)) {
            if (!verifyErc1271(web3j, claimedWallet, digest, signatureHex)) {
                throw new IllegalArgumentException(
                        "ERC-1271 signature verification failed for wallet " + claimedWallet);
            }
            return;
        }

        String recovered = recoverEcdsaSigner(digest, signatureHex);
        if (!recovered.equalsIgnoreCase(claimedWallet)) {
            throw new IllegalArgumentException("Signature was not produced by wallet " + claimedWallet);
        }
    }

    private boolean isContract(Web3j web3j, String address) {
        try {
            String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
            return code != null && !code.isEmpty() && !code.equals("0x");
        } catch (Exception e) {
            // Can't determine — fail closed to the ECDSA path rather than assume a bypass.
            log.warn("eth_getCode failed for {}, treating as EOA: {}", address, e.getMessage());
            return false;
        }
    }

    private boolean verifyErc1271(Web3j web3j, String walletAddress, byte[] digest, String signatureHex) {
        Function isValidSignature = new Function(
                "isValidSignature",
                List.of(new Bytes32(digest), new DynamicBytes(Numeric.hexStringToByteArray(signatureHex))),
                List.of(new TypeReference<Bytes4>() {}));
        try {
            List<Type> result = evmContractService.call(web3j, walletAddress, isValidSignature);
            if (result.isEmpty()) {
                return false;
            }
            byte[] returned = ((Bytes4) result.get(0)).getValue();
            return Numeric.toHexStringNoPrefix(returned).equalsIgnoreCase(ERC1271_MAGIC_VALUE);
        } catch (Exception e) {
            log.warn("ERC-1271 isValidSignature call failed for {}: {}", walletAddress, e.getMessage());
            return false;
        }
    }

    private static String recoverEcdsaSigner(byte[] digest, String signatureHex) {
        byte[] sig = Numeric.hexStringToByteArray(signatureHex);
        if (sig.length != 65) {
            throw new IllegalArgumentException("Signature must be 65 bytes (r,s,v)");
        }
        byte v = sig[64];
        if (v < 27) {
            v += 27;
        }
        Sign.SignatureData data = new Sign.SignatureData(
                v, Arrays.copyOfRange(sig, 0, 32), Arrays.copyOfRange(sig, 32, 64));
        try {
            BigInteger publicKey = Sign.signedMessageHashToKey(digest, data);
            return "0x" + Keys.getAddress(publicKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not recover signer from signature: " + e.getMessage(), e);
        }
    }
}
