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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent, StatusBadgeComponent } from '@registerwerk/ui';
import { OrgIdentityService } from '../../../core/api/org-identity.service';
import { OrgMemberWalletView, OrgRegistrationView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';

@Component({
  selector: 'app-organization-detail',
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
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
    StatusBadgeComponent,
  ],
  styles: [`
    .org-card {
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

    .org-field {
      .org-field-label {
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--rw-text-muted);
        margin: 0 0 4px;
      }

      .org-field-value {
        font-size: 13px;
        color: var(--rw-text-primary);
        margin: 0;
        word-break: break-all;

        &.mono { font-family: 'IBM Plex Mono', monospace; }
      }
    }

    .suspension-note {
      grid-column: 1 / -1;
      font-size: 13px;
      color: var(--rw-text-danger);
      margin: 0;
    }

    .section-title {
      font-size: 15px;
      font-weight: 700;
      margin: 24px 0 12px;
      color: var(--rw-text-primary);
    }
  `],
  template: `
    <app-page-header
      [title]="org?.entityName ?? 'Organization'"
      subtitle="Onchain organization, member wallets and status">
      <button mat-stroked-button routerLink="/organizations">
        <mat-icon>arrow_back</mat-icon>
        All organizations
      </button>
      @if (org?.status === 'ACTIVE') {
        <button mat-raised-button color="warn" (click)="suspend()">
          <mat-icon>block</mat-icon>
          Suspend
        </button>
      }
      @if (org?.status === 'SUSPENDED') {
        <button mat-raised-button color="primary" (click)="reinstate()">
          <mat-icon>restart_alt</mat-icon>
          Reinstate
        </button>
      }
    </app-page-header>

    @if (org; as o) {
      <div class="org-card">
        <div class="org-field">
          <p class="org-field-label">Status</p>
          <app-status-badge [status]="o.status"></app-status-badge>
        </div>
        <div class="org-field">
          <p class="org-field-label">Org ONCHAINID</p>
          <p class="org-field-value mono">{{ o.orgAddress }}</p>
        </div>
        <div class="org-field">
          <p class="org-field-label">Chain</p>
          <p class="org-field-value">{{ o.chainIdentifier ?? '—' }}</p>
        </div>
        <div class="org-field">
          <p class="org-field-label">Registration tx</p>
          <p class="org-field-value mono">{{ o.registeredTx ?? '—' }}</p>
        </div>
        <div class="org-field">
          <p class="org-field-label">Registered</p>
          <p class="org-field-value">{{ o.createdAt | date: 'medium' }}</p>
        </div>
        @if (o.status === 'SUSPENDED') {
          <p class="suspension-note">
            Suspended {{ o.suspendedAt | date: 'medium' }} — {{ o.suspensionReason }}
          </p>
        }
      </div>
    }

    <div class="section-title">Member wallets</div>
    <rw-data-table
      [columns]="walletColumns"
      [rows]="wallets"
      [state]="walletState"
      filterPlaceholder="Filter by address, label, role…"
      emptyMessage="No wallets bound to this organization."
      [actionsTemplate]="walletActions">
    </rw-data-table>

    <ng-template #walletActions let-wallet>
      @if (wallet.status === 'ACTIVE' || wallet.status === 'PENDING') {
        <button mat-icon-button color="warn" (click)="removeWallet(wallet)"
                matTooltip="Unbind wallet from organization">
          <mat-icon>link_off</mat-icon>
        </button>
      }
    </ng-template>

    <!-- Suspension reason dialog -->
    <ng-template #suspendDialog>
      <h2 mat-dialog-title>Suspend organization</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Suspension freezes active-member status for all wallets of
          <strong>{{ org?.entityName }}</strong> and disables org self-administration.
          Requires step-up and a second approver (4-eyes).
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="suspendReason"
                    placeholder="e.g. unresolved sanctions hit, court order"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn" [disabled]="!suspendReason.trim()"
                (click)="confirmSuspend()">
          <mat-icon>block</mat-icon>
          Suspend
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class OrganizationDetailComponent implements OnInit {
  @ViewChild('suspendDialog') suspendDialogTpl!: TemplateRef<unknown>;

  private readonly orgIdentityService = inject(OrgIdentityService);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  org: OrgRegistrationView | null = null;
  wallets: OrgMemberWalletView[] = [];
  walletState: AsyncSectionStatus = 'pending';
  suspendReason = '';

  readonly walletColumns: TableColumn[] = [
    { key: 'walletAddress', header: 'Wallet', cell: (r: OrgMemberWalletView) => r.walletAddress, type: 'mono' },
    { key: 'label', header: 'Label', cell: (r: OrgMemberWalletView) => r.label ?? '—' },
    { key: 'roles', header: 'Roles', cell: (r: OrgMemberWalletView) => r.roles.join(', ') || '—' },
    { key: 'status', header: 'Status', cell: (r: OrgMemberWalletView) => r.status, type: 'badge' },
    { key: 'createdAt', header: 'Bound', cell: (r: OrgMemberWalletView) => r.createdAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.load();
  }

  private get orgId(): string {
    return this.route.snapshot.paramMap.get('id')!;
  }

  load(): void {
    this.orgIdentityService.get(this.orgId).subscribe({
      next: (org) => {
        this.org = org;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to load organization.', 'Dismiss', { duration: 6000 });
      },
    });
    this.loadWallets();
  }

  loadWallets(): void {
    this.walletState = 'pending';
    this.cdr.markForCheck();

    this.orgIdentityService.wallets(this.orgId).subscribe({
      next: (wallets) => {
        this.wallets = wallets;
        this.walletState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.walletState = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  suspend(): void {
    this.suspendReason = '';
    this.dialog.open(this.suspendDialogTpl, { width: '500px' });
  }

  confirmSuspend(): void {
    const reason = this.suspendReason.trim();
    this.dialog.closeAll();

    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: true, reason: 'Org suspension', action: 'Org suspension' },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (!result?.stepUpToken || !result.dualControlToken) return;

        this.orgIdentityService
          .suspend(this.orgId, reason, result.stepUpToken, result.dualControlToken)
          .subscribe({
            next: () => {
              this.snackBar.open('Organization suspended.', 'Dismiss', { duration: 5000 });
              this.load();
            },
            error: (err) => {
              this.snackBar.open(err?.error?.message ?? 'Failed to suspend organization.', 'Dismiss', {
                duration: 6000,
              });
            },
          });
      });
  }

  reinstate(): void {
    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: true, reason: 'Org reinstatement', action: 'Org reinstatement' },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (!result?.stepUpToken || !result.dualControlToken) return;

        this.orgIdentityService.reinstate(this.orgId, result.stepUpToken, result.dualControlToken).subscribe({
          next: () => {
            this.snackBar.open('Organization reinstated.', 'Dismiss', { duration: 5000 });
            this.load();
          },
          error: (err) => {
            this.snackBar.open(err?.error?.message ?? 'Failed to reinstate organization.', 'Dismiss', {
              duration: 6000,
            });
          },
        });
      });
  }

  removeWallet(wallet: OrgMemberWalletView): void {
    this.orgIdentityService.removeWallet(this.orgId, wallet.id).subscribe({
      next: () => {
        this.snackBar.open(`Wallet ${wallet.walletAddress} unbound.`, 'Dismiss', { duration: 5000 });
        this.loadWallets();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to unbind wallet.', 'Dismiss', { duration: 6000 });
      },
    });
  }
}
