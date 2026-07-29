import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { Jurisdiction, LendingMarket } from '../../core/models';

const JURISDICTION_LABELS: Record<Jurisdiction, string> = {
  DE_EWPG: 'Germany (eWpG)',
  LU_CSSF: 'Luxembourg (CSSF)',
  FR_AMF: 'France (AMF)',
  LI_TVTG: 'Liechtenstein (TVTG)',
};

/**
 * Jurisdiction-aware compliance context for the repo/lending workspace — surfaces the same
 * rationale `docs/platform/defi-interoperability.md` documents (nominee-pool custody, MiCAR
 * applicability, the open margin-lending legal-review item) directly to the trader, rather than
 * leaving it as backend-only documentation. Pass `[market]` for a market-specific reading of
 * `kyc.api.JurisdictionRequirementConfig`'s per-jurisdiction deltas; omit it for the generic
 * DE_EWPG framing used on pages not scoped to one specific market.
 */
@Component({
  selector: 'app-lending-compliance-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatCardModule],
  template: `
    <mat-card class="compliance-banner">
      <mat-card-content>
        <mat-icon class="shield-icon">verified_user</mat-icon>
        <div class="text">
          <strong>Compliant by design ({{ jurisdictionLabel }})</strong>
          <span>
            Your pledged security stays in collective custody with the operator as a verified
            nominee holder — the position, coupon, and redemption rights remain yours.
            @if (micarApplicable === false) {
              MiCAR applies only to the stablecoin you borrow, not to the security itself.
            } @else if (micarApplicable === true) {
              MiCAR considerations apply to this jurisdiction's issuance beyond just the payment
              leg — see the operator's jurisdiction compliance profile for details.
            }
            Margin-lending rules are still subject to jurisdiction-specific legal review before
            scaled production use.
          </span>
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .compliance-banner {
      background: var(--rw-accent-light, #ccfbf1);
      border: 1px solid var(--rw-accent, #0d9488);
      border-radius: 10px;
      margin-bottom: 16px;
    }
    mat-card-content {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 12px 16px !important;
    }
    .shield-icon { color: var(--rw-accent, #0d9488); flex-shrink: 0; margin-top: 2px; }
    .text {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 12.5px;
      color: var(--rw-text-primary);
      line-height: 1.5;
    }
  `],
})
export class LendingComplianceBannerComponent {
  @Input() market: LendingMarket | null = null;

  get jurisdictionLabel(): string {
    const jurisdiction = this.market?.jurisdiction;
    return jurisdiction ? JURISDICTION_LABELS[jurisdiction] : 'Germany (eWpG)';
  }

  get micarApplicable(): boolean | null {
    return this.market?.micarApplicable ?? false;
  }
}
