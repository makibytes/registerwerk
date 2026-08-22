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
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { PageHeaderComponent } from '@registerwerk/ui';
import { FinalityPolicyService } from '../../../core/api/finality-policy.service';
import {
  FinalityLevel,
  FinalityPolicyAssignmentView,
  FinalityPolicyOverrideView,
  FinalityPolicyProfile,
  TokenStandard,
} from '../../../core/models';
import { AuthService } from '../../../core/auth/auth.service';
import { StepUpDialogComponent, StepUpDialogResult } from '../../../shared/components/step-up/step-up-dialog.component';

const ALL_TOKEN_STANDARDS: TokenStandard[] = [
  'ERC20', 'ERC721', 'ERC1155', 'ERC3643', 'CONF_ERC20', 'CONF_ERC3643',
  'SPL', 'SPL_2022', 'STARKNET_ERC20', 'STELLAR_ASSET', 'CANTON_TOKEN',
  'ERC3525', 'ERC4626', 'ERC7540', 'STARKNET_ERC3525',
  'DAML_BOND_FIXED', 'DAML_BOND_FLOATING', 'DAML_BOND_ZERO',
  'SPL_2022_BOND', 'SPL_2022_CONFIDENTIAL',
];

const PROFILES: FinalityPolicyProfile[] = ['FAST', 'BALANCED', 'CONSERVATIVE'];
const LEVELS: FinalityLevel[] = ['SAFE', 'FINALIZED'];

/**
 * Editor for the finality policy model — resolution chain most-specific-wins: asset override →
 * token-standard profile → global profile → compiled-in default. Sits next to
 * {@code ChainConfig}'s screen for the same reason the backend controller does: {@code
 * chain_config.finality_model} and the policy that gates on top of it belong together. See
 * {@code UnresolvedCompensationQueueComponent} for the sibling screen showing what this policy
 * currently blocks.
 */
