package de.makibytes.registerwerk.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime discovery endpoint; avoids baking ephemeral Anvil addresses into frontend images. */
@RestController
@RequestMapping("/api/v1/demo/onchain")
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class DemoOnchainManifestController {
    private final DemoOnchainManifest manifest;

    public DemoOnchainManifestController(DemoOnchainManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        Map<String, String> values = manifest.publicValues();
        if (values.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "schemaVersion", 1,
                "network", "Local Anvil",
                "contracts", values));
    }
}
