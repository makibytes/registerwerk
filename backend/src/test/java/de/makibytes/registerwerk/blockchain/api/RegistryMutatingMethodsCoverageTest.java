package de.makibytes.registerwerk.blockchain.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * H9(c): the set of registry-mutating contract functions that need second-source confirmation is an
 * explicit allowlist of function names, no longer a substring match (which silently missed
 * {@code setAddressFrozen}, {@code setMaxBalance}, {@code setNavPerShare}, {@code addModule}, ...).
 *
 * <p>This test keeps the list honest by scanning the backend's own call sites: every
 * {@code new Function("name", ...)} in {@code src/main/java} must be classified - as registry-mutating
 * (needs a second source), as a read-only call, or as explicitly state-changing-but-not-registry-mutating.
 * A new function that is not classified fails the build. Function names that are computed at run time
 * cannot be scanned, so every such call site is registered below with the names it can produce; a new
 * dynamic call site fails the build until it is registered here.
 */
@DisplayName("H9(c): every backend-issued contract function is classified for second-source confirmation")
class RegistryMutatingMethodsCoverageTest {

    /** {@code eth_call} only: never signed, never recorded as a blockchain transaction. */
    private static final Set<String> READ_ONLY = Set.of(
            "assetId", "borrowPaused", "confidentialBalanceOf", "decimals", "depositRequestPayer",
            "docStoreByAssetId", "getConfig", "getDocument", "getModules", "getSuiteAddresses", "healthFactor",
            "isAgent", "isClaimRevoked", "isCountryBlocked", "isFrozen", "isMarket", "isModuleBound",
            "isNomineePool", "isOrgActive", "isRoleRestricted", "isValidSignature", "keyHasPurpose", "name",
            "orgGranted", "orgOf", "owner", "positions", "predictAddress", "price", "roleGranted", "surplusOf",
            "trexFactory");

    /** State-changing calls the backend issues that deliberately do not touch the register. */
    private static final Set<String> NOT_REGISTRY_MUTATING = Set.of("setBorrowPaused", "reconcileCollateral");

    /**
     * Call sites whose function name is computed at run time (file name -> number of such sites), so the
     * scanner cannot read the name. Registered per file so that adding one fails this test.
     */
    private static final Map<String, Integer> DYNAMIC_SITES = Map.of(
            "PaymasterOnchainReader.java", 1,            // read
            "OnchainHandoverVerifier.java", 1,           // read
            "RepoMarketOnchainReader.java", 2,           // reads
            "EvmFactoryDeploymentSupport.java", 3,       // deployVault / deployToken + 2 reads
            "TokenAdminService.java", 2,                 // forcedTransfer / forcedApprove
            "Erc3643LifecycleService.java", 2,           // forcedTransfer / forcedApprove
            "Erc7540AdminService.java", 3,               // fulfil / cancel / force-cancel request
            "WhitelistService.java", 1);                 // whitelist / removeFromWhitelist

    /** The state-changing names those dynamic sites can produce. */
    private static final Map<String, Set<String>> DYNAMIC_MUTATING_NAMES = Map.of(
            "EvmFactoryDeploymentSupport.java", Set.of("deployVault", "deployToken"),
            "TokenAdminService.java", Set.of("forcedTransfer", "forcedApprove"),
            "Erc3643LifecycleService.java", Set.of("forcedTransfer", "forcedApprove"),
            "Erc7540AdminService.java", Set.of("fulfillDepositRequest", "fulfillRedeemRequest",
                    "cancelDepositRequest", "cancelRedeemRequest", "forceCancelDepositRequest",
                    "forceCancelRedeemRequest"),
            "WhitelistService.java", Set.of("whitelist", "removeFromWhitelist"));

    private static final Pattern FUNCTION_LITERAL =
            Pattern.compile("new\\s+(?:org\\.web3j\\.abi\\.datatypes\\.)?Function\\(\\s*\"([^\"]*)\"");
    private static final Pattern FUNCTION_ANY =
            Pattern.compile("new\\s+(?:org\\.web3j\\.abi\\.datatypes\\.)?Function\\(");

    private final BlockchainTxProperties props = new BlockchainTxProperties();

    private record Scan(Set<String> literalNames, Map<String, Integer> dynamicSites, Map<String, String> sources) {}