@Component({
  selector: 'app-finality-policy',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Finality Policy"
      subtitle="Which FinalityLevel each gated operation requires — resolved asset override → token-standard → global → compiled-in default">
    </app-page-header>

    <section class="policy-section">
      <h2>Global profile</h2>
      <p class="hint">Applies to every asset with no more specific token-standard or asset-level assignment.</p>
      <div class="row">
        <mat-form-field appearance="outline">
          <mat-label>Profile</mat-label>
          <mat-select [(ngModel)]="globalProfile">
            @for (p of profiles; track p) {
              <mat-option [value]="p">{{ p }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <button mat-flat-button color="primary" [disabled]="!canManage" (click)="saveGlobalProfile()">
          Save
        </button>
      </div>
    </section>

    <section class="policy-section">
      <h2>Token-standard profiles</h2>
      <p class="hint">Overrides the global profile for every asset of that standard, unless the asset itself has an override.</p>
      <table class="standards-table">
        <thead>
          <tr><th>Token standard</th><th>Profile</th><th></th></tr>
        </thead>
        <tbody>
          @for (standard of tokenStandards; track standard) {
            <tr>
              <td class="mono">{{ standard }}</td>
              <td>
                <mat-form-field appearance="outline" class="compact">
                  <mat-select [(ngModel)]="tokenStandardProfiles[standard]">
                    <mat-option [value]="null">— not set (falls back to global) —</mat-option>
                    @for (p of profiles; track p) {
                      <mat-option [value]="p">{{ p }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
              </td>
              <td>
                <button mat-icon-button matTooltip="Save" [disabled]="!canManage" (click)="saveTokenStandardProfile(standard)">
                  <mat-icon>save</mat-icon>
                </button>
                @if (tokenStandardAssignmentId[standard] && !tokenStandardProfiles[standard]) {
                  <button mat-icon-button matTooltip="Remove assignment" [disabled]="!canManage" (click)="deleteTokenStandardAssignment(standard)">
                    <mat-icon>delete_outline</mat-icon>
                  </button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>

    <section class="policy-section">
      <h2>Asset overrides</h2>
      <p class="hint">The most specific level — a per-operation required level for one asset, always audited with a mandatory reason.</p>
      <div class="row">
        <mat-form-field appearance="outline">
          <mat-label>Asset ID</mat-label>
          <input matInput [(ngModel)]="lookupAssetId" placeholder="UUID" />
        </mat-form-field>
        <button mat-stroked-button (click)="loadOverrides()" [disabled]="!lookupAssetId.trim()">
          <mat-icon>search</mat-icon>
          Load overrides
        </button>
        @if (canManage && overridesLoadedForAssetId) {
          <button mat-flat-button color="primary" (click)="openCreateOverrideDialog()">
            <mat-icon>add</mat-icon>
            Add override
          </button>
        }
      </div>

      @if (overridesLoadedForAssetId) {
        @if (overrides.length === 0) {
          <p class="no-overrides">No overrides for this asset — it follows its token-standard/global profile.</p>
        } @else {
          <table class="overrides-table">
            <thead>
              <tr><th>Operation</th><th>Required level</th><th>Reason</th><th>Created</th><th></th></tr>
            </thead>
            <tbody>
              @for (o of overrides; track o.id) {
                <tr>
                  <td class="mono">{{ o.operation }}</td>
                  <td>{{ o.requiredLevel }}</td>
                  <td [matTooltip]="o.reason">{{ o.reason }}</td>
                  <td>{{ o.createdAt | date:'short' }}</td>
                  <td>
                    <button mat-icon-button matTooltip="Delete override" [disabled]="!canManage" (click)="deleteOverride(o)">
                      <mat-icon>delete_outline</mat-icon>
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
    </section>

    <ng-template #createOverrideDialogTpl>
      <h2 mat-dialog-title>Add Asset Override</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:460px">
        <mat-form-field appearance="outline">
          <mat-label>Operation</mat-label>
          <mat-select [(ngModel)]="overrideForm.operation">
            @for (op of operations; track op) {
              <mat-option [value]="op">{{ op }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Required level</mat-label>
          <mat-select [(ngModel)]="overrideForm.requiredLevel">
            @for (l of levels; track l) {
              <mat-option [value]="l">{{ l }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Reason (required for audit trail)</mat-label>
          <textarea matInput rows="3" [(ngModel)]="overrideForm.reason"
            placeholder="Why this asset needs a different required level than its standard/global profile."></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                [disabled]="!overrideForm.operation || !overrideForm.requiredLevel || !overrideForm.reason.trim()"
                (click)="submitCreateOverride()">
          <mat-icon>add</mat-icon>
          Add Override
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .policy-section {
      margin-bottom: 28px;
      padding-bottom: 20px;
      border-bottom: 1px solid var(--rw-border);
    }
    .policy-section h2 {
      font-size: 15px;
      font-weight: 700;
      margin: 0 0 4px;
    }
    .hint {
      font-size: 12px;
      color: var(--rw-text-muted);
      margin: 0 0 12px;
    }
    .row { display: flex; align-items: flex-start; gap: 12px; }
    .compact { width: 220px; }
    table { width: 100%; border-collapse: collapse; margin-top: 8px; }
    th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px;
         color: var(--rw-text-muted); padding: 6px 10px; border-bottom: 1px solid var(--rw-border); }
    td { padding: 6px 10px; font-size: 13px; vertical-align: middle; border-bottom: 1px solid var(--rw-border); }
    .mono { font-family: 'IBM Plex Mono', monospace; font-size: 12px; }
    .no-overrides { font-size: 13px; color: var(--rw-text-muted); margin-top: 12px; }
  `],
})
export class FinalityPolicyComponent implements OnInit {
  @ViewChild('createOverrideDialogTpl') createOverrideDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(FinalityPolicyService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  readonly canManage = this.auth.hasRole('REGISTRY_ADMIN');
  readonly profiles = PROFILES;
  readonly levels = LEVELS;
  readonly tokenStandards = ALL_TOKEN_STANDARDS;

  globalProfile: FinalityPolicyProfile = 'BALANCED';
  private globalAssignmentId: string | null = null;

  tokenStandardProfiles: Partial<Record<TokenStandard, FinalityPolicyProfile | null>> = {};
  tokenStandardAssignmentId: Partial<Record<TokenStandard, string>> = {};

  lookupAssetId = '';
  overridesLoadedForAssetId: string | null = null;
  overrides: FinalityPolicyOverrideView[] = [];
  operations: string[] = [];
  overrideForm = { operation: '', requiredLevel: null as FinalityLevel | null, reason: '' };

  ngOnInit(): void {
    this.service.listAssignments().subscribe({
      next: (assignments) => this.applyAssignments(assignments),
      error: () => this.snackBar.open('Failed to load finality policy assignments.', 'Dismiss', { duration: 5000 }),
    });
    this.service.listOperations().subscribe({ next: (ops) => (this.operations = ops) });
  }

  private applyAssignments(assignments: FinalityPolicyAssignmentView[]): void {
    for (const a of assignments) {
      if (a.scopeType === 'GLOBAL') {
        this.globalProfile = a.profile;
        this.globalAssignmentId = a.id;
      } else if (a.scopeType === 'TOKEN_STANDARD' && a.tokenStandard) {
        this.tokenStandardProfiles[a.tokenStandard] = a.profile;
        this.tokenStandardAssignmentId[a.tokenStandard] = a.id;
      }
    }
    this.cdr.markForCheck();
  }

  private withStepUp(action: string, reason: string, onToken: (token: string) => void): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: false, reason, action },
      width: '500px',
      disableClose: true,
    });
    ref.afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (result?.stepUpToken) onToken(result.stepUpToken);
    });
  }

  saveGlobalProfile(): void {
    this.withStepUp('FINALITY_POLICY_GLOBAL_PROFILE_SET', `Set global finality policy profile to ${this.globalProfile}`,
      (token) => {
        this.service.setGlobalProfile(this.globalProfile, token).subscribe({
          next: (a) => {
            this.globalAssignmentId = a.id;
            this.snackBar.open('Global profile saved.', 'Dismiss', { duration: 3500 });
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to save global profile.', 'Dismiss', { duration: 5000 }),
        });
      });
  }

  saveTokenStandardProfile(standard: TokenStandard): void {
    const profile = this.tokenStandardProfiles[standard];
    if (!profile) {
      this.deleteTokenStandardAssignment(standard);
      return;
    }
    this.withStepUp('FINALITY_POLICY_TOKEN_STANDARD_PROFILE_SET',
      `Set finality policy profile for ${standard} to ${profile}`,
      (token) => {
        this.service.setTokenStandardProfile(standard, profile, token).subscribe({
          next: (a) => {
            this.tokenStandardAssignmentId[standard] = a.id;
            this.snackBar.open(`Profile for ${standard} saved.`, 'Dismiss', { duration: 3500 });
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to save profile.', 'Dismiss', { duration: 5000 }),
        });
      });
  }

  deleteTokenStandardAssignment(standard: TokenStandard): void {
    const id = this.tokenStandardAssignmentId[standard];
    if (!id) return;
    this.withStepUp('FINALITY_POLICY_ASSIGNMENT_DELETED', `Remove finality policy assignment for ${standard}`,
      (token) => {
        this.service.deleteAssignment(id, token).subscribe({
          next: () => {
            delete this.tokenStandardAssignmentId[standard];
            this.snackBar.open(`Assignment for ${standard} removed.`, 'Dismiss', { duration: 3500 });
            this.cdr.markForCheck();
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to remove assignment.', 'Dismiss', { duration: 5000 }),
        });
      });
  }

  loadOverrides(): void {
    const assetId = this.lookupAssetId.trim();
    if (!assetId) return;
    this.service.listOverridesForAsset(assetId).subscribe({
      next: (overrides) => {
        this.overrides = overrides;
        this.overridesLoadedForAssetId = assetId;
        this.cdr.markForCheck();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to load overrides.', 'Dismiss', { duration: 5000 }),
    });
  }

  openCreateOverrideDialog(): void {
    this.overrideForm = { operation: '', requiredLevel: null, reason: '' };
    this.dialog.open(this.createOverrideDialogTpl, { width: '520px' });
  }

  submitCreateOverride(): void {
    if (!this.overridesLoadedForAssetId || !this.overrideForm.operation
        || !this.overrideForm.requiredLevel || !this.overrideForm.reason.trim()) return;
    const assetId = this.overridesLoadedForAssetId;
    const { operation, requiredLevel, reason } = this.overrideForm;
    this.dialog.closeAll();

    this.withStepUp('FINALITY_POLICY_OVERRIDE_SET', `Add finality override for asset ${assetId}: ${operation} → ${requiredLevel}`,
      (token) => {
        this.service.createOverride(assetId, operation, requiredLevel!, reason.trim(), token).subscribe({
          next: () => {
            this.snackBar.open('Override added.', 'Dismiss', { duration: 3500 });
            this.loadOverrides();
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to add override.', 'Dismiss', { duration: 5000 }),
        });
      });
  }

  deleteOverride(o: FinalityPolicyOverrideView): void {
    this.withStepUp('FINALITY_POLICY_OVERRIDE_DELETED', `Remove finality override ${o.operation} for asset ${o.assetId}`,
      (token) => {
        this.service.deleteOverride(o.id, token).subscribe({
          next: () => {
            this.snackBar.open('Override removed.', 'Dismiss', { duration: 3500 });
            this.loadOverrides();
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to remove override.', 'Dismiss', { duration: 5000 }),
        });
      });
  }
}
