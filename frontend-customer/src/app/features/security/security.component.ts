import { ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { QrCodeComponent } from '@registerwerk/ui';
import { Subscription, interval, takeWhile } from 'rxjs';
import { SecurityService, TwoFactorStatus } from '../../core/api/security.service';
import { DsarService, DsarErasureResult } from '../../core/api/dsar.service';
import { downloadBlob } from '../../core/utils/download.util';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

const POLL_INTERVAL_MS = 5_000;
const POLL_LIMIT = 60; // 5 minutes

/**
 * Two-factor authentication self-service.
 *
 * **The QR code here is not ours.** Microsoft Graph exposes no way to create a Microsoft
 * Authenticator or software-OATH method — those resources support only list, get and delete,
 * and `secretKey` is documented as always returning null. Entra owns the TOTP secret, so the
 * QR code the user scans to enrol is shown on Microsoft's own registration page.
 *
 * What this page's QR code encodes is the *link to that page*, so a user sitting at a desktop
 * can continue on the phone that will hold the authenticator — which is the device that needs
 * to be there, and usually is not the one they are reading this on.
 */
@Component({
  selector: 'app-security',
  standalone: true,
  imports: [DatePipe, MatIconModule, MatProgressSpinnerModule, MatSnackBarModule, QrCodeComponent],
  styles: [`
    .page {
      max-width: 720px;
      margin: 0 auto;
      padding: 32px 24px 64px;
    }

    h1 {
      font-size: 24px;
      font-weight: 700;
      margin: 0 0 6px;
      color: var(--rw-text, #111827);
    }

    .subtitle {
      margin: 0 0 28px;
      font-size: 14px;
      color: var(--rw-text-muted, #6B7280);
    }

    .card {
      background: var(--rw-surface, #FFFFFF);
      border: 1px solid var(--rw-border, #E5E7EB);
      border-radius: 12px;
      padding: 24px;
    }

    .card-head {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 8px;
    }

    .card-head h2 {
      font-size: 16px;
      font-weight: 600;
      margin: 0;
      flex: 1;
      color: var(--rw-text, #111827);
    }

    .badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 600;

      mat-icon {
        font-size: 15px;
        width: 15px;
        height: 15px;
      }

      &.ok { background: #ECFDF5; color: #047857; }
      &.warn { background: #FFFBEB; color: #B45309; }
      &.info { background: #EFF6FF; color: #1D4ED8; }
    }

    .body-text {
      font-size: 14px;
      line-height: 1.65;
      color: var(--rw-text-muted, #6B7280);
      margin: 0 0 20px;
    }

    ol {
      margin: 0 0 24px;
      padding-left: 20px;
      font-size: 14px;
      line-height: 1.85;
      color: var(--rw-text, #111827);
    }

    .qr-row {
      display: flex;
      gap: 24px;
      align-items: center;
      flex-wrap: wrap;
      padding: 20px;
      border: 1px solid var(--rw-border, #E5E7EB);
      border-radius: 10px;
      margin-bottom: 24px;
    }

    .qr-caption {
      flex: 1;
      min-width: 200px;
      font-size: 13px;
      line-height: 1.6;
      color: var(--rw-text-muted, #6B7280);
      margin: 0;
    }

    .actions {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }

    button, a.btn {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      height: 42px;
      padding: 0 18px;
      border-radius: 9px;
      border: none;
      font-family: 'Manrope', sans-serif;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      text-decoration: none;

      &.primary {
        background: var(--rw-accent, #0D9488);
        color: #FFFFFF;
      }

      &.secondary {
        background: transparent;
        border: 1px solid var(--rw-border, #E5E7EB);
        color: var(--rw-text, #111827);
      }

      &[disabled] { opacity: 0.55; cursor: default; }
    }

    ul.methods {
      list-style: none;
      margin: 0 0 16px;
      padding: 0;

      li {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 10px 0;
        border-bottom: 1px solid var(--rw-border, #E5E7EB);
        font-size: 14px;
        color: var(--rw-text, #111827);

        &:last-child { border-bottom: none; }

        mat-icon { color: #047857; font-size: 18px; width: 18px; height: 18px; }
      }
    }

    .checked-at {
      font-size: 12px;
      color: var(--rw-text-muted, #6B7280);
      margin: 0;
    }

    .loading {
      display: flex;
      justify-content: center;
      padding: 48px 0;
    }

    .erasure-note {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      margin-top: 16px;
      padding: 12px 14px;
      border-radius: 8px;
      background: #EFF6FF;
      color: #1D4ED8;
      font-size: 13px;
      line-height: 1.5;

      mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; margin-top: 1px; }
    }
  `],
  template: `
    <div class="page">
      <h1>Security</h1>
      <p class="subtitle">Manage the second factor protecting your Registerwerk account.</p>

      @if (loading) {
        <div class="loading"><mat-spinner diameter="32" /></div>
      } @else if (status) {

        <!-- Local / demo mode: Entra is not in play, so there is nothing to configure here. -->
        @if (!status.applicable) {
          <div class="card">
            <div class="card-head">
              <h2>Two-factor authentication</h2>
              <span class="badge info"><mat-icon>info</mat-icon> Not applicable</span>
            </div>
            <p class="body-text">{{ status.message }}</p>
          </div>
        }

        <!-- Federated: the customer's own tenant issues the credentials, so we cannot help. -->
        @else if (!status.managedHere) {
          <div class="card">
            <div class="card-head">
              <h2>Two-factor authentication</h2>
              <span class="badge info"><mat-icon>domain</mat-icon> Managed by your organisation</span>
            </div>
            <p class="body-text">{{ status.message }}</p>
          </div>
        }

        <!-- Registered. -->
        @else if (status.registered) {
          <div class="card">
            <div class="card-head">
              <h2>Two-factor authentication</h2>
              <span class="badge ok"><mat-icon>verified_user</mat-icon> Active</span>
            </div>
            <ul class="methods">
              @for (method of status.methods; track method) {
                <li><mat-icon>check_circle</mat-icon> {{ method }}</li>
              }
            </ul>
            @if (status.checkedAt) {
              <p class="checked-at">Last checked {{ status.checkedAt | date: 'medium' }}</p>
            }
            <div class="actions" style="margin-top: 20px;">
              <a class="btn secondary" [href]="status.setupUrl" target="_blank" rel="noopener noreferrer">
                <mat-icon>open_in_new</mat-icon> Manage at Microsoft
              </a>
              <button class="secondary" type="button" (click)="recheck()" [disabled]="refreshing">
                <mat-icon>refresh</mat-icon>
                {{ refreshing ? 'Checking…' : 'Check again' }}
              </button>
            </div>
          </div>
        }

        <!-- Not yet registered: guide the user to Microsoft's registration page. -->
        @else {
          <div class="card">
            <div class="card-head">
              <h2>Two-factor authentication</h2>
              <span class="badge warn"><mat-icon>warning</mat-icon> Not set up</span>
            </div>
            @if (status.message) {
              <p class="body-text">{{ status.message }}</p>
            }
            <ol>
              <li>Install <strong>Microsoft Authenticator</strong> on your phone.</li>
              <li>Open your Microsoft security info page — scan the code below to open it on your
                  phone, or use the button if you are already on the device you want to register.</li>
              <li>Add a sign-in method and follow Microsoft's instructions.</li>
              <li>Come back here and select <strong>I've finished</strong>.</li>
            </ol>

            <div class="qr-row">
              <rw-qr-code
                [value]="status.setupUrl"
                [size]="160"
                alt="QR code linking to your Microsoft security info page" />
              <p class="qr-caption">
                Scan with your phone's camera to open Microsoft's security info page there.
                The authenticator setup code itself is shown by Microsoft on that page —
                Registerwerk never sees it.
              </p>
            </div>

            <div class="actions">
              <a class="btn primary" [href]="status.setupUrl" target="_blank" rel="noopener noreferrer"
                 (click)="startPolling()">
                <mat-icon>open_in_new</mat-icon> Set up now
              </a>
              <button class="secondary" type="button" (click)="recheck()" [disabled]="refreshing">
                <mat-icon>refresh</mat-icon>
                {{ refreshing ? 'Checking…' : "I've finished — check again" }}
              </button>
            </div>
          </div>
        }
      } @else {
        <div class="card">
          <p class="body-text">
            Two-factor status could not be loaded. Refresh the page, or manage your sign-in
            methods at Microsoft directly.
          </p>
          <button class="secondary" type="button" (click)="loadStatus()">Retry</button>
        </div>
      }

      <div class="card" style="margin-top: 20px;">
        <div class="card-head">
          <h2>Privacy &amp; data (GDPR)</h2>
        </div>
        <p class="body-text">
          Export the personal data we hold on your entity (DSGVO Art. 15/20), or request erasure
          of the fields not subject to statutory retention (DSGVO Art. 17; eWpG §15(3) — 10 years,
          GwG §8 — 5 years). Erasure requests are reviewed by an operator within 30 days
          (Art. 12(3)).
        </p>
        <div class="actions">
          <button class="secondary" type="button" (click)="exportData()" [disabled]="exporting">
            <mat-icon>download</mat-icon>
            {{ exporting ? 'Preparing…' : 'Export my data' }}
          </button>
          <button class="secondary" type="button" (click)="requestErasure()" [disabled]="erasing || !!erasureResult">
            <mat-icon>delete_outline</mat-icon>
            {{ erasing ? 'Submitting…' : (erasureResult ? 'Erasure requested' : 'Request erasure') }}
          </button>
        </div>
        @if (erasureResult) {
          <div class="erasure-note">
            <mat-icon>info</mat-icon>
            <span>
              {{ erasureResult.message }}
              @if (erasureResult.dueAt) { Due by {{ erasureResult.dueAt | date: 'medium' }}. }
            </span>
          </div>
        }
      </div>
    </div>
  `,
})
export class SecurityComponent implements OnInit, OnDestroy {
  private readonly securityService = inject(SecurityService);
  private readonly dsarService = inject(DsarService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);

  status: TwoFactorStatus | null = null;
  loading = true;
  refreshing = false;
  exporting = false;
  erasing = false;
  erasureResult: DsarErasureResult | null = null;

  private poll?: Subscription;

  ngOnInit(): void {
    this.loadStatus();
  }

  loadStatus(): void {
    this.loading = true;
    this.securityService.getTwoFactorStatus().subscribe({
      next: status => {
        this.status = status;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.status = null;
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
  }

  recheck(): void {
    if (this.refreshing) return;
    this.refreshing = true;
    this.cdr.markForCheck();

    this.securityService.refreshTwoFactorStatus().subscribe({
      next: status => {
        this.status = status;
        this.refreshing = false;
        if (status.registered) {
          this.poll?.unsubscribe();
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.refreshing = false;
        this.cdr.markForCheck();
        this.snackBar.open('Two-factor status could not be refreshed.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  exportData(): void {
    if (this.exporting) return;
    this.exporting = true;
    this.cdr.markForCheck();

    this.dsarService.exportMyData().subscribe({
      next: data => {
        const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
        downloadBlob(blob, `registerwerk-data-export-${new Date().toISOString().slice(0, 10)}.json`);
        this.exporting = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.exporting = false;
        this.cdr.markForCheck();
        this.snackBar.open('Your data export could not be prepared.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  requestErasure(): void {
    if (this.erasing || this.erasureResult) return;
    if (!confirm('Submit a personal-data erasure request for operator review?')) return;
    this.erasing = true;
    this.cdr.markForCheck();

    this.dsarService.requestErasure().subscribe({
      next: result => {
        this.erasureResult = result;
        this.erasing = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.erasing = false;
        this.cdr.markForCheck();
        this.snackBar.open('The erasure request could not be submitted.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  /**
   * Polls after the user opens Microsoft's page, so the card flips to "Active" on its own when
   * they come back. Bounded at five minutes: an unbounded poll on a tab someone left open would
   * become a steady stream of Graph calls, and the manual button covers the slower cases.
   */
  startPolling(): void {
    this.poll?.unsubscribe();
    let ticks = 0;
    this.poll = interval(POLL_INTERVAL_MS)
      .pipe(takeWhile(() => ticks++ < POLL_LIMIT && !this.status?.registered))
      .subscribe(() => this.recheck());
  }
}
