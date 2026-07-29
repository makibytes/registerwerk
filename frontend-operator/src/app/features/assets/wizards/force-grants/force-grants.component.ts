import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, SlicePipe } from '@angular/common';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';
import { TokenAdminGrantService } from '../../../../core/api/token-admin-grant.service';
import { TokenAdminGrant } from '../../../../core/models';

/**
 * Asset-scoped ASSET_TOKEN_ADMIN grant management, embedded as a tab on the asset
 * detail page. Gates forcedTransfer/forcedApprove/forceBurn beyond REGISTRY_ADMIN — by
 * default nobody has this, not even the asset's own issuer; an operator must explicitly
 * grant it here to a specific issuer/investor entity, validated against a wallet that is
 * already whitelisted (and, for ERC-3643 assets, ONCHAINID-verified) for this asset.
 */
@Component({
  selector: 'app-force-grants',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatTooltipModule, DatePipe, SlicePipe,
  ],
  template: `
    <div class="fg-shell">
      <div class="fg-header">
        <div>
          <h3 class="fg-title">Token admin grants</h3>
          <p class="fg-subtitle">
            Delegated authority to force-transfer, force-approve, or force-burn on this asset.
            Nobody has this by default — not even the issuer.
          </p>
        </div>
        <button mat-raised-button color="warn" (click)="openCreateDialog()">
          <mat-icon>admin_panel_settings</mat-icon>
          Grant permission
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (grants.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">shield</mat-icon>
          <p>No active grants for this asset.</p>
        </div>
      } @else {
        <div class="fg-table">
          <div class="fg-row header">
            <span>Entity</span>
            <span>Wallet</span>
            <span>Basis</span>
            <span>Legal basis</span>
            <span>Expires</span>
            <span></span>
          </div>
          @for (g of grants; track g.id) {
            <div class="fg-row">
              <span class="mono">{{ g.entityId | slice:0:8 }}…</span>
              <span class="mono">{{ g.walletAddress | slice:0:10 }}…</span>
              <span class="basis-badge">{{ g.eligibilityBasis.replace('_', ' ') }}</span>
              <span>{{ g.legalBasis }}</span>
              <span class="dimmed">{{ g.expiresAt ? (g.expiresAt | date:'dd MMM yyyy') : 'No expiry' }}</span>
              <div class="row-actions">
                <button mat-stroked-button color="warn" [disabled]="revoking.has(g.id)"
                        matTooltip="Revoke — requires step-up auth + a second approver"
                        (click)="revoke(g)">
                  <mat-icon>block</mat-icon>
                  Revoke
                </button>
              </div>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #createDialog>
      <h2 mat-dialog-title>Grant ASSET_TOKEN_ADMIN</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:460px;padding-top:8px">
        <div class="warn-banner">
          Requires <strong>step-up authentication and dual control (4-eyes)</strong>. The
          grantee's wallet must already be whitelisted for this asset (and, for ERC-3643
          assets, ONCHAINID-verified) or the grant will be rejected.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Entity ID (issuer or investor of this asset) *</mat-label>
          <input matInput [(ngModel)]="form.entityId" placeholder="UUID" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Wallet address *</mat-label>
          <input matInput [(ngModel)]="form.walletAddress" placeholder="0x…" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Chain config ID (required only if granting to the issuer)</mat-label>
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
                [disabled]="!form.entityId || !form.walletAddress || !form.legalBasis">
          <mat-icon>admin_panel_settings</mat-icon>
          Grant
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .fg-shell { padding: 1.5rem 0; }
    .fg-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; margin-bottom: 1.25rem; }
    .fg-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .fg-subtitle { font-size: .8125rem; color: var(--rw-text-secondary); margin: .25rem 0 0; max-width: 480px; }
    .dimmed { color: var(--rw-text-secondary); }
    .mono { font-family: 'IBM Plex Mono', monospace; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }

    .fg-table { display: flex; flex-direction: column; }
    .fg-row {
      display: grid;
      grid-template-columns: 110px 130px 200px 1fr 130px 110px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .fg-row.header { font-size: .6875rem; letter-spacing: .06em; text-transform: uppercase; color: var(--rw-text-muted); }

    .basis-badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .6875rem;
      font-weight: 700;
      color: var(--rw-accent, #F59E0B);
    }

    .row-actions { display: flex; justify-content: flex-end; }

    .warn-banner {
      font-size: .75rem;
      color: var(--rw-text-secondary);
      padding: .625rem .75rem;
      background: rgba(239,68,68,.07);
      border: 1px solid rgba(239,68,68,.18);
      border-radius: 6px;
    }
  `],
})
export class ForceGrantsComponent implements OnInit {
  @Input() assetId!: string;
  @ViewChild('createDialog') createDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(TokenAdminGrantService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  grants: TokenAdminGrant[] = [];
  loading = false;
  revoking = new Set<string>();

  form = { entityId: '', walletAddress: '', chainConfigId: '', legalBasis: '', expiresAt: '' };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.listForAsset(this.assetId).subscribe({
      next: (grants) => { this.grants = grants; this.loading = false; this.cdr.markForCheck(); },
      error: () => { this.loading = false; this.cdr.markForCheck(); },
    });
  }

  openCreateDialog(): void {
    this.form = { entityId: '', walletAddress: '', chainConfigId: '', legalBasis: '', expiresAt: '' };
    this.dialog.open(this.createDialogTpl, { width: '520px' });
  }

  submitCreate(): void {
    this.dialog.closeAll();

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Grant ASSET_TOKEN_ADMIN on asset ${this.assetId} to entity ${this.form.entityId}`,
        action: 'ASSET_TOKEN_ADMIN_GRANT',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;

      this.service.grantForAsset(this.assetId, {
        entityId: this.form.entityId,
        walletAddress: this.form.walletAddress,
        chainConfigId: this.form.chainConfigId || undefined,
        legalBasis: this.form.legalBasis,
        expiresAt: this.form.expiresAt || undefined,
      }, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (grant) => {
          this.grants = [grant, ...this.grants];
          this.cdr.markForCheck();
          this.snackBar.open('ASSET_TOKEN_ADMIN granted. Audit event recorded.', 'Dismiss', { duration: 5000 });
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to grant permission.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  revoke(grant: TokenAdminGrant): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Revoke ASSET_TOKEN_ADMIN grant ${grant.id}`,
        action: 'ASSET_TOKEN_ADMIN_REVOKE',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;

      const reason = prompt('Revocation reason (required for audit trail):');
      if (!reason) return;

      this.revoking.add(grant.id);
      this.service.revokeForAsset(this.assetId, grant.id, reason, result.stepUpToken, result.dualControlToken!).subscribe({
        next: () => {
          this.grants = this.grants.filter(g => g.id !== grant.id);
          this.revoking.delete(grant.id);
          this.cdr.markForCheck();
          this.snackBar.open('Grant revoked. Audit event recorded.', 'Dismiss', { duration: 5000 });
        },
        error: (err) => {
          this.revoking.delete(grant.id);
          this.cdr.markForCheck();
          this.snackBar.open(err?.error?.message ?? 'Failed to revoke grant.', 'Dismiss', { duration: 6000 });
        },
      });
    });
  }
}
