package de.makibytes.registerwerk.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Read-only, validated view of the deployment artifact shared by backend and frontends. */
@Component
public class DemoOnchainManifest {
    private static final Pattern PUBLIC_KEY = Pattern.compile(
            "^(CHAIN_ID|.*_WALLET|.*_TOKEN|.*_VAULT|.*_MARKET|.*_FACTORY|.*_REGISTRY|.*_ORACLE|ECOSYSTEM_TIR)$");

    private final String path;

    public DemoOnchainManifest(@Value("${registerwerk.lending.local-demo-addresses-file:}") String path) {
        this.path = path;
    }

    public Map<String, String> publicValues() {
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            return Collections.emptyMap();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            properties.load(input);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read demo on-chain manifest", e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted()
                .filter(key -> PUBLIC_KEY.matcher(key).matches())
                .forEach(key -> values.put(key, properties.getProperty(key)));
        return Collections.unmodifiableMap(values);
    }
}
