import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BondService } from '../../../../core/api/bond.service';
import { CouponScheduleEntry } from '../../../../core/models';

/**
 * Read-only coupon schedule generated from the bond terms (ICMA day count, business-day
 * convention on the TARGET2 calendar, record date and announcement lead time). The backend's
 * coupon job raises a COUPON corporate action from each SCHEDULED row. Rendered nothing for
 * assets without a schedule (non-bonds, zero-coupon bonds).
 */
@Component({
  selector: 'app-coupon-schedule',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, MatIconModule, MatTooltipModule],
  template: `
    @if (loadError) {
      <p class="cs-error" role="alert">The coupon schedule could not be loaded.</p>
    } @else if (rows.length > 0) {
      <section class="cs-shell" aria-labelledby="cs-title">
        <div class="cs-header">
          <h3 id="cs-title" class="cs-title">Coupon schedule</h3>
          <span class="cs-note">Version {{ version }} · amounts per unit, unrounded</span>
        </div>
        <div class="cs-scroll">
          <table class="cs-table">
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Accrual period</th>
                <th scope="col">Announcement</th>
                <th scope="col">Record date</th>
                <th scope="col">Payment date</th>
                <th scope="col" class="right">Amount / unit</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              @for (r of rows; track r.periodNo + '-' + r.scheduleVersion) {
                <tr>
                  <td class="mono">{{ r.periodNo }}</td>
                  <td class="mono">
                    {{ r.periodStart ? (r.periodStart | date:'dd MMM yyyy') : '—' }} → {{ r.periodEnd ? (r.periodEnd | date:'dd MMM yyyy') : '—' }}
                  </td>
                  <td class="mono">{{ r.announcementDate ? (r.announcementDate | date:'dd MMM yyyy') : '—' }}</td>
                  <td class="mono">{{ r.recordDate ? (r.recordDate | date:'dd MMM yyyy') : '—' }}</td>
                  <td class="mono">{{ r.paymentDate | date:'dd MMM yyyy' }}</td>
                  <td class="mono right">
                    @if (r.amountPerUnit != null) {
                      {{ r.amountPerUnit | number:'1.2-8' }}
                    } @else {
                      <span matTooltip="Floating rate — set once the reference rate is fixed">awaiting fixing</span>
                    }
                  </td>
                  <td><span class="cs-status" [class]="r.couponStatus.toLowerCase()">{{ r.couponStatus }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    }
  `,
  styles: [`
    .cs-shell { margin-bottom: 24px; }
    .cs-header { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
    .cs-title { margin: 0; font-size: 1rem; font-weight: 700; }
    .cs-note { font-size: .75rem; color: var(--rw-text-secondary); }
    .cs-error { color: var(--rw-text-danger); }
    .cs-scroll { overflow-x: auto; }
    .cs-table { width: 100%; border-collapse: collapse; font-size: .8125rem; }
    .cs-table th { text-align: left; font-weight: 600; color: var(--rw-text-secondary); padding: 6px 8px; border-bottom: 1px solid var(--rw-border); white-space: nowrap; }
    .cs-table td { padding: 6px 8px; border-bottom: 1px solid var(--rw-border); white-space: nowrap; }
    .mono { font-family: 'IBM Plex Mono', monospace; }
    .right { text-align: right; }
    .cs-status { font-size: .6875rem; font-weight: 700; letter-spacing: .04em; padding: 2px 8px; border-radius: 10px;
      background: var(--rw-surface-soft); color: var(--rw-text-secondary); }
    .cs-status.paid { color: var(--rw-text-success); }
    .cs-status.missed { color: var(--rw-text-danger); }
    .cs-status.overdue { color: var(--rw-text-warning, #f59e0b); }
  `],
})
export class CouponScheduleComponent implements OnInit {
  @Input({ required: true }) assetId!: string;

  private readonly bondService = inject(BondService);
  private readonly cdr = inject(ChangeDetectorRef);

  rows: CouponScheduleEntry[] = [];
  loadError = false;

  get version(): number {
    return this.rows.reduce((max, r) => Math.max(max, r.scheduleVersion), 0);
  }

  ngOnInit(): void {
    this.bondService.getCouponSchedule(this.assetId).subscribe({
      next: (rows) => { this.rows = rows; this.cdr.markForCheck(); },
      error: () => { this.loadError = true; this.cdr.markForCheck(); },
    });
  }
}
