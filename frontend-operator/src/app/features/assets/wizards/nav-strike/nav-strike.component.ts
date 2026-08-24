import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { VaultService } from '../../../../core/api/vault.service';
import { VaultNavStrike } from '../../../../core/models';

@Component({
  selector: 'app-nav-strike',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatIconModule, DatePipe, DecimalPipe, SlicePipe],
  template: `
    <div class="nav-shell">
      <header class="nav-header">
        <div class="nav-badge-row">
          <span class="badge">NAV STRIKE</span>
          @if (latestNav) {
            <span class="current-nav">Current: <span class="nav-val">{{ latestNav.navPerShare | number:'1.4-4' }}</span></span>
          }
        </div>
        <h2 class="nav-title">Strike net asset value</h2>
      </header>

      <div class="nav-body">
        <!-- Input form -->
        <div class="strike-card">
          <div class="strike-form">
            <mat-form-field appearance="outline" class="nav-field">
              <mat-label>NAV per share</mat-label>
              <input matInput type="number" step="0.0001" [(ngModel)]="navPerShare" placeholder="1.0000" min="0.0001">
              <span matSuffix class="field-suffix">× 1.0</span>
            </mat-form-field>

            <mat-form-field appearance="outline" class="nav-field">
              <mat-label>Effective at</mat-label>
              <input matInput type="datetime-local" [(ngModel)]="effectiveAt">
            </mat-form-field>
          </div>

          <button type="button" mat-flat-button class="btn-strike" [disabled]="striking || !navPerShare" (click)="strike()">
            @if (striking) {
              <span class="spinner"></span> Striking…
            } @else {
              <ng-container><mat-icon>price_change</mat-icon> Confirm NAV strike</ng-container>
            }
          </button>
        </div>

        <!-- History -->
        <div class="history-section">
          <h3 class="history-title">Strike history</h3>
          @if (strikes.length === 0) {
            <p class="empty-note">No NAV strikes recorded yet.</p>
          } @else {
            <div class="history-table">
              <div class="h-row header">
                <span>Strike #</span>
                <span>NAV / share</span>
                <span>Effective at</span>
                <span>Struck by</span>
              </div>
              @for (s of strikes; track s.id) {
                <div class="h-row" [class.latest]="$first">
                  <span class="mono">#{{ s.strikeId }}</span>
                  <span class="nav-num mono">{{ s.navPerShare | number:'1.4-4' }}</span>
                  <span class="mono dimmed">{{ s.effectiveAt | date:'dd MMM yyyy HH:mm' }}</span>
                  <span class="dimmed addr">{{ s.struckBy | slice:0:8 }}…</span>
                </div>
              }
            </div>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      --accent: var(--rw-accent, #F59E0B);
      --surface: #0e1124;
      --border: rgba(245,158,11,.18);
    }

    .nav-shell { padding: 1.5rem 0; }

    .nav-header { margin-bottom: 1.5rem; }

    .nav-badge-row {
      display: flex;
      align-items: center;
      gap: 1rem;
      margin-bottom: .5rem;
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

    .current-nav {
      font-size: .8125rem;
      color: #7b8aac;
    }

    .nav-val {
      font-family: 'IBM Plex Mono', monospace;
      color: #4ade80;
      font-weight: 700;
    }

    .nav-title {
      font-family: 'Manrope Variable', sans-serif;
      font-size: 1.25rem;
      font-weight: 700;
      color: #e2e8f8;
      margin: 0;
    }

    .nav-body { display: flex; flex-direction: column; gap: 2rem; }

    .strike-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 1.5rem;
    }

    .strike-form {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 1rem;
      margin-bottom: 1.25rem;
    }

    .nav-field { width: 100%; }

    .field-suffix {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .75rem;
      color: #7b8aac;
    }

    .btn-strike {
      background: var(--accent) !important;
      color: #07091A !important;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: .5rem;
    }

    .btn-strike:disabled { opacity: .5; }

    .history-section { }

    .history-title {
      font-family: 'Manrope Variable', sans-serif;
      font-size: .9375rem;
      font-weight: 700;
      color: #cbd5e1;
      margin: 0 0 .75rem;
    }

    .history-table { display: flex; flex-direction: column; }

    .h-row {
      display: grid;
      grid-template-columns: 60px 120px 1fr 1fr;
      gap: 1rem;
      padding: .625rem .75rem;
      border-bottom: 1px solid rgba(255,255,255,.04);
      font-size: .8125rem;
      align-items: center;
    }

    .h-row.header {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .6875rem;
      letter-spacing: .08em;
      color: #7b8aac;
      background: rgba(255,255,255,.02);
      border-radius: 4px 4px 0 0;
    }

    .h-row.latest { background: rgba(245,158,11,.04); }

    .mono { font-family: 'IBM Plex Mono', monospace; }
    .nav-num { color: #4ade80; font-weight: 600; }
    .dimmed { color: #7b8aac; }
    .addr { font-size: .75rem; }

    .empty-note { color: #7b8aac; font-size: .875rem; }

    .spinner {
      width: 1rem; height: 1rem;
      border: 2px solid rgba(7,9,26,.4);
      border-top-color: #07091A;
      border-radius: 50%;
      animation: spin .6s linear infinite;
      display: inline-block;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__leading,
    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__notch,
    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__trailing {
      border-color: var(--border) !important;
    }

    ::ng-deep .mat-mdc-form-field input { color: #e2e8f8 !important; }
    ::ng-deep .mat-mdc-form-field .mat-mdc-floating-label { color: #7b8aac !important; }
  `]
})
export class NavStrikeComponent implements OnInit {
  @Input() deploymentId!: string;

  private readonly vaultService = inject(VaultService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  navPerShare: number | null = null;
  effectiveAt: string = new Date().toISOString().slice(0, 16);
  striking = false;
  strikes: VaultNavStrike[] = [];
  latestNav: VaultNavStrike | null = null;

  ngOnInit(): void {
    this.loadHistory();
  }

  loadHistory(): void {
    this.vaultService.getNavStrikes(this.deploymentId).subscribe({
      next: (strikes) => {
        this.strikes = strikes;
        this.latestNav = strikes[0] ?? null;
        this.cdr.markForCheck();
      },
      error: () => {
        this.snackBar.open('Failed to load NAV strike history', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  strike(): void {
    if (!this.navPerShare) return;
    this.striking = true;
    this.cdr.markForCheck();

    this.vaultService.strikeNav(this.deploymentId, {
      navPerShare: this.navPerShare,
      effectiveAt: new Date(this.effectiveAt).toISOString(),
    }).subscribe({
      next: () => {
        this.snackBar.open('NAV struck successfully', 'Dismiss', { duration: 4000 });
        this.striking = false;
        this.navPerShare = null;
        this.loadHistory();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'NAV strike failed', 'Dismiss', { duration: 6000 });
        this.striking = false;
        this.cdr.markForCheck();
      },
    });
  }
}