    private static Scan scan() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).as("run from the backend module directory").isDirectory();
        Set<String> literals = new TreeSet<>();
        Map<String, Integer> dynamic = new TreeMap<>();
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(file);
                int all = (int) FUNCTION_ANY.matcher(text).results().count();
                if (all == 0) continue;
                int named = 0;
                int helpers = 0;   // "" = plain ABI-encoding helper, not a contract call
                Matcher m = FUNCTION_LITERAL.matcher(text);
                while (m.find()) {
                    if (m.group(1).isEmpty()) {
                        helpers++;
                    } else {
                        named++;
                        literals.add(m.group(1));
                    }
                }
                int dynamicCount = all - named - helpers;
                String name = file.getFileName().toString();
                if (dynamicCount > 0) dynamic.merge(name, dynamicCount, Integer::sum);
                sources.put(name, text);
            }
        }
        return new Scan(literals, dynamic, sources);
    }

    @Test
    @DisplayName("every function name used with `new Function(\"name\", ...)` is classified; state-changing ones need a second source")
    void everyLiteralFunctionNameIsClassified() throws IOException {
        Scan scan = scan();

        Set<String> mutating = new TreeSet<>(scan.literalNames());
        mutating.removeAll(READ_ONLY);
        mutating.removeAll(NOT_REGISTRY_MUTATING);

        // the classification sets only contain names the backend really uses (no stale entries)
        assertThat(scan.literalNames()).as("READ_ONLY entries that no call site uses any more")
                .containsAll(READ_ONLY);
        assertThat(scan.literalNames()).as("NOT_REGISTRY_MUTATING entries that no call site uses any more")
                .containsAll(NOT_REGISTRY_MUTATING);

        Set<String> missing = new TreeSet<>();
        for (String name : mutating) {
            if (!props.requiresSecondSource(name)) missing.add(name);
        }
        assertThat(missing)
                .as("state-changing backend calls that would be completed without a second-source receipt check")
                .isEmpty();

        for (String name : NOT_REGISTRY_MUTATING) {
            assertThat(props.requiresSecondSource(name)).as(name + " is explicitly not registry-mutating").isFalse();
        }
        assertThat(RegistryMutatingMethods.NOT_REGISTRY_MUTATING)
                .containsExactlyInAnyOrderElementsOf(lowerAll(NOT_REGISTRY_MUTATING));
        assertThat(RegistryMutatingMethods.MUTATING).doesNotContainAnyElementsOf(
                RegistryMutatingMethods.NOT_REGISTRY_MUTATING);
        assertThat(RegistryMutatingMethods.MUTATING).doesNotContainAnyElementsOf(lowerAll(READ_ONLY));
    }

    @Test
    @DisplayName("the allowlist holds no stale names: every entry is a function the backend actually issues")
    void allowlistHasNoStaleEntries() throws IOException {
        Scan scan = scan();
        Set<String> used = new TreeSet<>(lowerAll(scan.literalNames()));
        DYNAMIC_MUTATING_NAMES.values().forEach(names -> used.addAll(lowerAll(names)));

        assertThat(used).containsAll(RegistryMutatingMethods.MUTATING);
    }

    @Test
    @DisplayName("an unclassified or missing method name fails closed: it requires a second source")
    void unknownNamesFailClosed() {
        assertThat(props.requiresSecondSource("someFutureAdminFunction")).isTrue();
        assertThat(props.requiresSecondSource(null)).isTrue();
        assertThat(props.requiresSecondSource("  ")).isTrue();
        assertThat(props.requiresSecondSource("SETADDRESSFROZEN")).as("names match case-insensitively").isTrue();
        assertThat(RegistryMutatingMethods.isClassified("someFutureAdminFunction")).isFalse();
        assertThat(RegistryMutatingMethods.isClassified("setBorrowPaused")).isTrue();
    }

    @Test
    @DisplayName("configured extras are exact names (no substring match) and * means every transaction")
    void configuredExtrasAreExactNames() {
        BlockchainTxProperties strict = new BlockchainTxProperties();
        strict.setSecondSourceMethods(java.util.List.of("reconcileCollateral"));
        assertThat(strict.requiresSecondSource("reconcileCollateral")).isTrue();
        assertThat(strict.requiresSecondSource("setBorrowPaused")).as("not a substring match").isFalse();

        BlockchainTxProperties everything = new BlockchainTxProperties();
        everything.setSecondSourceMethods(java.util.List.of("*"));
        assertThat(everything.requiresSecondSource("setBorrowPaused")).isTrue();
    }

    @Test
    @DisplayName("against the built contract ABIs: no read-only name is state-changing, every allowlisted name that "
            + "exists in an ABI is state-changing (skipped when contracts/out is not built)")
    void classificationAgreesWithContractAbis() throws IOException {
        Path out = Path.of("..", "contracts", "out");
        assumeTrue(Files.isDirectory(out), "contracts/out is not built (run `forge build`)");

        Map<String, Set<String>> mutability = new HashMap<>();
        JsonMapper mapper = JsonMapper.builder().build();
        try (Stream<Path> files = Files.walk(out, 2)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                JsonNode abi;
                try {
                    abi = mapper.readTree(file.toFile()).path("abi");
                } catch (RuntimeException e) {
                    continue;   // build-info and the like
                }
                for (JsonNode entry : abi) {
                    if ("function".equals(entry.path("type").asString())) {
                        mutability.computeIfAbsent(entry.path("name").asString().toLowerCase(Locale.ROOT),
                                k -> new TreeSet<>()).add(entry.path("stateMutability").asString());
                    }
                }
            }
        }
        assumeTrue(!mutability.isEmpty(), "no ABIs found under contracts/out");

        Set<String> readOnlyButWritable = new TreeSet<>();
        for (String name : READ_ONLY) {
            Set<String> kinds = mutability.getOrDefault(name.toLowerCase(Locale.ROOT), Set.of());
            if (kinds.contains("nonpayable") || kinds.contains("payable")) readOnlyButWritable.add(name);
        }
        assertThat(readOnlyButWritable).as("names classified READ_ONLY that a contract exposes as state-changing")
                .isEmpty();

        Set<String> mutatingButViewOnly = new TreeSet<>();
        for (String name : RegistryMutatingMethods.MUTATING) {
            Set<String> kinds = mutability.get(name);
            if (kinds != null && !kinds.contains("nonpayable") && !kinds.contains("payable")) {
                mutatingButViewOnly.add(name);
            }
        }
        assertThat(mutatingButViewOnly).as("allowlisted names that every contract exposes as view/pure").isEmpty();

        for (String name : lowerAll(NOT_REGISTRY_MUTATING)) {
            Set<String> kinds = mutability.getOrDefault(name, Set.of());
            assertThat(kinds).as(name + " is a state-changing call that is deliberately exempt")
                    .anyMatch(k -> k.equals("nonpayable") || k.equals("payable"));
        }
    }

    private static Set<String> lowerAll(Set<String> names) {
        Set<String> lower = new TreeSet<>();
        names.forEach(n -> lower.add(n.toLowerCase(Locale.ROOT)));
        return lower;
    }

    @Test
    @DisplayName("the court-ordered freeze, the compliance limits and the NAV strike are second-source calls")
    void namedGapsAreClosed() {
        for (String name : new String[] {"setAddressFrozen", "setMaxBalance", "setNavPerShare", "setMaxInvestors",
                "setTransferCooldown", "blockCountry", "addModule", "removeModule", "setNomineePool", "syncHolders",
                "setSupplyCap", "setSlotSupplyCap", "setDepositCap", "freezePartialTokens", "unfreezePartialTokens",
                "freezeToken", "unfreezeToken", "forcedApprove", "fulfillDepositRequest", "forceCancelRedeemRequest",
                "registerOrg", "grantToOrg", "setStatus", "updateManifest", "registerDapp"}) {
            assertThat(props.requiresSecondSource(name)).as(name).isTrue();
        }
    }

    @Test
    @DisplayName("function names computed at run time are registered here with the names they can produce")
    void dynamicCallSitesAreRegistered() throws IOException {
        Scan scan = scan();
        assertThat(scan.dynamicSites())
                .as("`new Function(<non-literal name>, ...)` call sites: register new ones in DYNAMIC_SITES / "
                        + "DYNAMIC_MUTATING_NAMES and classify the names they can produce")
                .isEqualTo(DYNAMIC_SITES);

        DYNAMIC_MUTATING_NAMES.forEach((file, names) -> {
            for (String name : names) {
                assertThat(scan.sources().get(file)).as(file + " still mentions \"" + name + "\"")
                        .contains("\"" + name + "\"");
                assertThat(props.requiresSecondSource(name)).as(name).isTrue();
            }
        });
    }
}
