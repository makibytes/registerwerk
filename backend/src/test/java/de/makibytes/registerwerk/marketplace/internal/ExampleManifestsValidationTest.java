package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Ties the four shipped reference dApp manifests
 * ({@code backend/src/main/resources/demo/dapps/{boardroom,bond-desk,repo-facility,repo-markets}.manifest.json}
 * — also read directly by {@code EcosystemDemoDataSeeder} and documented in
 * {@code examples/dapps/}) to the real manifest JSON schema and marketplace semantic
 * rules, so a schema or manifest edit that breaks either is caught in CI rather than at
 * demo-seed time.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Example dApp manifests")
class ExampleManifestsValidationTest {

    @Mock
    private PermissionDefinitionRepository permissionDefinitionRepository;

    @Mock
    private PaymentRailRepository paymentRailRepository;

    @Mock
    private PaymentRailChainAddressRepository paymentRailChainAddressRepository;

    private ManifestValidationService service;

    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Every rail code resolves to an enabled, chain-bound-and-deployed rail so the
        // demo manifests (which reference real operator rails) validate against a mock
        // catalog without needing a real DB — deployment-address correctness is covered
        // by the dedicated chain-bound tests in ManifestValidationServiceTest.
        lenient().when(paymentRailRepository.findByCode(anyString())).thenAnswer(invocation -> {
            PaymentRail rail = new PaymentRail();
            rail.setId(UUID.randomUUID());
            rail.setCode(invocation.getArgument(0));
            rail.setRailType(PaymentRailType.STABLECOIN);
            rail.setEnabled(true);
            return Optional.of(rail);
        });
        lenient().when(paymentRailChainAddressRepository.existsByPaymentRailIdAndChainConfigId(any(), any()))
                .thenReturn(true);
        service = new ManifestValidationService(
                permissionDefinitionRepository, paymentRailRepository, paymentRailChainAddressRepository);
    }

    @ParameterizedTest(name = "{0}.manifest.json is schema-valid and passes marketplace validation")
    @ValueSource(strings = {"boardroom", "bond-desk", "repo-facility", "repo-markets"})
    void demoManifestIsValid(String slug) throws IOException {
        String manifestRaw = readManifest(slug);

        var result = service.validate(manifestRaw, slug, chainConfigId);

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().slug()).isEqualTo(slug);
        assertThat(result.manifest().requiredPermissions())
                .isNotEmpty()
                .allSatisfy(permission -> assertThat(permission.code()).startsWith(slug + "."));
        assertThat(result.manifest().requiredClaimTopics()).isNotEmpty();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("bond-desk declares its three operator payment rails")
    void bondDeskDeclaresPaymentMethods() throws IOException {
        var result = service.validate(readManifest("bond-desk"), "bond-desk", chainConfigId);

        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().paymentMethods()).hasSize(3)
                .allSatisfy(method -> assertThat(method.methodType()).isEqualTo("RAIL"));
        assertThat(result.manifest().paymentMethods())
                .extracting(ManifestValidationService.PaymentMethod::railCode)
                .containsExactlyInAnyOrder("aueur", "usdc", "erc7573-dvp");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("boardroom declares no payment methods (pure governance, no cash leg)")
    void boardroomDeclaresNoPaymentMethods() throws IOException {
        var result = service.validate(readManifest("boardroom"), "boardroom", chainConfigId);

        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().paymentMethods()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("repo-facility declares its two stablecoin payment rails and only the KYC claim topic")
    void repoFacilityDeclaresPaymentMethodsAndClaims() throws IOException {
        var result = service.validate(readManifest("repo-facility"), "repo-facility", chainConfigId);

        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().paymentMethods()).hasSize(2)
                .allSatisfy(method -> assertThat(method.methodType()).isEqualTo("RAIL"));
        assertThat(result.manifest().paymentMethods())
                .extracting(ManifestValidationService.PaymentMethod::railCode)
                .containsExactlyInAnyOrder("aueur", "usdc");
        assertThat(result.manifest().requiredClaimTopics()).containsExactly(1L);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("repo-markets declares its own permission namespace even though BORROW/CONFIGURE are shared with repo-facility")
    void repoMarketsDeclaresOwnNamespacedPermissions() throws IOException {
        var result = service.validate(readManifest("repo-markets"), "repo-markets", chainConfigId);

        assertThat(result.valid()).isTrue();
        assertThat(result.manifest().requiredPermissions())
                .extracting(ManifestValidationService.RequiredPermission::code)
                .containsExactlyInAnyOrder(
                        "repo-markets.create-market", "repo-markets.curate-vault", "repo-markets.push-price");
        assertThat(result.manifest().paymentMethods()).hasSize(2)
                .extracting(ManifestValidationService.PaymentMethod::railCode)
                .containsExactlyInAnyOrder("aueur", "usdc");
    }

    private String readManifest(String slug) throws IOException {
        var resource = new ClassPathResource("demo/dapps/" + slug + ".manifest.json");
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
