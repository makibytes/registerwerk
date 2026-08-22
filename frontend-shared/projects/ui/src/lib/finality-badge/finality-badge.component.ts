import { Component, Input } from '@angular/core';

/** Mirrors the backend's `finality.api.FinalityLevel` (three-tier: PROVISIONAL/SAFE/FINALIZED,
 *  plus the terminal ORPHANED). Kept as a plain string union here (not shared with the entity
 *  models file) since this component is used by both apps against slightly different DTO shapes. */
export type FinalityLevelName = 'PROVISIONAL' | 'SAFE' | 'FINALIZED' | 'ORPHANED';

/**
 * Renders a finality state as a colored badge, reusing the same `.status-badge`/`.status-*` CSS
 * convention as `StatusBadgeComponent` (each app's global styles.scss defines the color rules).
 *
 * <p>Deliberately takes `level` (for the CSS class — always the stable technical enum name) and
 * `label` (the display text) as two separate inputs, rather than deriving both from one string
 * the way `StatusBadgeComponent` does: the portfolio plan's "two vocabularies, one model" design
 * resolves the display text server-side by the caller's role (see backend's `TokenTransferMapper`
 * / `TokenTransferResponse.finalityLabel`) — customer-facing callers get "Being confirmed" while
 * the badge still needs `PROVISIONAL` to pick the right color, since there's no CSS class for
 * "being-confirmed". The frontend never decides which vocabulary to show; it only renders what
 * the server already resolved.
 */
@Component({
  selector: 'app-finality-badge',
  standalone: true,
  template: `<span class="status-badge" [class]="'status-badge status-' + level.toLowerCase()">{{ label }}</span>`,
})
export class FinalityBadgeComponent {
  @Input({ required: true }) level!: FinalityLevelName;
  @Input({ required: true }) label!: string;
}
