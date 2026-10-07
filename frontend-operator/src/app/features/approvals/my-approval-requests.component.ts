import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApprovalClaim, ApprovalRequestView, approvalErrorMessage, claimApproval } from '@registerwerk/ui';
import { EMPTY, catchError } from 'rxjs';
import { ApprovalQueueService } from '../../core/api/approval-queue.service';
import { ApprovalRequestCardComponent } from './approval-request-card.component';

/**
 * The caller's own requests in every status. Cancel withdraws an open request; Claim turns an approved one
 * into the single-use approver token for the manual path (pasted into the step-up dialog's token field).
 * The dialogs themselves claim and use the approval automatically, so this is the fallback.
 */
@Component({
  selector: 'app-my-approval-requests',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, ApprovalRequestCardComponent],
  styles: [`
    .toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; font-size: 13px; color: var(--rw-text-secondary); }
    .toolbar .spacer { flex: 1; }
    .empty { padding: 32px 0; text-align: center; color: var(--rw-text-muted); font-size: 13px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; }
    .claimed { margin-top: 10px; }
    .claimed .warn { font-size: 12px; color: var(--rw-text-warning); margin: 4px 0 0; }
    .token-row { display: flex; gap: 8px; align-items: flex-start; }
    .token-row mat-form-field { flex: 1; }
    .error { color: var(--rw-text-danger); }
    .hint { font-size: 12px; color: var(--rw-text-muted); margin: 4px 0 0; }
  `],
  template: `
    <div class="toolbar">
      <span>Requests you filed. Approved ones must be used before they expire.</span>
      <span class="spacer"></span>
      <button type="button" mat-stroked-button (click)="load()" [disabled]="loading()">
        <mat-icon>refresh</mat-icon> Refresh
      </button>
    </div>

    @if (loadError()) {
      <p class="error" role="alert">{{ loadError() }}</p>
    }

    @for (req of requests(); track req.id) {
      <app-approval-request-card [request]="req" [now]="now()">
        @if (req.status === 'PENDING' || req.status === 'APPROVED') {
          <div class="row">
            @if (req.status === 'APPROVED') {
              <button type="button" mat-flat-button color="primary" [disabled]="busyId() === req.id" (click)="claim(req)">
                <mat-icon>key</mat-icon> Claim approver token
              </button>
            }
            <button type="button" mat-stroked-button [disabled]="busyId() === req.id" (click)="cancel(req)">
              <mat-icon>cancel</mat-icon> Cancel request
            </button>
          </div>
          @if (req.status === 'APPROVED') {
            <p class="hint">Normally the confirm dialog of the action claims and uses the approval for you. Claim by hand
              only to paste the token into the "Second approver's step-up token" field of that dialog.</p>
          }
        }
        @if (claims()[req.id]; as claim) {
          <div class="claimed">
            <div class="token-row">
              <mat-form-field appearance="outline">
                <mat-label>Approver token (single use)</mat-label>
                <input matInput [value]="claim.approvalToken" readonly />
              </mat-form-field>
              <button type="button" mat-stroked-button (click)="copy(claim)"><mat-icon>content_copy</mat-icon> Copy</button>
            </div>
            <p class="warn">Valid until {{ claim.expiresAt | date: 'mediumTime' }}. Send it in the {{ claim.headerName }} header of
              {{ claim.target }} only; do not keep it in shared chat.</p>
          </div>
        }
      </app-approval-request-card>
    } @empty {
      @if (loaded()) {
        <div class="empty">You have not filed any approval requests.</div>
      }
    }
  `,
})
export class MyApprovalRequestsComponent {
  private readonly api = inject(ApprovalQueueService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly now = input<number>(Date.now());

  protected readonly requests = signal<ApprovalRequestView[]>([]);
  protected readonly loading = signal(false);
  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);
  protected readonly claims = signal<Record<string, ApprovalClaim>>({});

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.mine(0, 50).pipe(
      catchError(err => {
        this.loadError.set(approvalErrorMessage(err, 'Your requests could not be loaded.'));
        this.loading.set(false);
        return EMPTY;
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(page => {
      this.requests.set(page.content);
      this.loadError.set(null);
      this.loading.set(false);
      this.loaded.set(true);
    });
  }

  protected claim(req: ApprovalRequestView): void {
    this.busyId.set(req.id);
    claimApproval(this.api, req.id).subscribe({
      next: claim => {
        this.claims.update(c => ({ ...c, [req.id]: claim }));
        this.busyId.set(null);
        this.load();
      },
      error: err => {
        this.busyId.set(null);
        this.snackBar.open(approvalErrorMessage(err, 'The approval could not be claimed.'), 'Dismiss', { duration: 7000 });
        this.load();
      },
    });
  }

  protected cancel(req: ApprovalRequestView): void {
    this.busyId.set(req.id);
    this.api.cancel(req.id).subscribe({
      next: () => {
        this.busyId.set(null);
        this.snackBar.open('Request cancelled.', 'Dismiss', { duration: 3000 });
        this.load();
      },
      error: err => {
        this.busyId.set(null);
        this.snackBar.open(approvalErrorMessage(err, 'The request could not be cancelled.'), 'Dismiss', { duration: 7000 });
        this.load();
      },
    });
  }

  protected copy(claim: ApprovalClaim): void {
    void navigator.clipboard.writeText(claim.approvalToken);
    this.snackBar.open('Approver token copied.', 'Dismiss', { duration: 3000 });
  }
}
