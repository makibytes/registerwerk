import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  AdminUserService,
  EntraMethodsResponse,
  OperatorUser,
  TemporaryAccessPassResponse,
} from '../../core/api/admin-user.service';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../shared/components/step-up/step-up-dialog.component';

/** Must match the backend `@RequiresStepUp(reason = ...)` values exactly — the approver token's
 *  `stepup_scope` claim is compared with `equals()`. */
const ACTION_DELETE = 'ENTRA_AUTH_METHOD_DELETE';
const ACTION_RESET = 'ENTRA_MFA_RESET';
const ACTION_REVOKE = 'ENTRA_REVOKE_SIGNIN_SESSIONS';
const ACTION_TAP = 'ENTRA_TEMPORARY_ACCESS_PASS';

/**
 * Two-factor support for one customer account — in practice, the lost-phone runbook.
 *
 * The order the UI presents matches the order Microsoft documents, because it matters: reset the
 * methods, then revoke sessions (deleting methods does *not* invalidate existing refresh tokens
 * or browser cookies), then issue a Temporary Access Pass for the customer to sign in once and
 * re-register.
 *
 * The real security boundary is the out-of-band identity check the operator performs before any
 * of this; everything here is mechanism, which is why each action is behind step-up and the two
 * that could result in someone signing in as the customer are behind 4-eyes as well.
 */
