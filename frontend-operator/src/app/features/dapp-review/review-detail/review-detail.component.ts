import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';

import { PageHeaderComponent, StatusBadgeComponent } from '@registerwerk/ui';
import { MarketplaceAdminService } from '../../../core/api/marketplace-admin.service';
import { ReviewDetailView } from '../../../core/models';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';

interface DiffLine {
  text: string;
  kind: 'same' | 'added' | 'removed';
}

@Component({
  selector: 'app-review-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTableModule,
    MatTabsModule,
    PageHeaderComponent,
    StatusBadgeComponent,
  ],
  styles: [`
    .meta-card {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 16px;
      padding: 18px;
      margin-bottom: 20px;
      background: var(--rw-surface);
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius-md);
      box-shadow: var(--rw-shadow-xs);
    }

    .meta-label {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--rw-text-muted);
      margin: 0 0 4px;
    }

    .meta-value {
      font-size: 13px;
      margin: 0;
      word-break: break-all;
      &.mono { font-family: 'IBM Plex Mono', monospace; }
    }

    .tab-body { padding: 16px 4px; }

    .manifest-pre {
      background: var(--rw-code-bg);
      color: var(--rw-code-fg);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      padding: 14px;
      border-radius: 8px;
      overflow-x: auto;
      max-height: 560px;
    }

    .diff-view {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      background: var(--rw-code-bg);
      color: var(--rw-code-fg);
      border-radius: 8px;
      padding: 14px;
      overflow-x: auto;
      max-height: 560px;

      .diff-line { white-space: pre; padding: 0 6px; }
      .diff-line.added   { background: var(--rw-approved-bg); }
      .diff-line.removed { background: var(--rw-rejected-bg); text-decoration: line-through; opacity: 0.8; }
    }

    .rationale { color: var(--rw-text-secondary); font-size: 12px; }

    .payment-methods { display: flex; flex-direction: column; gap: 10px; }

    .payment-card {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px 14px;
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius);
      background: var(--rw-surface);

      &.custom { border-color: var(--rw-accent); background: var(--rw-accent-subtle); }
    }

    .payment-card-header { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 13px; }

    .payment-card-meta { font-size: 12px; color: var(--rw-text-secondary); }

    .custom-flag {
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--rw-accent-contrast);
      background: var(--rw-accent);
      border-radius: var(--rw-radius-sm);
      padding: 2px 6px;
    }
  `],
  template: `
    <app-page-header
      [title]="detail ? detail.listing.name + ' v' + detail.version.version : 'Review'"
      subtitle="Manifest review — approval requires step-up and a second approver (4-eyes)">
      <button mat-stroked-button routerLink="/dapp-review">
        <mat-icon>arrow_back</mat-icon>
        Queue
      </button>
      @if (detail && (detail.version.status === 'SUBMITTED' || detail.version.status === 'IN_REVIEW')) {
        <button mat-stroked-button color="warn" (click)="openRejectDialog()">
          <mat-icon>block</mat-icon>
          Reject
        </button>
        <button mat-raised-button color="primary" (click)="approve()">
          <mat-icon>task_alt</mat-icon>
          Approve & anchor onchain
        </button>
      }
    </app-page-header>

    @if (detail; as d) {
      <div class="meta-card">
        <div>
          <p class="meta-label">Status</p>
          <app-status-badge [status]="d.version.status"></app-status-badge>
        </div>
        <div>
          <p class="meta-label">Publisher</p>
          <p class="meta-value">{{ d.listing.publisherName ?? d.listing.publisherEntityId }}</p>
        </div>
        <div>
          <p class="meta-label">Slug / onchain dappId</p>
          <p class="meta-value mono">{{ d.listing.slug }} · {{ d.listing.dappIdHash }}</p>
        </div>
        <div>
          <p class="meta-label">Anchor chain</p>
          <p class="meta-value">{{ d.listing.chainIdentifier ?? '—' }}</p>
        </div>
        <div>
          <p class="meta-label">Manifest keccak256</p>
          <p class="meta-value mono">{{ d.version.manifestHash }}</p>
        </div>
        <div>
          <p class="meta-label">Signed by</p>
          <p class="meta-value mono">{{ d.version.signerWallet ?? '—' }}</p>
        </div>
        <div>
          <p class="meta-label">Submitted</p>
          <p class="meta-value">{{ d.version.submittedAt | date: 'medium' }}</p>
        </div>
      </div>

      <mat-tab-group>
        <mat-tab label="Manifest">
          <div class="tab-body">
            <pre class="manifest-pre">{{ d.manifestRaw }}</pre>
          </div>
        </mat-tab>

        <mat-tab label="Changes vs. published" [disabled]="!d.previousManifestRaw">
          <div class="tab-body">
            <div class="diff-view">
              @for (line of diffLines; track $index) {
                <div class="diff-line {{ line.kind }}">{{ prefix(line.kind) }}{{ line.text }}</div>
              }
            </div>
          </div>
        </mat-tab>

        <mat-tab label="Required permissions">
          <div class="tab-body">
            <table mat-table [dataSource]="d.requiredPermissions" style="width:100%">
              <ng-container matColumnDef="permissionCode">
                <th mat-header-cell *matHeaderCellDef>Permission</th>
                <td mat-cell *matCellDef="let p" style="font-family:'IBM Plex Mono',monospace;font-size:12px">
                  {{ p.permissionCode }}
                </td>
              </ng-container>
              <ng-container matColumnDef="rationale">
                <th mat-header-cell *matHeaderCellDef>Rationale</th>
                <td mat-cell *matCellDef="let p" class="rationale">{{ p.rationale ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="claimTopics">
                <th mat-header-cell *matHeaderCellDef>Claim topics</th>
                <td mat-cell *matCellDef="let p">{{ p.claimTopics.join(', ') || '—' }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="permissionColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: permissionColumns"></tr>
            </table>
          </div>
        </mat-tab>

        <mat-tab [label]="'Payment methods (' + d.paymentMethods.length + ')'">
          <div class="tab-body payment-methods">
            @if (d.paymentMethods.length === 0) {
              <p class="rationale">This dApp declares no payment methods.</p>
            }
            @for (method of d.paymentMethods; track $index) {
              <div class="payment-card" [class.custom]="method.methodType === 'CUSTOM'">
                <div class="payment-card-header">
                  @if (method.methodType === 'RAIL') {
                    <mat-icon style="font-size:18px;height:18px;width:18px">payments</mat-icon>
                    <span>{{ method.displayName }}</span>
                    @if (!method.railEnabled) {
                      <span class="custom-flag" style="background:var(--rw-rejected-fg)">RAIL DISABLED</span>
                    }
                  } @else {
                    <mat-icon style="font-size:18px;height:18px;width:18px">build</mat-icon>
                    <span>{{ method.customName }}</span>
                    <span class="custom-flag">publisher-implemented — review carefully</span>
                  }
                </div>
                @if (method.methodType === 'RAIL') {
                  <p class="payment-card-meta">
                    {{ method.railType }} · {{ method.currency }}
                    @if (method.emtFlag) { · MiCAR EMT }
                    @if (method.issuerName) { · Issuer: {{ method.issuerName }} }
                    @if (method.issuerLei) { · LEI {{ method.issuerLei }} }
                    @if (method.redemptionAtPar) { · redeemable at par }
                    @if (method.whitePaperUrl) {
                      · <a [href]="method.whitePaperUrl" target="_blank" rel="noopener noreferrer">white paper</a>
                    }
                  </p>
                } @else {
                  <p class="payment-card-meta">{{ method.customDescription }}</p>
                }
                @if (method.note) {
                  <p class="rationale">{{ method.note }}</p>
                }
              </div>
            }
          </div>
        </mat-tab>
      </mat-tab-group>
    }

    <!-- Reject dialog -->
    <ng-template #rejectDialog>
      <h2 mat-dialog-title>Reject submission</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <mat-form-field appearance="outline">
          <mat-label>Reason (sent to the publisher)</mat-label>
          <textarea matInput rows="4" [(ngModel)]="rejectReason"
                    placeholder="e.g. image references not digest-pinned; unclear permission rationale"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn" [disabled]="!rejectReason.trim()" (click)="confirmReject()">
          <mat-icon>block</mat-icon>
          Reject
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class ReviewDetailComponent implements OnInit {
  @ViewChild('rejectDialog') rejectDialogTpl!: TemplateRef<unknown>;

  private readonly marketplaceAdminService = inject(MarketplaceAdminService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly permissionColumns = ['permissionCode', 'rationale', 'claimTopics'];

  detail: ReviewDetailView | null = null;
  diffLines: DiffLine[] = [];
  rejectReason = '';

  private get versionId(): string {
    return this.route.snapshot.paramMap.get('versionId')!;
  }

  ngOnInit(): void {
    this.marketplaceAdminService.reviewDetail(this.versionId).subscribe({
      next: (detail) => {
        this.detail = detail;
        this.diffLines = this.computeDiff(detail.previousManifestRaw, detail.manifestRaw);
        this.cdr.markForCheck();
        if (detail.version.status === 'SUBMITTED') {
          this.marketplaceAdminService.startReview(this.versionId).subscribe({
            next: (updated) => {
              this.detail = this.detail ? { ...this.detail, version: updated } : this.detail;
              this.cdr.markForCheck();
            },
            error: (err) => {
              this.snackBar.open(err?.error?.message ?? 'Failed to start review.', 'Dismiss', { duration: 6000 });
            },
          });
        }
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to load review.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  prefix(kind: DiffLine['kind']): string {
    return kind === 'added' ? '+ ' : kind === 'removed' ? '- ' : '  ';
  }

  /** Line-based LCS diff — enough to highlight changed manifest lines for review. */
  private computeDiff(previous: string | null, current: string | null): DiffLine[] {
    const oldLines = (previous ?? '').split('\n');
    const newLines = (current ?? '').split('\n');
    if (!previous) {
      return newLines.map((text) => ({ text, kind: 'same' as const }));
    }

    const m = oldLines.length;
    const n = newLines.length;
    const lcs: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
    for (let i = m - 1; i >= 0; i--) {
      for (let j = n - 1; j >= 0; j--) {
        lcs[i][j] = oldLines[i] === newLines[j]
          ? lcs[i + 1][j + 1] + 1
          : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }

    const lines: DiffLine[] = [];
    let i = 0;
    let j = 0;
    while (i < m && j < n) {
      if (oldLines[i] === newLines[j]) {
        lines.push({ text: newLines[j], kind: 'same' });
        i++; j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        lines.push({ text: oldLines[i], kind: 'removed' });
        i++;
      } else {
        lines.push({ text: newLines[j], kind: 'added' });
        j++;
      }
    }
    while (i < m) lines.push({ text: oldLines[i++], kind: 'removed' });
    while (j < n) lines.push({ text: newLines[j++], kind: 'added' });
    return lines;
  }

  approve(): void {
    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: true, reason: 'dApp marketplace approval', action: 'dApp marketplace approval' },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (!result?.stepUpToken || !result.dualControlToken) return;

        this.marketplaceAdminService
          .approve(this.versionId, null, result.stepUpToken, result.dualControlToken)
          .subscribe({
            next: () => {
              this.snackBar.open('Approved — onchain anchoring submitted.', 'Dismiss', { duration: 5000 });
              this.router.navigate(['/dapp-review']);
            },
            error: (err) => {
              this.snackBar.open(err?.error?.message ?? 'Approval failed.', 'Dismiss', { duration: 6000 });
            },
          });
      });
  }

  openRejectDialog(): void {
    this.rejectReason = '';
    this.dialog.open(this.rejectDialogTpl, { width: '520px' });
  }

  confirmReject(): void {
    const reason = this.rejectReason.trim();
    this.dialog.closeAll();

    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: false, reason: 'dApp marketplace rejection', action: 'dApp marketplace rejection' },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (!result?.stepUpToken) return;

        this.marketplaceAdminService.reject(this.versionId, reason, result.stepUpToken).subscribe({
          next: () => {
            this.snackBar.open('Submission rejected.', 'Dismiss', { duration: 5000 });
            this.router.navigate(['/dapp-review']);
          },
          error: (err) => {
            this.snackBar.open(err?.error?.message ?? 'Rejection failed.', 'Dismiss', { duration: 6000 });
          },
        });
      });
  }
}
