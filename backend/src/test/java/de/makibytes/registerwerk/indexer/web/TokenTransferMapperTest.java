package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.web.dto.TokenTransferResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenTransferMapper — server-side technical vs. plain-language finality vocabulary")
class TokenTransferMapperTest {

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private Authentication authentication;

    private TokenTransferMapper mapper;
    private final UUID chainConfigId = UUID.randomUUID();

    private TokenTransfer transfer(FinalityLevel level) {
        TokenTransfer t = new TokenTransfer();
        t.setChainConfigId(chainConfigId);
        t.setContractAddress("0xabc");
        t.setAmount(BigDecimal.TEN);
        t.setEventType(TokenTransfer.EventType.TRANSFER);
        t.setTxHash("0xdeadbeef");
        t.setBlockNumber(42L);
        t.setOccurredAt(Instant.now());
        t.setFinalityStatus(level);
        return t;
    }

    private void setUpChain() {
        ChainConfig chain = new ChainConfig();
        chain.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain));
        mapper = new TokenTransferMapper(chainConfigRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_REGISTRY_ADMIN", "ROLE_AUDIT", "ROLE_COMPLIANCE_OFFICER", "ROLE_RELATIONSHIP_MANAGER"})
    @DisplayName("technical roles get the raw FinalityLevel name as the label")
    void technicalRoles_getRawEnumNameAsLabel(String authority) {
        setUpChain();
        doReturn(List.of(new SimpleGrantedAuthority(authority))).when(authentication).getAuthorities();

        TokenTransferResponse response = mapper.toResponse(transfer(FinalityLevel.SAFE), authentication);

        assertThat(response.finalityStatus()).isEqualTo("SAFE");
        assertThat(response.finalityLabel()).isEqualTo("SAFE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ISSUER", "ROLE_INVESTOR", "ROLE_TRADER", "ROLE_COMPANY_ADMIN", "ROLE_DAPP_PUBLISHER"})
    @DisplayName("customer-side roles get the plain-language label, never the raw enum name")
    void customerRoles_getPlainLanguageLabel(String authority) {
        setUpChain();
        doReturn(List.of(new SimpleGrantedAuthority(authority))).when(authentication).getAuthorities();

        TokenTransferResponse response = mapper.toResponse(transfer(FinalityLevel.SAFE), authentication);

        assertThat(response.finalityStatus()).isEqualTo("SAFE");
        assertThat(response.finalityLabel()).isEqualTo("Confirmed");
    }

    @Test
    @DisplayName("finalityStatus (the raw enum) never changes with role — only finalityLabel does")
    void finalityStatus_isStableAcrossRoles() {
        setUpChain();
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_INVESTOR"))).when(authentication).getAuthorities();

        TokenTransferResponse response = mapper.toResponse(transfer(FinalityLevel.ORPHANED), authentication);

        assertThat(response.finalityStatus()).isEqualTo("ORPHANED");
        assertThat(response.finalityLabel()).isEqualTo("Did not go through");
    }

    @Test
    @DisplayName("a null Authentication (defensive) resolves to plain language, not technical")
    void nullAuthentication_defaultsToPlainLanguage() {
        setUpChain();

        TokenTransferResponse response = mapper.toResponse(transfer(FinalityLevel.FINALIZED), null);

        assertThat(response.finalityLabel()).isEqualTo("Settled — final");
    }

    @Test
    @DisplayName("chainIdentifier resolves via ChainConfigRepository lookup")
    void resolvesChainIdentifier() {
        setUpChain();
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN"))).when(authentication).getAuthorities();

        TokenTransferResponse response = mapper.toResponse(transfer(FinalityLevel.PROVISIONAL), authentication);

        assertThat(response.chainIdentifier()).isEqualTo("ETHEREUM_SEPOLIA");
    }

    @Test
    @DisplayName("falls back to the raw chainConfigId when it doesn't resolve to a known ChainConfig")
    void unresolvedChainConfigId_fallsBackToRawId() {
        when(chainConfigRepository.findById(any())).thenReturn(Optional.empty());
        mapper = new TokenTransferMapper(chainConfigRepository);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN"))).when(authentication).getAuthorities();

        TokenTransferResponse response = mapper.toResponse(transfer(FinalityLevel.PROVISIONAL), authentication);

        assertThat(response.chainIdentifier()).isEqualTo(chainConfigId.toString());
    }
}
