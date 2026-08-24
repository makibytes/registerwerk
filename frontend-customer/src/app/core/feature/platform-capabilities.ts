import { InjectionToken, Injectable, inject } from '@angular/core';

export interface FeatureCapability {
  enabled: boolean;
  reason: string | null;
}

export interface PlatformCapabilities {
  securitiesBackedLending: FeatureCapability;
  repoDesk: FeatureCapability;
}

export const DISABLED_PLATFORM_CAPABILITIES: PlatformCapabilities = {
  securitiesBackedLending: { enabled: false, reason: 'Capability state could not be loaded' },
  repoDesk: { enabled: false, reason: 'Capability state could not be loaded' },
};

export const PLATFORM_CAPABILITIES = new InjectionToken<PlatformCapabilities>('PLATFORM_CAPABILITIES', {
  providedIn: 'root',
  factory: () => DISABLED_PLATFORM_CAPABILITIES,
});

@Injectable({ providedIn: 'root' })
export class PlatformCapabilitiesService {
  private readonly capabilities = inject(PLATFORM_CAPABILITIES);

  lendingEnabled(): boolean {
    return this.capabilities.securitiesBackedLending.enabled;
  }

  repoDeskEnabled(): boolean {
    return this.capabilities.repoDesk.enabled;
  }

  lendingDisabledReason(): string | null {
    return this.capabilities.securitiesBackedLending.reason;
  }
}
