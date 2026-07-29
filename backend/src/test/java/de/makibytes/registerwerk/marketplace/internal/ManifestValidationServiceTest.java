package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("dApp manifest validation")
class ManifestValidationServiceTest {

    @Mock
    private PermissionDefinitionRepository permissionDefinitionRepository;

    @Mock
    private PaymentRailRepository paymentRailRepository;

    @Mock
    private PaymentRailChainAddressRepository paymentRailChainAddressRepository;

    private ManifestValidationService service;

    private final UUID chainConfigId = UUID.randomUUID();

    private static final String VALID_MANIFEST = """
            {
              "slug": "loandesk",
              "name": "Loan Desk",
              "version": "1.0.0",
              "description": "Institutional loan origination on Registerwerk rails.",
              "category": "lending",
              "requiredPermissions": [
                { "code": "loandesk.open", "rationale": "Open loan requests on behalf of the org" }
              ],
              "requiredClaimTopics": [1],
              "images": [
                { "name": "backend", "role": "backend",
                  "ref": "registry.bank.example/loandesk/backend@sha256:1111111111111111111111111111111111111111111111111111111111111111" }
              ],
              "contact": "dapps@example.com"
            }
            """;

    private static PaymentRail rail(String code, PaymentRailType type, boolean enabled) {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode(code);
        rail.setRailType(type);
        rail.setEnabled(enabled);
        return rail;
    }

    @BeforeEach
    void setUp() {
        // Off-chain rail type by default so most tests don't need to mock chain addresses.
        lenient().when(paymentRailRepository.findByCode("aueur"))
                .thenReturn(Optional.of(rail("aueur", PaymentRailType.PONTES_API, true)));
        service = new ManifestValidationService(
                permissionDefinitionRepository, paymentRailRepository, paymentRailChainAddressRepository);
    }

    @Test
    @DisplayName("accepts a well-formed manifest with digest-pinned images")
    void acceptsValidManifest() {
        var result = service.validate(VALID_MANIFEST, "loandesk", chainConfigId);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().name()).isEqualTo("Loan Desk");
        assertThat(result.manifest().requiredPermissions()).hasSize(1);
        assertThat(result.manifest().requiredClaimTopics()).containsExactly(1L);
    }

    @Test
    @DisplayName("rejects tag-only image references (digest pinning is mandatory)")
    void rejectsTagOnlyImage() {
        String manifest = VALID_MANIFEST.replace(
                "@sha256:1111111111111111111111111111111111111111111111111111111111111111", ":latest");
        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("rejects a slug mismatch between manifest and listing")
    void rejectsSlugMismatch() {
        var result = service.validate(VALID_MANIFEST, "other-dapp", chainConfigId);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("does not match the listing slug"));
    }

    @Test
    @DisplayName("rejects permissions outside the dApp namespace unless they already exist")
    void rejectsForeignNamespace() {
        String manifest = VALID_MANIFEST.replace("loandesk.open", "otherdapp.open");
        when(permissionDefinitionRepository.existsByCode("otherdapp.open")).thenReturn(false);

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("outside the dApp's namespace"));
    }

    @Test
    @DisplayName("accepts platform permissions that already exist as definitions")
    void acceptsKnownPlatformPermission() {
        String manifest = VALID_MANIFEST.replace("loandesk.open", "registerwerk.transfer");
        when(permissionDefinitionRepository.existsByCode("registerwerk.transfer")).thenReturn(true);

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("rejects invalid JSON without throwing")
    void rejectsInvalidJson() {
        var result = service.validate("{not json", "loandesk", chainConfigId);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("not valid JSON"));
    }

    @Test
    @DisplayName("accepts a payment method referencing an enabled, chain-agnostic operator rail")
    void acceptsEnabledPaymentRail() {
        String manifest = VALID_MANIFEST.replace(
                "\"requiredClaimTopics\": [1],",
                "\"requiredClaimTopics\": [1], \"paymentMethods\": [{\"rail\": \"aueur\"}],");

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().paymentMethods()).hasSize(1);
        assertThat(result.manifest().paymentMethods().get(0).methodType()).isEqualTo("RAIL");
        assertThat(result.manifest().paymentMethods().get(0).railCode()).isEqualTo("aueur");
    }

    @Test
    @DisplayName("rejects a payment method referencing an unknown or disabled rail")
    void rejectsDisabledPaymentRail() {
        String manifest = VALID_MANIFEST.replace(
                "\"requiredClaimTopics\": [1],",
                "\"requiredClaimTopics\": [1], \"paymentMethods\": [{\"rail\": \"unknown-rail\"}],");
        when(paymentRailRepository.findByCode("unknown-rail")).thenReturn(Optional.empty());

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("not an enabled operator-curated rail"));
    }

    @Test
    @DisplayName("accepts a chain-bound rail (stablecoin) when a contract address exists on the anchor chain")
    void acceptsChainBoundRailWithDeployedAddress() {
        PaymentRail stablecoin = rail("usdc", PaymentRailType.STABLECOIN, true);
        when(paymentRailRepository.findByCode("usdc")).thenReturn(Optional.of(stablecoin));
        when(paymentRailChainAddressRepository
                .existsByPaymentRailIdAndChainConfigId(stablecoin.getId(), chainConfigId))
                .thenReturn(true);

        String manifest = VALID_MANIFEST.replace(
                "\"requiredClaimTopics\": [1],",
                "\"requiredClaimTopics\": [1], \"paymentMethods\": [{\"rail\": \"usdc\"}],");

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("rejects a chain-bound rail with no deployed contract address on the anchor chain — "
            + "a manifest cannot declare a payment rail its own chain can't actually use")
    void rejectsChainBoundRailWithoutDeployedAddress() {
        PaymentRail stablecoin = rail("usdc", PaymentRailType.STABLECOIN, true);
        when(paymentRailRepository.findByCode("usdc")).thenReturn(Optional.of(stablecoin));
        when(paymentRailChainAddressRepository
                .existsByPaymentRailIdAndChainConfigId(stablecoin.getId(), chainConfigId))
                .thenReturn(false);

        String manifest = VALID_MANIFEST.replace(
                "\"requiredClaimTopics\": [1],",
                "\"requiredClaimTopics\": [1], \"paymentMethods\": [{\"rail\": \"usdc\"}],");

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("no deployed contract address"));
    }

    @Test
    @DisplayName("accepts a custom payment method the dApp implements itself")
    void acceptsCustomPaymentMethod() {
        String manifest = VALID_MANIFEST.replace(
                "\"requiredClaimTopics\": [1],",
                "\"requiredClaimTopics\": [1], \"paymentMethods\": [{\"custom\": "
                + "{\"name\": \"Own SEPA rail\", \"description\": \"Publisher-run SEPA collection account\"}}],");

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().paymentMethods()).hasSize(1);
        assertThat(result.manifest().paymentMethods().get(0).methodType()).isEqualTo("CUSTOM");
        assertThat(result.manifest().paymentMethods().get(0).customName()).isEqualTo("Own SEPA rail");
    }

    @Test
    @DisplayName("rejects a payment method entry declaring both a rail and a custom method")
    void rejectsAmbiguousPaymentMethod() {
        String manifest = VALID_MANIFEST.replace(
                "\"requiredClaimTopics\": [1],",
                "\"requiredClaimTopics\": [1], \"paymentMethods\": [{\"rail\": \"aueur\", \"custom\": "
                + "{\"name\": \"x\", \"description\": \"conflicting entry with both fields set\"}}],");

        var result = service.validate(manifest, "loandesk", chainConfigId);
        assertThat(result.valid()).isFalse();
    }
}
