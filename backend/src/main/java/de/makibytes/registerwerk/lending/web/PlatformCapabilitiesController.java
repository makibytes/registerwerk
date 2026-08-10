package de.makibytes.registerwerk.lending.web;

import de.makibytes.registerwerk.lending.internal.LendingProperties;
import de.makibytes.registerwerk.repo.api.RepoDeskCapability;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime feature state consumed by the browser before it renders navigation. Keeping this
 * deployment state out of Angular's build-time environment means the same immutable frontend
 * image can be used in a disabled production deployment and in the explicitly enabled demo.
 */
@RestController
@RequestMapping("/api/v1/public/platform-capabilities")
public class PlatformCapabilitiesController {

    private final LendingProperties lending;
    private final RepoDeskCapability repoDesk;

    public PlatformCapabilitiesController(LendingProperties lending, RepoDeskCapability repoDesk) {
        this.lending = lending;
        this.repoDesk = repoDesk;
    }

    @GetMapping
    public ResponseEntity<PlatformCapabilitiesResponse> capabilities() {
        return ResponseEntity.ok(new PlatformCapabilitiesResponse(
                new FeatureCapability(lending.isReleased(),
                        lending.isReleased() ? null : "Pending explicit release approval"),
                new FeatureCapability(repoDesk.isReleased(),
                        repoDesk.isReleased() ? null : "Pending explicit release approval")));
    }

    public record PlatformCapabilitiesResponse(
            FeatureCapability securitiesBackedLending,
            FeatureCapability repoDesk) {}

    public record FeatureCapability(boolean enabled, String reason) {}
}
