import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { VaultService } from '../../../../core/api/vault.service';
import { VaultDealingState, VaultRequest, VaultStateSummary } from '../../../../core/models';
import { withDualControl } from '../../../../shared/components/step-up/with-dual-control';
import {
  canFulfil, formatCutoffUtc, fulfilHint, parseCutoffUtc, periodLabel, periodSecondsOf,
} from './vault-dealing';
import {
  StepUpDialogComponent, StepUpDialogResult,
} from '../../../../shared/components/step-up/step-up-dialog.component';

import { RwDecimalPipe } from '@registerwerk/ui';

@Component({
  selector: 'app-vault-requests',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule, MatButtonModule, MatIconModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatTooltipModule, DatePipe, DecimalPipe, SlicePipe, RwDecimalPipe,
  ],
  template: `
    <div class="req-shell">
      <div class="req-header">
        <span class="badge">ASYNC VAULT</span>
        <h3 class="req-title">Pending requests</h3>
        <div class="req-controls">
          <span class="req-count">{{ pending.length }} pending</span>
          <button type="button" mat-stroked-button class="btn-refresh" (click)="load()" aria-label="Refresh">
            <mat-icon>refresh</mat-icon>
          </button>
        </div>
      </div>

      @if (struckNav) {
        <div class="nav-struck" data-testid="struck-nav">
          <mat-icon>lock</mat-icon>
          <span>
            Fulfilment settles at the NAV struck on-chain:
            <strong class="mono">{{ struckNav | number:'1.2-8' }}</strong>
            @if (vaultState?.latestNavStrikeAt; as strikeAt) {
              <span class="dimmed"> · struck {{ strikeAt | date:'dd MMM yyyy HH:mm' }}</span>
            }
          </span>
          <span class="nav-hint">The executed NAV is recorded from the on-chain fulfilment event.</span>
        </div>
      } @else {
        <div class="nav-warning">
          <mat-icon>info_outline</mat-icon>
          <span>Strike a current NAV before fulfilling requests — fulfillment uses the latest NAV confirmed on-chain.</span>
        </div>
      }

      @if (vaultState?.dealing; as dealing) {
        @if (dealing.applicable) {
          <div class="dealing-banner" [class.warn]="!dealing.configured || !dealing.available" data-testid="dealing-banner">
            <mat-icon>schedule</mat-icon>
            <span>
              @if (!dealing.available) {
                The dealing cut-off could not be read from the chain — Fulfil stays available, the chain decides.
              } @else if (dealing.configured) {
                Forward pricing: dealing cut-off
                <strong class="mono">{{ cutoffLabel(dealing) }} UTC</strong> ({{ periodText(dealing) }}).
                A request placed now deals at
                <strong class="mono">{{ dealing.nextDealingPoint | date:'dd MMM yyyy HH:mm':'UTC' }} UTC</strong>.
                @if (dealing.navStruckAt; as struckAt) {
                  <span class="dimmed"> Latest NAV struck on-chain {{ struckAt | date:'dd MMM yyyy HH:mm':'UTC' }} UTC.</span>
                }
              } @else {
                No dealing cut-off configured — requests settle at the NAV struck at execution time (late-trading
                exposure) and production mode refuses new subscriptions on this vault.
              }
            </span>
            <button type="button" mat-stroked-button class="btn-cutoff" data-testid="dealing-cutoff-btn"
              (click)="openDealingCutoff()">
              {{ dealing.configured ? 'Change dealing cut-off' : 'Set dealing cut-off' }}
            </button>
          </div>
        }
      }

      @if (pending.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">done_all</mat-icon>
          <p>No pending requests.</p>
        </div>
      } @else {
        <div class="req-table">
          <div class="req-row header">
            <span></span>
            <span>Request ID</span>
            <span>Type</span>
            <span>Owner</span>
            <span class="right">Amount</span>
            <span class="right">Requested</span>
            <span>Dealing point (UTC)</span>
            <span>Status</span>
            <span></span>
          </div>

          @for (req of pending; track req.id) {
            <div class="req-row" [class.checked]="selected.has(req.id)" [class.held]="req.complianceHold">
              <mat-checkbox
                color="primary"
                [disabled]="!canFulfil(req)"
                [checked]="selected.has(req.id)"
                (change)="toggle(req.id, $event.checked)">
              </mat-checkbox>

              <span class="mono dimmed">#{{ req.requestId }}</span>

              <span class="type-badge" [class.deposit]="req.requestType === 'DEPOSIT'" [class.redeem]="req.requestType === 'REDEEM'">
                {{ req.requestType }}
              </span>

              <span class="addr mono" [matTooltip]="'Owner ' + req.ownerAddr + ' · controller ' + req.controllerAddr">
                {{ req.ownerAddr | slice:0:10 }}…
              </span>

              <span class="right mono amount">
                @if (req.requestType === 'DEPOSIT') {
                  {{ req.assetAmount | rwDecimal }}
                } @else {
                  {{ req.shareAmount | rwDecimal }} shares
                }
              </span>

              <span class="right dimmed small">{{ req.requestedAt | date:'dd MMM HH:mm' }}</span>

              <span class="dealing-cell small mono" data-testid="dealing-point">
                @if (req.dealingPoint) {
                  {{ req.dealingPoint | date:'dd MMM HH:mm':'UTC' }}
                } @else {
                  <span class="dimmed" matTooltip="Placed before a dealing cut-off was configured — settles at the NAV struck at execution time">—</span>
                }
              </span>

              <span>
                @if (req.complianceHold) {
                  <span class="status-chip hold" [matTooltip]="req.complianceHoldReason ?? ''">
                    <mat-icon>block</mat-icon> Blocked: compliance hold
                  </span>
                } @else if (req.awaitingConfirmation) {
                  <span class="status-chip waiting"><mat-icon>hourglass_top</mat-icon> Awaiting confirmation</span>
                } @else if (req.awaitingNavStrike) {
                  <span class="status-chip waiting" data-testid="awaiting-nav-strike" [matTooltip]="hint(req)">
                    <mat-icon>schedule</mat-icon> Waiting for NAV strike
                  </span>
                } @else {
                  <span class="status-chip ready">Ready</span>
                }
              </span>

              <div class="row-actions">
                <button type="button" mat-icon-button class="btn-fulfill" [disabled]="!canFulfil(req)"
                  (click)="fulfill(req)" [matTooltip]="hint(req)" aria-label="Fulfil request">
                  <mat-icon>check_circle_outline</mat-icon>
                </button>
                <button type="button" mat-icon-button class="btn-cancel" [disabled]="!actionable(req)"
                  (click)="cancel(req)" matTooltip="Cancel" aria-label="Cancel request">
                  <mat-icon>cancel</mat-icon>
                </button>
                <button type="button" mat-icon-button class="btn-force" [disabled]="req.awaitingConfirmation || busy.has(req.id)"
                  (click)="openForceCancel(req)" matTooltip="Force-cancel on a legal basis (step-up, 4-eyes)"
                  aria-label="Force-cancel request">
                  <mat-icon>gavel</mat-icon>
                </button>
              </div>
            </div>
          }
        </div>

        @if (selected.size > 0) {
          <div class="bulk-bar">
            <span class="bulk-count">{{ selected.size }} selected</span>
            <button type="button" mat-flat-button class="btn-bulk-fulfill" [disabled]="busy.size > 0" (click)="fulfillSelected()">
              <mat-icon>done_all</mat-icon>
              Fulfill {{ selected.size }} request{{ selected.size > 1 ? 's' : '' }}
            </button>
          </div>
        }
      }

      @if (settled.length > 0) {
        <h4 class="settled-title">Recently fulfilled — executed on-chain</h4>
        <div class="req-table settled">
          <div class="req-row settled-row header">
            <span>Request ID</span>
            <span>Type</span>
            <span class="right">Executed NAV</span>
            <span class="right">Shares</span>
            <span class="right">Assets</span>
            <span class="right">Fulfilled</span>
          </div>
          @for (req of settled; track req.id) {
            <div class="req-row settled-row">
              <span class="mono dimmed">#{{ req.requestId }}</span>
              <span class="type-badge" [class.deposit]="req.requestType === 'DEPOSIT'" [class.redeem]="req.requestType === 'REDEEM'">
                {{ req.requestType }}
              </span>
              <span class="right mono amount">
                @if (req.navAtFulfill != null) {
                  {{ req.navAtFulfill | number:'1.2-8' }}
                } @else {
                  <span class="status-chip hold" [matTooltip]="req.reviewNote ?? ''">Needs review</span>
                }
              </span>
              <span class="right mono">{{ req.shareAmount | rwDecimal }}</span>
              <span class="right mono">{{ req.assetAmount | rwDecimal }}</span>
              <span class="right dimmed small">{{ req.fulfilledAt | date:'dd MMM HH:mm' }}</span>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #dealingCutoffDialog>
      <h2 mat-dialog-title>Dealing cut-off</h2>
      <mat-dialog-content class="force-form">
        <p class="force-note">
          Requests placed after the cut-off settle only at a NAV struck after the next one, so nobody deals at an
          already-known NAV. Requests already placed keep their dealing point. Needs step-up and a second approver.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Daily cut-off (UTC)</mat-label>
          <input matInput type="time" [(ngModel)]="cutoffForm.time" data-testid="cutoff-time" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Dealing period (hours)</mat-label>
          <input matInput type="number" min="1" max="744" step="1" [(ngModel)]="cutoffForm.periodHours"
            data-testid="cutoff-period" />
          <mat-hint>24 = daily, 168 = weekly (1 hour to 31 days)</mat-hint>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-flat-button color="primary" [disabled]="!cutoffFormValid()"
          data-testid="cutoff-submit" (click)="submitDealingCutoff()">
          Continue to step-up
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #forceCancelDialog>
      <h2 mat-dialog-title>Force-cancel request #{{ forceTarget?.requestId }}</h2>
      <mat-dialog-content class="force-form">
        <p class="force-note">
          The request's escrow ({{ forceTarget?.requestType === 'DEPOSIT' ? 'subscription assets' : 'escrowed shares' }})
          leaves the vault to the destination below, recorded on-chain with the legal basis. This cannot be undone in place.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Destination address</mat-label>
          <input matInput [(ngModel)]="forceForm.to" placeholder="0x…" autocomplete="off" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal basis</mat-label>
          <textarea matInput rows="3" [(ngModel)]="forceForm.legalBasis"
            placeholder="Court order / authority reference"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-flat-button color="warn" [disabled]="!forceFormValid()" (click)="submitForceCancel()">
          Continue to step-up
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host {
      display: block;
      --accent: var(--rw-accent);
      --surface: var(--rw-surface);
      --border: var(--rw-border);
    }

    .req-shell { padding: 1.5rem 0; }

    .req-header {
      display: flex;
      align-items: center;
      gap: 1rem;
      margin-bottom: 1.25rem;
      flex-wrap: wrap;
    }

    .badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .625rem;
      letter-spacing: .2em;
      color: var(--accent);
      background: var(--rw-accent-subtle);
      border: 1px solid var(--border);
      border-radius: 2px;
      padding: .2rem .625rem;
    }

    .req-title {
      font-family: 'Manrope Variable', sans-serif;
      font-size: 1rem;
      font-weight: 700;
      color: var(--rw-text-primary);
      margin: 0;
      flex: 1;
    }

    .req-controls {
      display: flex;
      align-items: center;
      gap: .75rem;
    }

    .req-count {
      font-size: .8125rem;
      color: var(--rw-text-secondary);
      font-family: 'IBM Plex Mono', monospace;
    }

    .btn-refresh { border-color: var(--border); color: var(--rw-text-secondary); }

    .nav-warning {
      display: flex;
      align-items: center;
      gap: .625rem;
      padding: .75rem 1rem;
      background: var(--rw-accent-subtle);
      border: 1px solid var(--rw-pending-fg);
      border-radius: 6px;
      color: var(--rw-text-warning);
      font-size: .8125rem;
      margin-bottom: 1rem;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 3rem 0;
      color: var(--rw-text-secondary);
    }

    .empty-icon {
      font-size: 3rem; height: 3rem; width: 3rem;
      color: var(--rw-text-success);
      margin-bottom: .75rem;
    }

    .req-table { display: flex; flex-direction: column; }

    .req-row {
      display: grid;
      grid-template-columns: 36px 90px 80px 1fr 120px 100px 120px 190px 120px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border-subtle);
      font-size: .8125rem;
      border-radius: 4px;
      transition: background .1s;
    }

    .req-row:hover { background: var(--rw-surface-soft); }

    .req-row.checked { background: var(--rw-accent-subtle); }

    .req-row.header {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .6875rem;
      letter-spacing: .06em;
      color: var(--rw-text-secondary);
      background: var(--rw-surface-soft);
      border-radius: 4px 4px 0 0;
    }

    .mono { font-family: 'IBM Plex Mono', monospace; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .addr { font-size: .8125rem; }
    .right { text-align: right; }
    .amount { color: var(--rw-text-primary); font-weight: 600; }

    .type-badge {
      display: inline-flex;
      align-items: center;
      padding: .125rem .5rem;
      border-radius: 3px;
      font-family: 'IBM Plex Mono', monospace;
      font-size: .625rem;
      letter-spacing: .06em;
      font-weight: 700;
    }

    .type-badge.deposit { background: var(--rw-approved-bg); color: var(--rw-text-success); }
    .type-badge.redeem { background: var(--rw-pending-bg); color: var(--rw-text-warning); }

    .row-actions { display: flex; gap: .25rem; justify-content: flex-end; }

    .btn-fulfill { color: var(--rw-text-success); }
    .btn-cancel { color: var(--rw-text-danger); }

    .bulk-bar {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem;
      background: var(--rw-accent-subtle);
      border: 1px solid var(--border);
      border-radius: 0 0 6px 6px;
      margin-top: .5rem;
    }

    .bulk-count {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .8125rem;
      color: var(--accent);
      font-weight: 700;
    }

    .btn-bulk-fulfill {
      background: var(--accent) !important;
      color: var(--rw-accent-contrast) !important;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: .5rem;
    }

    .btn-bulk-fulfill:disabled { opacity: .5; }

    .btn-force { color: var(--rw-text-warning); }

    .req-row.held { background: var(--rw-rejected-bg); }

    .status-chip {
      display: inline-flex;
      align-items: center;
      gap: .25rem;
      padding: .125rem .5rem;
      border-radius: var(--rw-radius-sm);
      font-size: .6875rem;
      font-weight: 600;
      white-space: nowrap;
    }
    .status-chip mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .status-chip.hold { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
    .status-chip.waiting { background: var(--rw-pending-bg); color: var(--rw-pending-fg); }
    .status-chip.ready { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }

    .nav-struck {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: .625rem;
      padding: .75rem 1rem;
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius-md);
      color: var(--rw-text-primary);
      font-size: .8125rem;
      margin-bottom: 1rem;
    }
    .nav-struck mat-icon { color: var(--rw-accent); }
    .nav-hint { color: var(--rw-text-muted); font-size: .75rem; flex-basis: 100%; }

    .dealing-banner {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: .625rem;
      padding: .75rem 1rem;
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius-md);
      color: var(--rw-text-primary);
      font-size: .8125rem;
      margin-bottom: 1rem;
    }
    .dealing-banner mat-icon { color: var(--rw-accent); }
    .dealing-banner > span { flex: 1; min-width: 14rem; }
    .dealing-banner.warn { background: var(--rw-pending-bg); color: var(--rw-pending-fg); }
    .dealing-banner.warn mat-icon { color: inherit; }
    .dealing-cell { color: var(--rw-text-secondary); }

    .settled-title {
      margin: 1.5rem 0 .5rem;
      font-size: .875rem;
      color: var(--rw-text-secondary);
    }
    .req-row.settled-row { grid-template-columns: 90px 80px 1fr 1fr 1fr 110px; }

    .force-form { display: flex; flex-direction: column; gap: .5rem; min-width: 440px; padding-top: .5rem; }
    .force-note { color: var(--rw-text-secondary); font-size: .8125rem; margin: 0 0 .5rem; }

    @media (max-width: 720px) {
      .req-row { grid-template-columns: 28px 70px 1fr 110px; }
      .req-row > :nth-child(4), .req-row > :nth-child(6), .req-row > :nth-child(7) { display: none; }
      .req-row.settled-row { grid-template-columns: 70px 1fr 1fr; }
      .req-row.settled-row > :nth-child(n+4) { display: none; }
      .force-form { min-width: 0; }
    }
  `]
})
export class VaultRequestsComponent implements OnInit {
  @Input() deploymentId!: string;
  /** Unused — the struck NAV is read from GET /vault-state, which only reflects strikes confirmed
   *  on-chain (the latest nav-strike row may still be unconfirmed). Kept for template compatibility. */
  @Input() latestNav: number | null = null;

