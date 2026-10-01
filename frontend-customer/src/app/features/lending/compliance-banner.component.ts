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
        <mat-icon class="shield-icon">info</mat-icon>
        <div class="text">
          <strong>Risk notice ({{ jurisdictionLabel }})</strong>
          <span>
            Borrowing pledges your securities: the collateral is transferred to the market contract
            for the life of the loan. Coupons and redemption proceeds on pledged units are held
            pending the operator's process and are not paid out to you while the units are pledged.
            If the loan is liquidated, title to the pledged units passes to the buyer. Access is
            intended for professional clients; margin-lending rules are subject to
            jurisdiction-specific legal review, and the backend cannot block an on-chain borrow
            made directly against the contract.
            @if (micarApplicable === true) {
              MiCAR considerations may apply to this jurisdiction's issuance beyond the payment leg.
            }
            This is not legal or investment advice.
          </span>
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .compliance-banner {
      background: var(--rw-surface-raised);
      border: 1px solid var(--rw-border);
      border-radius: 10px;
      margin-bottom: 16px;
    }
    mat-card-content {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 12px 16px !important;
    }
    .shield-icon { color: var(--rw-text-secondary); flex-shrink: 0; margin-top: 2px; }
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
    return jurisdiction ? JURISDICTION_LABELS[jurisdiction] : 'jurisdiction-specific controls';
  }

  get micarApplicable(): boolean | null {
    return this.market ? this.market.micarApplicable : null;
  }
}
