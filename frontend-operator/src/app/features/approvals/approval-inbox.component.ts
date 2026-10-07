import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApprovalRequestView, approvalErrorMessage } from '@registerwerk/ui';
import { EMPTY, fromEvent, map, startWith, distinctUntilChanged, switchMap, timer, catchError } from 'rxjs';
import { ApprovalQueueService } from '../../core/api/approval-queue.service';
import { ApprovalRequestCardComponent } from './approval-request-card.component';

interface Decision {
  id: string;
  kind: 'approve' | 'reject';
}

/** Pending requests of other people. Approve asks for the approver's own TOTP code, Reject takes an optional note. */
@Component({
  selector: 'app-approval-inbox',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, ApprovalRequestCardComponent],
  styles: [`
    .toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; font-size: 13px; color: var(--rw-text-secondary); }
    .toolbar .spacer { flex: 1; }
    .empty { padding: 32px 0; text-align: center; color: var(--rw-text-muted); font-size: 13px; }
    .decision-form { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
    .decision-form mat-form-field { width: 240px; }
    .decision-form .confirm { margin-top: 6px; }
    .row { display: flex; gap: 8px; }
    .error { color: var(--rw-text-danger); }
  `],
  template: `
    <div class="toolbar">
      <span>{{ total() }} open request{{ total() === 1 ? '' : 's' }} from other administrators</span>
      <span class="spacer"></span>
      <button type="button" mat-stroked-button (click)="reload()" [disabled]="loading()">
        <mat-icon>refresh</mat-icon> Refresh
      </button>
    </div>

    @if (loadError()) {
      <p class="error" role="alert">{{ loadError() }}</p>
    }

    @for (req of requests(); track req.id) {
      <app-approval-request-card [request]="req" [now]="now()">
        @if (decision()?.id === req.id) {
          @if (decision()!.kind === 'approve') {
            <div class="decision-form">
              <mat-form-field appearance="outline">
                <mat-label>Your authenticator code</mat-label>
                <mat-icon matPrefix>smartphone</mat-icon>
                <input matInput [(ngModel)]="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6"
                       placeholder="123456" (keydown.enter)="approve(req)" />
              </mat-form-field>
              <button type="button" mat-flat-button color="primary" class="confirm"
                      [disabled]="busy() || code.trim().length < 6" (click)="approve(req)">
                <mat-icon>check</mat-icon> {{ busy() ? 'Approving…' : 'Confirm approval' }}
              </button>
              <button type="button" mat-button class="confirm" [disabled]="busy()" (click)="closeDecision()">Back</button>
            </div>
          } @else {
            <div class="decision-form">
              <mat-form-field appearance="outline">
                <mat-label>Note (optional)</mat-label>
                <input matInput [(ngModel)]="note" maxlength="500" (keydown.enter)="reject(req)" />
              </mat-form-field>
              <button type="button" mat-flat-button color="warn" class="confirm" [disabled]="busy()" (click)="reject(req)">
                <mat-icon>close</mat-icon> {{ busy() ? 'Rejecting…' : 'Confirm rejection' }}
              </button>
              <button type="button" mat-button class="confirm" [disabled]="busy()" (click)="closeDecision()">Back</button>
            </div>
          }
        } @else {
          <div class="row">
            <button type="button" mat-flat-button color="primary" (click)="openDecision(req, 'approve')">
              <mat-icon>check</mat-icon> Approve
            </button>
            <button type="button" mat-stroked-button (click)="openDecision(req, 'reject')">
              <mat-icon>close</mat-icon> Reject
            </button>
          </div>
        }
      </app-approval-request-card>
    } @empty {
      @if (loaded()) {
        <div class="empty">No requests are waiting for your approval.</div>
      }
    }
  `,
})
export class ApprovalInboxComponent {
  private readonly api = inject(ApprovalQueueService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  /** Shared one-second clock of the page (countdowns). */
  readonly now = input<number>(Date.now());

  protected readonly requests = signal<ApprovalRequestView[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly decision = signal<Decision | null>(null);
  protected readonly busy = signal(false);
  protected code = '';
  protected note = '';

  constructor() {
    // Refresh every 20 s while the tab is visible; a hidden tab does not poll.
    fromEvent(document, 'visibilitychange').pipe(
      map(() => !document.hidden), startWith(!document.hidden), distinctUntilChanged(),
      switchMap(visible => visible ? timer(0, 20_000) : EMPTY),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(() => this.load());
  }

  reload(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.api.pending(0, 50).pipe(
      catchError(err => {
        this.loadError.set(approvalErrorMessage(err, 'The approval inbox could not be loaded.'));
        this.loading.set(false);
        return EMPTY;
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(page => {
      this.requests.set(page.content);
      this.total.set(page.totalElements);
      this.loadError.set(null);
      this.loading.set(false);
      this.loaded.set(true);
      // a request decided by someone else meanwhile closes its form
      const open = this.decision();
      if (open && !page.content.some(r => r.id === open.id)) this.decision.set(null);
    });
  }

  protected openDecision(req: ApprovalRequestView, kind: Decision['kind']): void {
    this.code = '';
    this.note = '';
    this.decision.set({ id: req.id, kind });
  }

  protected closeDecision(): void {
    this.decision.set(null);
  }

  protected approve(req: ApprovalRequestView): void {
    if (this.busy() || this.code.trim().length < 6) return;
    this.busy.set(true);
    this.api.approve(req.id, this.code.trim()).subscribe({
      next: () => this.done(`${req.action} approved. The requester can now confirm and run it.`),
      error: err => this.failed(err, 'Approval failed.'),
    });
  }

  protected reject(req: ApprovalRequestView): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.api.reject(req.id, this.note.trim()).subscribe({
      next: () => this.done(`${req.action} rejected.`),
      error: err => this.failed(err, 'Rejection failed.'),
    });
  }

  private done(message: string): void {
    this.busy.set(false);
    this.decision.set(null);
    this.code = '';
    this.snackBar.open(message, 'Dismiss', { duration: 4000 });
    this.load();
  }

  private failed(err: unknown, fallback: string): void {
    this.busy.set(false);
    this.code = '';
    this.snackBar.open(approvalErrorMessage(err, fallback), 'Dismiss', { duration: 7000 });
    this.load();
  }
}
