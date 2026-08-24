import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe } from '@angular/common';
import { RegisterInspectionService } from '../../../../core/api/register-inspection.service';
import { RegisterInspectionRequest } from '../../../../core/models';
import { AuthService } from '../../../../core/auth/auth.service';

type DecisionMode = 'approve' | 'reject';

/**
 * Operator queue for §10 eWpG register inspection requests. `RegisterInspectionController`
 * previously had no frontend caller at all — approve/reject/fulfil were curl-only despite
 * carrying the statutory decision on a customer's inspection right. Scoped per-asset because
 * the backend only exposes `GET /register-inspections/assets/{assetId}` (no global queue).
 */
@Component({
  selector: 'app-register-inspections',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTooltipModule, DatePipe],
  template: `
    <div class="ri-shell">
      <div class="ri-header">
        <h3 class="ri-title">Register inspection requests (§10 eWpG)</h3>
        <button type="button" mat-stroked-button (click)="load()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (requests.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">visibility_off</mat-icon>
          <p>No inspection requests for this asset yet.</p>
        </div>
      } @else {
        <div class="ri-table">
          <div class="ri-row header">
            <span>Requester</span>
            <span>Basis</span>
            <span>Requested</span>
            <span>Status</span>
            <span></span>
          </div>

          @for (r of requests; track r.id) {
            <div class="ri-row">
              <span>
                {{ r.requesterName }}
                @if (r.requesterEmail) { <span class="dimmed small"><br />{{ r.requesterEmail }}</span> }
              </span>
              <span>
                {{ r.legalBasis.replace('_', ' ') }}
                @if (r.statedInterest) { <span class="dimmed small"><br />{{ r.statedInterest }}</span> }
              </span>
              <span class="dimmed">{{ r.createdAt | date:'dd MMM yyyy' }}</span>
              <span class="status-badge" [class]="r.status.toLowerCase()">{{ r.status }}</span>
              <div class="row-actions">
                @if (r.status === 'REQUESTED') {
                  <button type="button" mat-icon-button color="primary" matTooltip="Approve" (click)="openDecisionDialog(r, 'approve')">
                    <mat-icon>check_circle</mat-icon>
                  </button>
                  <button type="button" mat-icon-button color="warn" matTooltip="Reject" (click)="openDecisionDialog(r, 'reject')">
                    <mat-icon>cancel</mat-icon>
                  </button>
                }
                @if (r.status === 'APPROVED') {
                  <button type="button" mat-stroked-button [disabled]="fulfilling.has(r.id)" (click)="fulfil(r)">
                    <mat-icon>picture_as_pdf</mat-icon>
                    {{ fulfilling.has(r.id) ? 'Preparing…' : 'Fulfil' }}
                  </button>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #decisionDialogTpl>
      <h2 mat-dialog-title>{{ decisionMode === 'approve' ? 'Approve' : 'Reject' }} Inspection Request</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="decisionReason"
            placeholder="{{ decisionMode === 'approve' ? 'e.g. §10(2) eWpRV legitimate interest confirmed' : 'e.g. stated interest insufficient' }}">
          </textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button [color]="decisionMode === 'approve' ? 'primary' : 'warn'"
                [disabled]="!decisionReason.trim()" (click)="submitDecision()">
          {{ decisionMode === 'approve' ? 'Approve' : 'Reject' }}
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .ri-shell { padding: 1.5rem 0; }
    .ri-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .ri-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }

    .ri-table { display: flex; flex-direction: column; }
    .ri-row {
      display: grid;
      grid-template-columns: 1fr 1fr 120px 120px 140px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .ri-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
    }

    .status-badge {
      display: inline-flex;
      align-items: center;
      padding: .125rem .5rem;
      border-radius: 3px;
      font-size: .6875rem;
      font-weight: 700;
      width: fit-content;
    }
    .status-badge.requested { background: rgba(245,158,11,.15); color: #f59e0b; }
    .status-badge.approved  { background: rgba(96,165,250,.15); color: #60a5fa; }
    .status-badge.fulfilled { background: rgba(74,222,128,.15); color: #4ade80; }
    .status-badge.rejected  { background: rgba(248,113,113,.15); color: #f87171; }

    .row-actions { display: flex; justify-content: flex-end; gap: 4px; }
  `],
})
export class RegisterInspectionsComponent implements OnInit {
  @Input() assetId!: string;
  @ViewChild('decisionDialogTpl') decisionDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(RegisterInspectionService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  requests: RegisterInspectionRequest[] = [];
  loading = false;
  fulfilling = new Set<string>();

  activeRequest: RegisterInspectionRequest | null = null;
  decisionMode: DecisionMode = 'approve';
  decisionReason = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.listForAsset(this.assetId).subscribe({
      next: (page) => {
        this.requests = page.content;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  openDecisionDialog(request: RegisterInspectionRequest, mode: DecisionMode): void {
    this.activeRequest = request;
    this.decisionMode = mode;
    this.decisionReason = '';
    this.dialog.open(this.decisionDialogTpl, { width: '480px' });
  }

  submitDecision(): void {
    const request = this.activeRequest;
    const reason = this.decisionReason.trim();
    if (!request || !reason) return;
    this.dialog.closeAll();

    const actorId = this.authService.getUserId() ?? '';
    const action$ = this.decisionMode === 'approve'
      ? this.service.approve(request.id, actorId, reason)
      : this.service.reject(request.id, actorId, reason);

    action$.subscribe({
      next: () => {
        this.snackBar.open(`Request ${this.decisionMode === 'approve' ? 'approved' : 'rejected'}.`, 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to record decision.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  fulfil(request: RegisterInspectionRequest): void {
    this.fulfilling.add(request.id);
    this.cdr.markForCheck();
    this.service.fulfil(request.id).subscribe({
      next: (pdf) => {
        const url = URL.createObjectURL(pdf);
        const link = document.createElement('a');
        link.href = url;
        link.download = `registereinsicht-${request.id}.pdf`;
        link.click();
        URL.revokeObjectURL(url);
        this.fulfilling.delete(request.id);
        this.snackBar.open('Register extract disclosed. Disclosure hash recorded.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => {
        this.fulfilling.delete(request.id);
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to fulfil request.', 'Dismiss', { duration: 6000 });
      },
    });
  }
}