  @ViewChild('forceCancelDialog') forceCancelDialogTpl!: TemplateRef<unknown>;
  @ViewChild('dealingCutoffDialog') dealingCutoffDialogTpl!: TemplateRef<unknown>;

  private readonly vaultService = inject(VaultService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);

  pending: VaultRequest[] = [];
  settled: VaultRequest[] = [];
  vaultState: VaultStateSummary | null = null;
  selected = new Set<string>();
  busy = new Set<string>();

  forceTarget: VaultRequest | null = null;
  forceForm = { to: '', legalBasis: '' };
  cutoffForm: { time: string; periodHours: number } = { time: '17:00', periodHours: 24 };

  get struckNav(): number | null {
    return this.vaultState?.latestNavPerShare ?? null;
  }

  ngOnInit(): void { this.load(); }

  load(): void {
    this.vaultService.getVaultRequests(this.deploymentId).subscribe({
      next: (reqs) => {
        this.pending = reqs;
        // Drop selections that are no longer fulfillable (e.g. a hold appeared since).
        this.selected.forEach((id) => {
          const req = reqs.find((r) => r.id === id);
          if (!req || !this.canFulfil(req)) this.selected.delete(id);
        });
        this.cdr.markForCheck();
      },
      error: () => {
        this.snackBar.open('Failed to load vault requests', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
    this.vaultService.getVaultState(this.deploymentId).subscribe({
      next: (state) => { this.vaultState = state; this.cdr.markForCheck(); },
      error: () => { this.vaultState = null; this.cdr.markForCheck(); },
    });
    this.vaultService.getVaultRequests(this.deploymentId, 'FULFILLED').subscribe({
      next: (reqs) => {
        this.settled = [...reqs]
          .sort((a, b) => (b.fulfilledAt ?? '').localeCompare(a.fulfilledAt ?? ''))
          .slice(0, 5);
        this.cdr.markForCheck();
      },
      error: () => { this.settled = []; this.cdr.markForCheck(); },
    });
  }

  /** Fulfil/cancel are refused while a compliance hold applies or a tx is in flight. */
  actionable(req: VaultRequest): boolean {
    return !req.complianceHold && !req.awaitingConfirmation && !this.busy.has(req.id);
  }

  /** Fulfil is additionally held back until a NAV was struck after the request's dealing point (T1-07). */
  canFulfil(req: VaultRequest): boolean {
    return canFulfil(req, this.busy.has(req.id));
  }

  hint(req: VaultRequest): string {
    return fulfilHint(req);
  }

  cutoffLabel(dealing: VaultDealingState): string {
    return dealing.cutoffSecondsOfDay == null ? '—' : formatCutoffUtc(dealing.cutoffSecondsOfDay);
  }

  periodText(dealing: VaultDealingState): string {
    return dealing.periodSeconds == null ? '' : periodLabel(dealing.periodSeconds);
  }

  openDealingCutoff(): void {
    const dealing = this.vaultState?.dealing;
    this.cutoffForm = {
      time: dealing?.configured && dealing.cutoffSecondsOfDay != null ? formatCutoffUtc(dealing.cutoffSecondsOfDay) : '17:00',
      periodHours: dealing?.configured && dealing.periodSeconds ? dealing.periodSeconds / 3600 : 24,
    };
    this.dialog.open(this.dealingCutoffDialogTpl, { width: '520px', maxWidth: '95vw' });
  }

  cutoffFormValid(): boolean {
    return parseCutoffUtc(this.cutoffForm.time) !== null && periodSecondsOf(Number(this.cutoffForm.periodHours)) !== null;
  }

  /** Step-up + second approver bound to this exact body; then the registry transaction is submitted. */
  submitDealingCutoff(): void {
    const cutoffSecondsOfDay = parseCutoffUtc(this.cutoffForm.time);
    const periodSeconds = periodSecondsOf(Number(this.cutoffForm.periodHours));
    if (cutoffSecondsOfDay === null || periodSeconds === null) return;
    const body = { cutoffSecondsOfDay, periodSeconds };
    this.dialog.closeAll();
    withDualControl(this.dialog, {
      action: 'VAULT_DEALING_CUTOFF',
      reason: `Set dealing cut-off ${this.cutoffForm.time} UTC, period ${periodLabel(periodSeconds)}`,
      target: `POST /api/v1/deployments/${this.deploymentId}/dealing-cutoff`,
      targetBody: body,
    }, (tokens) => {
      this.vaultService.setDealingCutoff(this.deploymentId, body, tokens).subscribe({
        next: () => {
          this.snackBar.open('Dealing cut-off submitted — it applies to requests placed after it confirms. '
            + 'Audit event recorded.', 'Dismiss', { duration: 6000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Setting the dealing cut-off failed', 'Dismiss', { duration: 6000 });
          this.cdr.markForCheck();
        },
      });
    });
  }

  toggle(id: string, checked: boolean): void {
    if (checked) this.selected.add(id); else this.selected.delete(id);
  }

  fulfill(req: VaultRequest): void {
    if (!this.struckNav) {
      this.snackBar.open('Strike a NAV first before fulfilling requests', 'Dismiss', { duration: 5000 });
      return;
    }
    if (!this.canFulfil(req)) return;
    this.busy.add(req.id);
    this.vaultService.fulfillRequest(this.deploymentId, req.requestId).subscribe({
      next: () => {
        this.snackBar.open('Fulfilment submitted — awaiting on-chain confirmation', 'Dismiss', { duration: 3000 });
        this.busy.delete(req.id);
        this.load();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Fulfillment failed', 'Dismiss', { duration: 6000 });
        this.busy.delete(req.id);
        this.cdr.markForCheck();
      },
    });
  }

  fulfillSelected(): void {
    this.selected.forEach((id) => {
      const req = this.pending.find((r) => r.id === id);
      if (req) this.fulfill(req);
    });
    this.selected.clear();
  }

  cancel(req: VaultRequest): void {
    if (!this.actionable(req)) return;
    this.busy.add(req.id);
    this.vaultService.cancelRequest(this.deploymentId, req.requestId).subscribe({
      next: () => {
        this.snackBar.open('Cancellation submitted — awaiting on-chain confirmation', 'Dismiss', { duration: 3000 });
        this.busy.delete(req.id);
        this.load();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Cancel failed', 'Dismiss', { duration: 6000 });
        this.busy.delete(req.id);
        this.cdr.markForCheck();
      },
    });
  }

  openForceCancel(req: VaultRequest): void {
    this.forceTarget = req;
    this.forceForm = { to: '', legalBasis: '' };
    this.dialog.open(this.forceCancelDialogTpl, { width: '540px', maxWidth: '95vw' });
  }

  forceFormValid(): boolean {
    return /^0x[0-9a-fA-F]{40}$/.test(this.forceForm.to.trim()) && this.forceForm.legalBasis.trim().length > 0;
  }

  submitForceCancel(): void {
    const req = this.forceTarget;
    if (!req || !this.forceFormValid()) return;
    const body = { to: this.forceForm.to.trim(), legalBasis: this.forceForm.legalBasis.trim() };
    this.dialog.closeAll();

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Force-cancel vault request #${req.requestId} to ${body.to}`,
        action: 'VAULT_REQUEST_FORCE_CANCEL',
        target: `POST /api/v1/deployments/${this.deploymentId}/vault-requests/${req.requestId}/force-cancel`,
        targetBody: body,
      },
      width: '500px',
      disableClose: true,
    });
    ref.afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result) return;
      this.busy.add(req.id);
      this.cdr.markForCheck();
      this.vaultService.forceCancelRequest(
        this.deploymentId, req.requestId, body, result.stepUpToken, result.dualControlToken!,
      ).subscribe({
        next: () => {
          this.snackBar.open('Force-cancel submitted. Audit event recorded.', 'Dismiss', { duration: 5000 });
          this.busy.delete(req.id);
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Force-cancel failed', 'Dismiss', { duration: 6000 });
          this.busy.delete(req.id);
          this.cdr.markForCheck();
        },
      });
    });
  }
}
