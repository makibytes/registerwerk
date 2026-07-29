package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes4;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wallet signature verification (EOA + ERC-1271)")
class WalletSignatureVerifierImplTest {

    private static final UUID CHAIN_CONFIG_ID = UUID.randomUUID();
    private static final String CONTRACT_WALLET = "0x2222222222222222222222222222222222222222";

    @Mock
    private EvmContractService evmContractService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Web3j web3j;

    private WalletSignatureVerifierImpl verifier;

    @BeforeEach
    void setUp() {
        verifier = new WalletSignatureVerifierImpl(evmContractService);
        when(evmContractService.evmClient(CHAIN_CONFIG_ID)).thenReturn(web3j);
    }

    @Test
    @DisplayName("verifies a plain EOA signature via ECDSA recovery")
    void verifiesEoaSignature() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String wallet = "0x" + Keys.getAddress(keyPair);
        String message = "Registerwerk wallet binding\nnonce: abcd1234";
        stubEoa(wallet);

        verifier.verifyPersonalSign(CHAIN_CONFIG_ID, message, sign(message, keyPair), wallet);
        // no exception => verified
    }

    @Test
    @DisplayName("rejects a foreign EOA signature")
    void rejectsForeignEoaSignature() throws Exception {
        ECKeyPair attacker = Keys.createEcKeyPair();
        String claimedWallet = "0x1111111111111111111111111111111111111111";
        String message = "Registerwerk wallet binding\nnonce: abcd1234";
        stubEoa(claimedWallet);

        assertThatThrownBy(() ->
                verifier.verifyPersonalSign(CHAIN_CONFIG_ID, message, sign(message, attacker), claimedWallet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not produced by wallet");
    }

    @Test
    @DisplayName("rejects malformed signatures")
    void rejectsMalformedSignature() {
        stubEoa("0x1111111111111111111111111111111111111111");

        assertThatThrownBy(() -> verifier.verifyPersonalSign(
                CHAIN_CONFIG_ID, "msg", "0x1234", "0x1111111111111111111111111111111111111111"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("65 bytes");
    }

    @Test
    @DisplayName("verifies a smart-contract wallet via ERC-1271 isValidSignature")
    void verifiesErc1271Signature() {
        stubContract(CONTRACT_WALLET);
        when(evmContractService.call(eq(web3j), eq(CONTRACT_WALLET), any()))
                .thenReturn(List.<Type>of(new Bytes4(Numeric.hexStringToByteArray("0x1626ba7e"))));

        verifier.verifyPersonalSign(CHAIN_CONFIG_ID, "any message", "0xdeadbeef", CONTRACT_WALLET);
        // no exception => verified, regardless of the raw signature bytes — the mocked
        // contract is the source of truth for its own isValidSignature result
    }

    @Test
    @DisplayName("rejects a smart-contract wallet when isValidSignature returns a non-magic value")
    void rejectsErc1271WrongMagicValue() {
        stubContract(CONTRACT_WALLET);
        when(evmContractService.call(eq(web3j), eq(CONTRACT_WALLET), any()))
                .thenReturn(List.<Type>of(new Bytes4(Numeric.hexStringToByteArray("0xffffffff"))));

        assertThatThrownBy(() ->
                verifier.verifyPersonalSign(CHAIN_CONFIG_ID, "any message", "0xdeadbeef", CONTRACT_WALLET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ERC-1271");
    }

    @Test
    @DisplayName("rejects a smart-contract wallet when the eth_call reverts")
    void rejectsErc1271WhenCallReverts() {
        stubContract(CONTRACT_WALLET);
        when(evmContractService.call(eq(web3j), eq(CONTRACT_WALLET), any()))
                .thenThrow(new RuntimeException("execution reverted"));

        assertThatThrownBy(() ->
                verifier.verifyPersonalSign(CHAIN_CONFIG_ID, "any message", "0xdeadbeef", CONTRACT_WALLET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ERC-1271");
    }

    private void stubEoa(String address) {
        try {
            when(web3j.ethGetCode(eq(address), any(DefaultBlockParameterName.class)).send().getCode())
                    .thenReturn("0x");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void stubContract(String address) {
        try {
            when(web3j.ethGetCode(eq(address), any(DefaultBlockParameterName.class)).send().getCode())
                    .thenReturn("0x600180600d600039806000f3");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sign(String message, ECKeyPair keyPair) {
        Sign.SignatureData signature =
                Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
        byte[] out = new byte[65];
        System.arraycopy(signature.getR(), 0, out, 0, 32);
        System.arraycopy(signature.getS(), 0, out, 32, 32);
        out[64] = signature.getV()[0];
        return Numeric.toHexString(out);
    }
}
