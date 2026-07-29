package de.makibytes.registerwerk.marketplace.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates dApp manifests against the JSON schema plus marketplace semantics:
 * the slug must match the listing, image references must be digest-pinned (schema),
 * declared permissions must live in the dApp's own namespace or already exist
 * as platform permission definitions (no namespace squatting), and payment-method
 * entries referencing an operator rail must point at an enabled rail. Custom payment
 * methods pass through (advisory model) and are flagged for the operator at review.
 */
@Service
public class ManifestValidationService {

    /** Parsed manifest fields the marketplace persists alongside the raw bytes. */
    public record RequiredPermission(String code, String rationale) {}

    /** A payment method declared in the manifest: rail reference or custom descriptor. */
    public record PaymentMethod(
            String methodType, // RAIL | CUSTOM
            String railCode,
            String customName,
            String customDescription,
            String currency,
            String note) {}

    public record ParsedManifest(
            String slug,
            String name,
            String version,
            String category,
            String description,
            String docsUrl,
            String contact,
            String license,
            String pricingNote,
            List<RequiredPermission> requiredPermissions,
            List<Long> requiredClaimTopics,
            List<PaymentMethod> paymentMethods) {}

    public record ValidationResult(boolean valid, List<String> errors, ParsedManifest manifest) {}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchema schema;
    private final PermissionDefinitionRepository permissionDefinitionRepository;
    private final PaymentRailRepository paymentRailRepository;
    private final PaymentRailChainAddressRepository paymentRailChainAddressRepository;

    ManifestValidationService(PermissionDefinitionRepository permissionDefinitionRepository,
                              PaymentRailRepository paymentRailRepository,
                              PaymentRailChainAddressRepository paymentRailChainAddressRepository) {
        this.permissionDefinitionRepository = permissionDefinitionRepository;
        this.paymentRailRepository = paymentRailRepository;
        this.paymentRailChainAddressRepository = paymentRailChainAddressRepository;
        this.schema = loadSchema();
    }

    private JsonSchema loadSchema() {
        try (InputStream in = new ClassPathResource("schemas/dapp-manifest.schema.json").getInputStream()) {
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    .formatAssertionsEnabled(true)
                    .build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(objectMapper.readTree(in), config);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load dapp-manifest.schema.json", e);
        }
    }

    /**
     * Validates the raw manifest for a listing with the given slug, anchored on
     * {@code chainConfigId}. The raw bytes are the signature/hash input — callers must
     * persist them verbatim.
     */
    public ValidationResult validate(String manifestRaw, String expectedSlug, UUID chainConfigId) {
        List<String> errors = new ArrayList<>();

        JsonNode root;
        try {
            root = objectMapper.readTree(manifestRaw);
        } catch (IOException e) {
            return new ValidationResult(false, List.of("Manifest is not valid JSON: " + e.getMessage()), null);
        }

        Set<ValidationMessage> schemaErrors = schema.validate(root);
        schemaErrors.stream().map(ValidationMessage::getMessage).forEach(errors::add);
        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, null);
        }

        String slug = root.path("slug").asText();
        if (!slug.equals(expectedSlug)) {
            errors.add("Manifest slug '" + slug + "' does not match the listing slug '" + expectedSlug + "'");
        }

        List<RequiredPermission> permissions = new ArrayList<>();
        for (JsonNode permission : root.path("requiredPermissions")) {
            String code = permission.path("code").asText();
            boolean ownNamespace = code.startsWith(slug + ".");
            if (!ownNamespace && !permissionDefinitionRepository.existsByCode(code)) {
                errors.add("Permission '" + code + "' is outside the dApp's namespace ('" + slug
                        + ".*') and is not a known platform permission");
            }
            permissions.add(new RequiredPermission(code, permission.path("rationale").asText(null)));
        }

        List<Long> claimTopics = new ArrayList<>();
        root.path("requiredClaimTopics").forEach(topic -> claimTopics.add(topic.asLong()));

        List<PaymentMethod> paymentMethods = parsePaymentMethods(root, errors, chainConfigId);

        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, null);
        }

        ParsedManifest parsed = new ParsedManifest(
                slug,
                root.path("name").asText(),
                root.path("version").asText(),
                root.path("category").asText("general"),
                root.path("description").asText(),
                root.path("docsUrl").asText(null),
                root.path("contact").asText(null),
                root.path("license").asText(null),
                root.path("pricingNote").asText(null),
                permissions,
                claimTopics,
                paymentMethods);
        return new ValidationResult(true, List.of(), parsed);
    }

    private List<PaymentMethod> parsePaymentMethods(JsonNode root, List<String> errors, UUID chainConfigId) {
        List<PaymentMethod> paymentMethods = new ArrayList<>();
        for (JsonNode method : root.path("paymentMethods")) {
            String note = method.path("note").asText(null);
            if (method.hasNonNull("rail")) {
                String railCode = method.path("rail").asText();
                var rail = paymentRailRepository.findByCode(railCode).filter(PaymentRail::isEnabled);
                if (rail.isEmpty()) {
                    errors.add("Payment rail '" + railCode
                            + "' is not an enabled operator-curated rail — pick one from the payment-rail"
                            + " catalog or declare a custom payment method");
                } else if (rail.get().getRailType().isChainBound()
                        && !paymentRailChainAddressRepository
                                .existsByPaymentRailIdAndChainConfigId(rail.get().getId(), chainConfigId)) {
                    errors.add("Payment rail '" + railCode
                            + "' has no deployed contract address on this dApp's anchor chain — the operator"
                            + " must configure a chain address for this rail before it can be declared here");
                }
                paymentMethods.add(new PaymentMethod("RAIL", railCode, null, null, null, note));
            } else {
                JsonNode custom = method.path("custom");
                paymentMethods.add(new PaymentMethod("CUSTOM", null,
                        custom.path("name").asText(),
                        custom.path("description").asText(),
                        custom.path("currency").asText(null),
                        note));
            }
        }
        return paymentMethods;
    }
}
