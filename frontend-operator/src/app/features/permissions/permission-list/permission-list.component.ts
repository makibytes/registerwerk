import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { PermissionService } from '../../../core/api/permission.service';
import { ChainService } from '../../../core/api/chain.service';
import {
  ChainHealth,
  EcosystemTrustedIssuerView,
  PermissionDefinitionView,
} from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';

@Component({
  selector: 'app-permission-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTabsModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [`
    .tab-body { padding-top: 16px; }
  `],
  template: `
    <app-page-header
      title="Permission Framework"
      subtitle="Ecosystem permissions dApps enforce onchain — granted per organization, delegated per role">
    </app-page-header>

    <mat-tab-group>
      <mat-tab label="Permissions">
        <div class="tab-body">
          <rw-data-table
            [columns]="permissionColumns"
            [rows]="permissions"
            [state]="permissionState"
            (retry)="loadPermissions()"
            filterPlaceholder="Filter by code or name…"
            emptyMessage="No permissions defined yet."
            [actionsTemplate]="permissionActions">
            <button tableToolbar mat-raised-button color="primary" (click)="openCreateDialog()">
              <mat-icon>add</mat-icon>
              Define permission
            </button>
          </rw-data-table>

          <ng-template #permissionActions let-permission>
            <button mat-icon-button color="primary" (click)="openDetail(permission)"
                    matTooltip="Manage grants">
              <mat-icon>chevron_right</mat-icon>
            </button>
          </ng-template>
        </div>
      </mat-tab>

      <mat-tab label="Trusted Issuers">
        <div class="tab-body">
          <rw-data-table
            [columns]="issuerColumns"
            [rows]="issuers"
            [state]="issuerState"
            (retry)="loadIssuers()"
            filterPlaceholder="Filter by address…"
            emptyMessage="No trusted claim issuers registered."
            [actionsTemplate]="issuerActions">
            <button tableToolbar mat-raised-button color="primary" (click)="openIssuerDialog()">
              <mat-icon>add_moderator</mat-icon>
              Add trusted issuer
            </button>
          </rw-data-table>

          <ng-template #issuerActions let-issuer>
            @if (issuer.status === 'ACTIVE' || issuer.status === 'PENDING') {
              <button mat-icon-button color="warn" (click)="removeIssuer(issuer)"
                      matTooltip="Remove trusted issuer (step-up)">
                <mat-icon>remove_moderator</mat-icon>
              </button>
            }
          </ng-template>
        </div>
      </mat-tab>
    </mat-tab-group>

    <!-- Define permission dialog -->
    <ng-template #createDialog>
      <h2 mat-dialog-title>Define permission</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <mat-form-field appearance="outline">
          <mat-label>Code</mat-label>
          <input matInput [(ngModel)]="newCode" placeholder="e.g. loandesk.open" />
          <mat-hint>Namespaced, lowercase: &lt;dapp&gt;.&lt;action&gt; — hashed to bytes32 onchain</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput [(ngModel)]="newName" placeholder="e.g. Open loan requests" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput rows="3" [(ngModel)]="newDescription"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                [disabled]="!newCode.trim() || !newName.trim() || submitting"
                (click)="submitCreate()">
          <mat-icon>add</mat-icon>
          Define
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Add trusted issuer dialog -->
    <ng-template #issuerDialog>
      <h2 mat-dialog-title>Add trusted claim issuer</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Claims on org ONCHAINIDs only count when signed by an issuer trusted for the topic.
          Requires step-up.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Chain</mat-label>
          <mat-select [(ngModel)]="issuerChainId">
            @for (chain of chains; track chain.id) {
              <mat-option [value]="chain.id">{{ chain.displayName }} ({{ chain.identifier }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Issuer address</mat-label>
          <input matInput [(ngModel)]="issuerAddress" placeholder="0x…" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Claim topics (comma-separated numbers)</mat-label>
          <input matInput [(ngModel)]="issuerTopics" placeholder="e.g. 1, 2" />
          <mat-hint>1 = KYC, 2 = AML, 3 = Accreditation</mat-hint>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                [disabled]="!issuerChainId || !isValidAddress(issuerAddress) || parseTopics().length === 0 || submitting"
                (click)="submitIssuer()">
          <mat-icon>add_moderator</mat-icon>
          Add issuer
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class PermissionListComponent implements OnInit {
  @ViewChild('createDialog') createDialogTpl!: TemplateRef<unknown>;
  @ViewChild('issuerDialog') issuerDialogTpl!: TemplateRef<unknown>;

  private readonly permissionService = inject(PermissionService);
  private readonly chainService = inject(ChainService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  permissions: PermissionDefinitionView[] = [];
  permissionState: AsyncSectionStatus = 'pending';

  issuers: EcosystemTrustedIssuerView[] = [];
  issuerState: AsyncSectionStatus = 'pending';
  chains: ChainHealth[] = [];

  newCode = '';
  newName = '';
  newDescription = '';
  issuerChainId: string | null = null;
  issuerAddress = '';
  issuerTopics = '1';
  submitting = false;

  readonly permissionColumns: TableColumn[] = [
    { key: 'code', header: 'Code', cell: (r: PermissionDefinitionView) => r.code, type: 'mono' },
    { key: 'name', header: 'Name', cell: (r: PermissionDefinitionView) => r.name },
    { key: 'status', header: 'Status', cell: (r: PermissionDefinitionView) => r.status, type: 'badge' },
    { key: 'permissionHash', header: 'Onchain id', cell: (r: PermissionDefinitionView) => r.permissionHash, type: 'mono' },
    { key: 'createdAt', header: 'Defined', cell: (r: PermissionDefinitionView) => r.createdAt, type: 'date' },
  ];

  readonly issuerColumns: TableColumn[] = [
    { key: 'issuerAddress', header: 'Issuer', cell: (r: EcosystemTrustedIssuerView) => r.issuerAddress, type: 'mono' },
    { key: 'claimTopics', header: 'Topics', cell: (r: EcosystemTrustedIssuerView) => r.claimTopics.join(', ') },
    { key: 'status', header: 'Status', cell: (r: EcosystemTrustedIssuerView) => r.status, type: 'badge' },
    { key: 'createdAt', header: 'Added', cell: (r: EcosystemTrustedIssuerView) => r.createdAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.loadPermissions();
    this.loadIssuers();
  }

  loadPermissions(): void {
    this.permissionState = 'pending';
    this.cdr.markForCheck();
    this.permissionService.list().subscribe({
      next: (permissions) => {
        this.permissions = permissions;
        this.permissionState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.permissionState = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  loadIssuers(): void {
    this.issuerState = 'pending';
    this.cdr.markForCheck();
    this.permissionService.trustedIssuers().subscribe({
      next: (issuers) => {
        this.issuers = issuers;
        this.issuerState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.issuerState = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openDetail(permission: PermissionDefinitionView): void {
    this.router.navigate(['/permissions', permission.id]);
  }

  openCreateDialog(): void {
    this.newCode = '';
    this.newName = '';
    this.newDescription = '';
    this.dialog.open(this.createDialogTpl, { width: '500px' });
  }

  submitCreate(): void {
    this.submitting = true;
    this.permissionService
      .create({
        code: this.newCode.trim(),
        name: this.newName.trim(),
        description: this.newDescription.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.submitting = false;
          this.dialog.closeAll();
          this.snackBar.open('Permission defined.', 'Dismiss', { duration: 5000 });
          this.loadPermissions();
        },
        error: (err) => {
          this.submitting = false;
          this.cdr.markForCheck();
          this.snackBar.open(err?.error?.message ?? 'Failed to define permission.', 'Dismiss', {
            duration: 6000,
          });
        },
      });
  }

  isValidAddress(address: string): boolean {
    return /^0x[0-9a-fA-F]{40}$/.test(address.trim());
  }

  parseTopics(): number[] {
    return this.issuerTopics
      .split(',')
      .map((topic) => Number(topic.trim()))
      .filter((topic) => Number.isInteger(topic) && topic > 0);
  }

  openIssuerDialog(): void {
    this.issuerAddress = '';
    this.issuerTopics = '1';
    this.issuerChainId = null;
    this.chainService.getHealth().subscribe({
      next: (chains) => {
        this.chains = chains.filter((chain) => chain.chainType === 'EVM' && chain.enabled);
        this.cdr.markForCheck();
      },
    });
    this.dialog.open(this.issuerDialogTpl, { width: '520px' });
  }

  submitIssuer(): void {
    const body = {
      chainConfigId: this.issuerChainId!,
      issuerAddress: this.issuerAddress.trim(),
      claimTopics: this.parseTopics(),
    };
    this.dialog.closeAll();

    this.withStepUp('Trusted claim issuer registration', (token, dualControlToken) =>
      this.permissionService.addTrustedIssuer(body, token, dualControlToken).subscribe({
        next: () => {
          this.snackBar.open('Trusted issuer registration submitted.', 'Dismiss', { duration: 5000 });
          this.loadIssuers();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to add trusted issuer.', 'Dismiss', {
            duration: 6000,
          });
        },
      }),
    );
  }

  removeIssuer(issuer: EcosystemTrustedIssuerView): void {
    this.withStepUp('Trusted claim issuer removal', (token, dualControlToken) =>
      this.permissionService.removeTrustedIssuer(issuer.id, token, dualControlToken).subscribe({
        next: () => {
          this.snackBar.open('Trusted issuer removal submitted.', 'Dismiss', { duration: 5000 });
          this.loadIssuers();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to remove trusted issuer.', 'Dismiss', {
            duration: 6000,
          });
        },
      }),
    );
  }

  /** Both actions here are dual-control (requireSecondApprover=true) on the backend. */
  private withStepUp(reason: string, action: (stepUpToken: string, dualControlToken: string) => void): void {
    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: true, reason, action: reason },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (result?.stepUpToken && result.dualControlToken) {
          action(result.stepUpToken, result.dualControlToken);
        }
      });
  }
}
