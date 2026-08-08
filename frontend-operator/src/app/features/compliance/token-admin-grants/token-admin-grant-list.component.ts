import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, TemplateRef, ViewChild, inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { StepUpDialogComponent } from '../../../shared/components/step-up/step-up-dialog.component';
import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { TokenAdminGrantService } from '../../../core/api/token-admin-grant.service';
import { TokenAdminGrant } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * Entity-wide ASSET_TOKEN_ADMIN grant management — a grant here (assetId = null) applies
 * across every asset a customer entity is issuer/holder on, present and future. See the
 * "Token Admin Grants" tab on an asset's detail page for the (more common) asset-scoped
 * variant. Deliberately a separate page from the unrelated orgidentity ecosystem
 * Permissions screen (dApp-marketplace-oriented, org-flat, no asset dimension).
 */
@Component({
  selector: 'app-token-admin-grant-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatTooltipModule, DataTableComponent, PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Token Admin Grants (entity-wide)"
      subtitle="Delegated authority to force-transfer / force-approve / force-burn across ALL of a customer entity's assets — present and future. A much broader trust delegation than the per-asset grant on each asset's detail page; use sparingly.">
    </app-page-header>

    <div class="lookup-row">
      <mat-form-field appearance="outline" style="flex:1;max-width:420px">
        <mat-label>Entity ID</mat-label>
        <input matInput [(ngModel)]="entityId" placeholder="UUID" (keyup.enter)="load()" />
      </mat-form-field>
      <button mat-stroked-button (click)="load()" [disabled]="!entityId">
        <mat-icon>search</mat-icon>
        Load
      </button>
      @if (canManage) {
        <button mat-raised-button color="warn" (click)="openCreateDialog()" [disabled]="!entityId">
          <mat-icon>admin_panel_settings</mat-icon>
          Grant entity-wide permission
        </button>
      }
    </div>

    @if (loadedFor) {
      <rw-data-table
        [columns]="columns"
        [rows]="grants"
        [state]="state"
        (retry)="load()"
        filterPlaceholder="Filter…"
        emptyMessage="No active entity-wide grants for this entity."
        [actionsTemplate]="canManage ? rowActions : undefined">
      </rw-data-table>
    }

    <ng-template #rowActions let-grant>
      <button mat-stroked-button color="warn"
              (click)="revoke(grant)"
              matTooltip="Revoke — requires step-up auth + a second approver">
        <mat-icon>block</mat-icon>
        Revoke
      </button>
    </ng-template>

    <ng-template #createDialog>
      <h2 mat-dialog-title>Grant entity-wide ASSET_TOKEN_ADMIN</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:460px;padding-top:8px">
        <div class="warn-banner">
          Requires <strong>step-up authentication and dual control (4-eyes)</strong>. Applies
          across every current and future asset this entity is issuer/holder on — validated
          against a wallet already bound to this entity's org identity, not a specific asset.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Wallet address *</mat-label>
          <input matInput [(ngModel)]="form.walletAddress" placeholder="0x…" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Chain config ID *</mat-label>
          <input matInput [(ngModel)]="form.chainConfigId" placeholder="UUID" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal basis *</mat-label>
          <input matInput [(ngModel)]="form.legalBasis" placeholder="e.g. Operator discretion per engagement dated …" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Expires at (optional, ISO-8601)</mat-label>
          <input matInput [(ngModel)]="form.expiresAt" placeholder="2027-01-01T00:00:00Z" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn"
                (click)="submitCreate()"
                [disabled]="!form.walletAddress || !form.chainConfigId || !form.legalBasis">
          <mat-icon>admin_panel_settings</mat-icon>
          Grant
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .lookup-row { display: flex; align-items: flex-start; gap: .75rem; margin-bottom: 1.25rem; }
    .warn-banner {
      font-size: .75rem;
      color: var(--rw-text-secondary);
      padding: .625rem .75rem;
      background: rgba(239,68,68,.07);
      border: 1px solid rgba(239,68,68,.18);
      border-radius: 6px;
    }
    @media (max-width: 720px) {
      .lookup-row { align-items: stretch; flex-direction: column; }
      .lookup-row mat-form-field { max-width: none !important; }
    }
  `],
})
export class TokenAdminGrantListComponent {
  @ViewChild('createDialog') createDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(TokenAdminGrantService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  readonly canManage = this.auth.hasRole('REGISTRY_ADMIN');

  entityId = '';
  loadedFor: string | null = null;
  grants: TokenAdminGrant[] = [];
  state: AsyncSectionStatus = 'pending';

  form = { walletAddress: '', chainConfigId: '', legalBasis: '', expiresAt: '' };

  readonly columns: TableColumn[] = [
    { key: 'walletAddress', header: 'Wallet Address', cell: (g: TokenAdminGrant) => g.walletAddress, type: 'mono' },
    { key: 'eligibilityBasis', header: 'Basis', cell: (g: TokenAdminGrant) => g.eligibilityBasis.replace('_', ' ') },
    { key: 'legalBasis', header: 'Legal Basis', cell: (g: TokenAdminGrant) => g.legalBasis },
    { key: 'createdAt', header: 'Granted', cell: (g: TokenAdminGrant) => g.createdAt, type: 'date' },
    { key: 'expiresAt', header: 'Expires', cell: (g: TokenAdminGrant) => g.expiresAt ?? '—', type: 'date' },
  ];

  load(): void {
    if (!this.entityId) return;
    this.state = 'pending';
    this.loadedFor = this.entityId;
    this.cdr.markForCheck();

    this.service.listForEntity(this.entityId).subscribe({
      next: (grants) => {
        this.grants = grants;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openCreateDialog(): void {
    this.form = { walletAddress: '', chainConfigId: '', legalBasis: '', expiresAt: '' };
    this.dialog.open(this.createDialogTpl, { width: '520px' });
  }

  submitCreate(): void {
    this.dialog.closeAll();

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Grant entity-wide ASSET_TOKEN_ADMIN to entity ${this.entityId}`,
        action: 'ASSET_TOKEN_ADMIN_GRANT_ENTITY_WIDE',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;

      this.service.grantForEntity(this.entityId, {
        walletAddress: this.form.walletAddress,
        chainConfigId: this.form.chainConfigId,
        legalBasis: this.form.legalBasis,
        expiresAt: this.form.expiresAt || undefined,
      }, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (grant) => {
          this.grants = [grant, ...this.grants];
          this.cdr.markForCheck();
          this.snackBar.open('Entity-wide ASSET_TOKEN_ADMIN granted. Audit event recorded.', 'Dismiss', { duration: 5000 });
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to grant permission.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  revoke(grant: TokenAdminGrant): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Revoke entity-wide ASSET_TOKEN_ADMIN grant ${grant.id}`,
        action: 'ASSET_TOKEN_ADMIN_REVOKE',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;

      const reason = prompt('Revocation reason (required for audit trail):');
      if (!reason) return;

      this.service.revokeForEntity(this.entityId, grant.id, reason, result.stepUpToken, result.dualControlToken!).subscribe({
        next: () => {
          this.grants = this.grants.filter(g => g.id !== grant.id);
          this.cdr.markForCheck();
          this.snackBar.open('Grant revoked. Audit event recorded.', 'Dismiss', { duration: 5000 });
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to revoke grant.', 'Dismiss', { duration: 6000 }),
      });
    });
  }
}