@Component({
  selector: 'app-user-2fa-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 18px; font-weight: 700; }

    .subject {
      font-size: 13px;
      color: var(--rw-text-secondary);
      margin: 0 0 20px;
    }

    .notice {
      display: flex;
      gap: 10px;
      padding: 12px 14px;
      border-radius: 8px;
      background: rgba(245, 158, 11, 0.08);
      border: 1px solid rgba(245, 158, 11, 0.3);
      font-size: 13px;
      line-height: 1.55;
      margin-bottom: 20px;

      mat-icon { color: var(--rw-accent); flex-shrink: 0; }
    }

    ul.methods {
      list-style: none;
      margin: 0 0 20px;
      padding: 0;

      li {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 10px 0;
        border-bottom: 1px solid var(--rw-border);
        font-size: 13px;

        &:last-child { border-bottom: none; }

        .label { flex: 1; }

        .default-chip {
          font-size: 11px;
          padding: 2px 8px;
          border-radius: 999px;
          background: rgba(99, 102, 241, 0.12);
          color: #4F46E5;
        }
      }
    }

    .empty {
      font-size: 13px;
      color: var(--rw-text-secondary);
      margin: 0 0 20px;
    }

    .section-title {
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--rw-text-secondary);
      margin: 20px 0 10px;
    }

    .action-row {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
      margin-bottom: 8px;
    }

    .tap-panel {
      margin-top: 16px;
      padding: 16px;
      border-radius: 8px;
      border: 1px solid var(--rw-accent);
      background: rgba(245, 158, 11, 0.06);

      .tap-value {
        font-family: 'IBM Plex Mono', monospace;
        font-size: 20px;
        letter-spacing: 0.06em;
        padding: 12px;
        background: var(--rw-surface);
        border-radius: 6px;
        margin: 10px 0;
        user-select: all;
        word-break: break-all;
      }

      .tap-meta { font-size: 12px; color: var(--rw-text-secondary); margin: 0 0 12px; }
    }

    .loading { display: flex; justify-content: center; padding: 28px 0; }
  `],
  template: `
    <h2 mat-dialog-title>Two-factor support</h2>
    <mat-dialog-content>
      <p class="subject">{{ user.name || user.email }} · {{ user.email }}</p>

      @if (loading) {
        <div class="loading"><mat-spinner diameter="28" /></div>
      } @else if (state) {

        @if (!state.managedHere) {
          <div class="notice">
            <mat-icon>info</mat-icon>
            <span>{{ state.message }}</span>
          </div>
        } @else {
          <div class="section-title">Registered methods</div>
          @if (state.methods.length) {
            <ul class="methods">
              @for (m of state.methods; track m.id) {
                <li>
                  <span class="label">{{ m.label }}</span>
                  @if (m.isDefault) { <span class="default-chip">Default</span> }
                  <button mat-icon-button
                          [disabled]="!m.deletable || busy"
                          [matTooltip]="m.deletable ? 'Remove this method' : 'This method cannot be removed individually'"
                          (click)="removeMethod(m.type, m.id)">
                    <mat-icon>delete_outline</mat-icon>
                  </button>
                </li>
              }
            </ul>
          } @else {
            <p class="empty">No authentication methods are registered.</p>
          }

          <div class="section-title">Lost-phone recovery</div>
          <div class="action-row">
            <button mat-stroked-button color="warn" [disabled]="busy" (click)="resetAll()">
              <mat-icon>restart_alt</mat-icon> Reset all methods
            </button>
            <button mat-stroked-button [disabled]="busy" (click)="revokeSessions()">
              <mat-icon>logout</mat-icon> Revoke sign-in sessions
            </button>
            <button mat-flat-button color="primary"
                    [disabled]="busy || !state.tapSupported"
                    [matTooltip]="state.tapSupported
                      ? 'Issue a one-time pass so the customer can sign in and re-register'
                      : 'Entra cannot issue a Temporary Access Pass to an external guest. Reset their methods and have them re-register through their home organisation.'"
                    (click)="issueTap()">
              <mat-icon>vpn_key</mat-icon> Issue Temporary Access Pass
            </button>
          </div>
          <p class="empty">
            Deleting methods does not end existing sessions — revoke them separately.
          </p>

          @if (tap) {
            <div class="tap-panel">
              <strong>Temporary Access Pass — shown once</strong>
              <div class="tap-value">{{ tap.value }}</div>
              <p class="tap-meta">
                Valid until {{ tap.expiresAt | date: 'medium' }}
                ({{ tap.lifetimeMinutes }} min{{ tap.usableOnce ? ', single use' : '' }}).
                Deliver it over a channel you have already verified. It is not stored anywhere
                and cannot be shown again.
              </p>
              <button mat-stroked-button (click)="copyTap()">
                <mat-icon>content_copy</mat-icon> Copy
              </button>
              <button mat-stroked-button (click)="dismissTap()">
                <mat-icon>check</mat-icon> I have delivered this
              </button>
            </div>
          }
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
})
export class User2faDialogComponent implements OnInit, OnDestroy {
  protected readonly user = inject<OperatorUser>(MAT_DIALOG_DATA);

  private readonly adminUsers = inject(AdminUserService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  protected state: EntraMethodsResponse | null = null;
  protected loading = true;
  protected busy = false;

  /**
   * Held only in this component instance and cleared on destroy. Deliberately not routed through
   * MatSnackBar: a snackbar renders into a persistent `aria-live` region that outlives the
   * dialog, which is the wrong place for a credential that authenticates as the customer.
   */
  protected tap: TemporaryAccessPassResponse | null = null;

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.tap = null;
  }

  protected removeMethod(type: string, methodId: string): void {
    this.withStepUp(
      { requireDualControl: false, reason: 'Remove an authentication method', action: ACTION_DELETE },
      result => this.adminUsers.deleteEntraMethod(this.user.id, type, methodId, result.stepUpToken)
        .subscribe({
          next: () => this.done('Authentication method removed.'),
          error: err => this.fail(err),
        }),
    );
  }

  protected resetAll(): void {
    this.withStepUp(
      { requireDualControl: true, reason: 'Reset all authentication methods', action: ACTION_RESET },
      result => this.adminUsers
        .resetEntraMfa(this.user.id, result.stepUpToken, result.dualControlToken ?? '')
        .subscribe({
          next: outcome => {
            // A partial reset is reported rather than hidden: the operator needs to know which
            // method survived before telling the customer they can re-register.
            this.done(outcome.complete
              ? `Removed ${outcome.deleted.length} method(s). Revoke sessions next.`
              : `Removed ${outcome.deleted.length} method(s); ${outcome.failures.length} could not be removed.`);
          },
          error: err => this.fail(err),
        }),
    );
  }

  protected revokeSessions(): void {
    this.withStepUp(
      { requireDualControl: false, reason: 'Revoke sign-in sessions', action: ACTION_REVOKE },
      result => this.adminUsers.revokeEntraSessions(this.user.id, result.stepUpToken)
        .subscribe({
          next: () => this.done('Sign-in sessions revoked.'),
          error: err => this.fail(err),
        }),
    );
  }

  protected issueTap(): void {
    this.withStepUp(
      { requireDualControl: true, reason: 'Issue a Temporary Access Pass', action: ACTION_TAP },
      result => this.adminUsers
        .issueTemporaryAccessPass(
          this.user.id,
          { lifetimeMinutes: 60, usableOnce: true },
          result.stepUpToken,
          result.dualControlToken ?? '',
        )
        .subscribe({
          next: tap => {
            this.tap = tap;
            this.busy = false;
            this.cdr.markForCheck();
          },
          error: err => this.fail(err),
        }),
    );
  }

  protected copyTap(): void {
    if (!this.tap) return;
    void navigator.clipboard.writeText(this.tap.value);
  }

  protected dismissTap(): void {
    this.tap = null;
    this.cdr.markForCheck();
  }

  private withStepUp(
    data: { requireDualControl: boolean; reason: string; action: string },
    run: (result: StepUpDialogResult) => void,
  ): void {
    this.dialog
      .open(StepUpDialogComponent, { data, width: '500px', disableClose: true })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (!result?.stepUpToken) return;
        this.busy = true;
        this.cdr.markForCheck();
        run(result);
      });
  }

  private load(): void {
    this.adminUsers.getEntraMethods(this.user.id).subscribe({
      next: state => {
        this.state = state;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: err => {
        this.loading = false;
        this.fail(err);
      },
    });
  }

  private done(message: string): void {
    this.busy = false;
    this.snackBar.open(message, 'Dismiss', { duration: 5000 });
    this.load();
  }

  private fail(err: { error?: { message?: string } }): void {
    this.busy = false;
    this.snackBar.open(
      err?.error?.message ?? 'The action could not be completed.',
      'Dismiss',
      { duration: 7000, panelClass: 'snack-error' },
    );
    this.cdr.markForCheck();
  }
}
