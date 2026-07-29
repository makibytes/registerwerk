import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { VaultService } from '../../../../core/api/vault.service';
import { VaultRequest } from '../../../../core/models';

@Component({
  selector: 'app-vault-requests',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatCheckboxModule, DatePipe, DecimalPipe, SlicePipe],
  template: `
    <div class="req-shell">
      <div class="req-header">
        <span class="badge">ASYNC VAULT</span>
        <h3 class="req-title">Pending requests</h3>
        <div class="req-controls">
          <span class="req-count">{{ pending.length }} pending</span>
          <button mat-stroked-button class="btn-refresh" (click)="load()">
            <mat-icon>refresh</mat-icon>
          </button>
        </div>
      </div>

      @if (!latestNav) {
        <div class="nav-warning">
          <mat-icon>info_outline</mat-icon>
          <span>Strike a current NAV before fulfilling requests — fulfillment uses the latest struck NAV.</span>
        </div>
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
            <span>Controller</span>
            <span class="right">Amount</span>
            <span class="right">Requested</span>
            <span></span>
          </div>

          @for (req of pending; track req.id) {
            <div class="req-row" [class.checked]="selected.has(req.id)">
              <mat-checkbox
                color="primary"
                [checked]="selected.has(req.id)"
                (change)="toggle(req.id, $event.checked)">
              </mat-checkbox>

              <span class="mono dimmed">#{{ req.requestId | slice:0:8 }}…</span>

              <span class="type-badge" [class.deposit]="req.requestType === 'DEPOSIT'" [class.redeem]="req.requestType === 'REDEEM'">
                {{ req.requestType }}
              </span>

              <span class="addr mono">{{ req.controllerAddr | slice:0:10 }}…</span>

              <span class="right mono amount">
                @if (req.requestType === 'DEPOSIT') {
                  {{ req.assetAmount | number }}
                } @else {
                  {{ req.shareAmount | number }} shares
                }
              </span>

              <span class="right dimmed small">{{ req.requestedAt | date:'dd MMM HH:mm' }}</span>

              <div class="row-actions">
                <button mat-icon-button class="btn-fulfill" [disabled]="fulfilling.has(req.id)"
                  (click)="fulfill(req)" title="Fulfill">
                  <mat-icon>check_circle_outline</mat-icon>
                </button>
                <button mat-icon-button class="btn-cancel" [disabled]="fulfilling.has(req.id)"
                  (click)="cancel(req)" title="Cancel">
                  <mat-icon>cancel</mat-icon>
                </button>
              </div>
            </div>
          }
        </div>

        @if (selected.size > 0) {
          <div class="bulk-bar">
            <span class="bulk-count">{{ selected.size }} selected</span>
            <button mat-flat-button class="btn-bulk-fulfill" [disabled]="fulfilling.size > 0" (click)="fulfillSelected()">
              <mat-icon>done_all</mat-icon>
              Fulfill {{ selected.size }} request{{ selected.size > 1 ? 's' : '' }}
            </button>
          </div>
        }
      }
    </div>
  `,
  styles: [`
    :host {
      display: block;
      --accent: var(--rw-accent, #F59E0B);
      --surface: #0e1124;
      --border: rgba(245,158,11,.18);
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
      background: rgba(245,158,11,.1);
      border: 1px solid var(--border);
      border-radius: 2px;
      padding: .2rem .625rem;
    }

    .req-title {
      font-family: Manrope, sans-serif;
      font-size: 1rem;
      font-weight: 700;
      color: #e2e8f8;
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
      color: #7b8aac;
      font-family: 'IBM Plex Mono', monospace;
    }

    .btn-refresh { border-color: var(--border); color: #a0aec0; }

    .nav-warning {
      display: flex;
      align-items: center;
      gap: .625rem;
      padding: .75rem 1rem;
      background: rgba(245,158,11,.06);
      border: 1px solid rgba(245,158,11,.25);
      border-radius: 6px;
      color: #f0c040;
      font-size: .8125rem;
      margin-bottom: 1rem;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 3rem 0;
      color: #7b8aac;
    }

    .empty-icon {
      font-size: 3rem; height: 3rem; width: 3rem;
      color: #4ade80;
      margin-bottom: .75rem;
    }

    .req-table { display: flex; flex-direction: column; }

    .req-row {
      display: grid;
      grid-template-columns: 36px 120px 80px 1fr 120px 100px 80px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid rgba(255,255,255,.04);
      font-size: .8125rem;
      border-radius: 4px;
      transition: background .1s;
    }

    .req-row:hover { background: rgba(255,255,255,.02); }

    .req-row.checked { background: rgba(245,158,11,.04); }

    .req-row.header {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .6875rem;
      letter-spacing: .06em;
      color: #7b8aac;
      background: rgba(255,255,255,.02);
      border-radius: 4px 4px 0 0;
    }

    .mono { font-family: 'IBM Plex Mono', monospace; }
    .dimmed { color: #7b8aac; }
    .small { font-size: .75rem; }
    .addr { font-size: .8125rem; }
    .right { text-align: right; }
    .amount { color: #e2e8f8; font-weight: 600; }

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

    .type-badge.deposit { background: rgba(74,222,128,.12); color: #4ade80; }
    .type-badge.redeem { background: rgba(251,191,36,.12); color: #fbbf24; }

    .row-actions { display: flex; gap: .25rem; justify-content: flex-end; }

    .btn-fulfill { color: #4ade80; }
    .btn-cancel { color: #f87171; }

    .bulk-bar {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem;
      background: rgba(245,158,11,.06);
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
      color: #07091A !important;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: .5rem;
    }

    .btn-bulk-fulfill:disabled { opacity: .5; }
  `]
})
export class VaultRequestsComponent implements OnInit {
  @Input() deploymentId!: string;
  @Input() latestNav: number | null = null;

  private readonly vaultService = inject(VaultService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  pending: VaultRequest[] = [];
  selected = new Set<string>();
  fulfilling = new Set<string>();

  ngOnInit(): void { this.load(); }

  load(): void {
    this.vaultService.getVaultRequests(this.deploymentId).subscribe({
      next: (reqs) => { this.pending = reqs; this.cdr.markForCheck(); },
      error: () => {
        this.snackBar.open('Failed to load vault requests', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  toggle(id: string, checked: boolean): void {
    if (checked) this.selected.add(id); else this.selected.delete(id);
  }

  fulfill(req: VaultRequest): void {
    if (!this.latestNav) {
      this.snackBar.open('Strike a NAV first before fulfilling requests', 'Dismiss', { duration: 5000 });
      return;
    }
    this.fulfilling.add(req.id);
    this.vaultService.fulfillRequest(this.deploymentId, req.requestId, this.latestNav).subscribe({
      next: () => {
        this.snackBar.open('Request fulfilled', 'Dismiss', { duration: 3000 });
        this.fulfilling.delete(req.id);
        this.load();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Fulfillment failed', 'Dismiss', { duration: 5000 });
        this.fulfilling.delete(req.id);
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
    this.fulfilling.add(req.id);
    this.vaultService.cancelRequest(this.deploymentId, req.requestId).subscribe({
      next: () => {
        this.snackBar.open('Request cancelled', 'Dismiss', { duration: 3000 });
        this.fulfilling.delete(req.id);
        this.load();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Cancel failed', 'Dismiss', { duration: 5000 });
        this.fulfilling.delete(req.id);
        this.cdr.markForCheck();
      },
    });
  }
}
