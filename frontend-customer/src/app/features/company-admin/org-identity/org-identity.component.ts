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
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { OrgIdentityService } from '../../../core/api/org-identity.service';
import { OrgMemberWalletView, OrgRegistrationView, PermissionGrantView } from '../../../core/models';
import { WalletService } from '../../../core/wallet/wallet.service';

@Component({
  selector: 'app-org-identity',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTabsModule,
    MatTooltipModule,
  ],
  styles: [`
    .org-card { margin-top: 20px; }

    .org-summary {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 16px;
      margin: 8px 0 4px;
    }

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
      margin: 0;
      word-break: break-all;

      &.mono { font-family: 'IBM Plex Mono', monospace; }
    }

    .status-chip {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 600;

      &.ACTIVE    { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
      &.PENDING   { background: var(--rw-pending-bg);  color: var(--rw-pending-fg); }
      &.SUSPENDED { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
      &.FAILED    { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
      &.REMOVED   { background: var(--rw-revoked-bg);  color: var(--rw-revoked-fg); }
    }

    .suspended-banner {
      margin: 12px 0 0;
      padding: 10px 14px;
      border-radius: 8px;
      background: var(--rw-rejected-bg);
      color: var(--rw-rejected-fg);
      font-size: 13px;
    }

    .wallets-card { margin-top: 20px; }

    .roles-cell { font-size: 12px; color: var(--rw-text-secondary); }

    .empty-state {
      text-align: center;
      padding: 40px 16px;
      color: var(--rw-text-secondary);

      mat-icon { font-size: 40px; width: 40px; height: 40px; opacity: 0.4; }
      p { margin: 12px 0 0; font-size: 14px; }
    }

    .error-message { color: var(--rw-text-danger); font-size: 13px; margin: 8px 0 0; }

    .challenge-box {
      background: var(--rw-code-bg);
      color: var(--rw-code-fg);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      padding: 12px;
      border-radius: 8px;
      white-space: pre-wrap;
      word-break: break-all;
    }

    .dialog-hint { font-size: 12px; color: var(--rw-text-muted); margin: 0; }
    .table-wrap { overflow-x: auto; }
    .table-wrap table { min-width: 720px; }
    .dialog-content { display: flex; flex-direction: column; gap: 12px; padding-top: 8px; width: min(480px, calc(100vw - 64px)); }
    .root-error { display: grid; justify-items: center; gap: 10px; }
  `],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Company Admin</h1>
      </div>

      <!-- Sub-tabs -->
      <nav mat-tab-nav-bar [tabPanel]="tabPanel">
        <a mat-tab-link routerLink="/company-admin/users" routerLinkActive #rla1="routerLinkActive" [active]="rla1.isActive">
          <mat-icon>people</mat-icon>&nbsp;Users
        </a>
        <a mat-tab-link routerLink="/company-admin/idp" routerLinkActive #rla2="routerLinkActive" [active]="rla2.isActive">
          <mat-icon>vpn_key</mat-icon>&nbsp;IdP Settings
        </a>
        <a mat-tab-link routerLink="/company-admin/external-ids" routerLinkActive #rla3="routerLinkActive" [active]="rla3.isActive">
          <mat-icon>tag</mat-icon>&nbsp;External IDs
        </a>
        <a mat-tab-link routerLink="/company-admin/org-identity" routerLinkActive #rla4="routerLinkActive" [active]="rla4.isActive">
          <mat-icon>fingerprint</mat-icon>&nbsp;Organization
        </a>
        <a mat-tab-link routerLink="/company-admin/beneficial-owners" routerLinkActive #rlaBo="routerLinkActive" [active]="rlaBo.isActive">
          <mat-icon>diversity_3</mat-icon>&nbsp;Beneficial Owners
        </a>
      </nav>
      <mat-tab-nav-panel #tabPanel></mat-tab-nav-panel>

      @if (loading) {
        <div class="empty-state"><mat-spinner diameter="36" style="margin:0 auto"></mat-spinner></div>
      } @else if (loadError) {
        <div class="empty-state root-error" role="alert">
          <mat-icon>error_outline</mat-icon>
          <span>Onchain organization details could not be loaded.</span>
          <button mat-stroked-button type="button" (click)="loadOrganizations()">Retry</button>
        </div>
      } @else if (orgs.length === 0) {
        <mat-card class="org-card">
          <mat-card-content>
            <div class="empty-state">
              <mat-icon>domain_disabled</mat-icon>
              <p>
                Your company is not yet registered as an onchain organization.<br />
                Please contact the registry operator to get started.
              </p>
            </div>
          </mat-card-content>
        </mat-card>
      } @else {
        <mat-card class="org-card">
          <mat-card-header>
            <mat-icon mat-card-avatar>fingerprint</mat-icon>
            <mat-card-title>Onchain organization</mat-card-title>
            <mat-card-subtitle>
              Your company's ONCHAINID anchors wallets and permissions in the Registerwerk ecosystem
            </mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            @if (orgs.length > 1) {
              <mat-form-field appearance="outline" style="max-width:320px">
                <mat-label>Chain</mat-label>
                <mat-select [(ngModel)]="selectedOrgId" (selectionChange)="onOrgChange()">
                  @for (org of orgs; track org.id) {
                    <mat-option [value]="org.id">{{ org.chainIdentifier ?? org.chainConfigId }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>
            }

            @if (selectedOrg; as org) {
              <div class="org-summary">
                <div>
                  <p class="org-field-label">Status</p>
                  <span class="status-chip {{ org.status }}">{{ org.status }}</span>
                </div>
                <div>
                  <p class="org-field-label">Org ONCHAINID</p>
                  <p class="org-field-value mono">{{ org.orgAddress }}</p>
                </div>
                <div>
                  <p class="org-field-label">Chain</p>
                  <p class="org-field-value">{{ org.chainIdentifier ?? '—' }}</p>
                </div>
                <div>
                  <p class="org-field-label">Registered</p>
                  <p class="org-field-value">{{ org.createdAt | date: 'medium' }}</p>
                </div>
              </div>
              @if (org.status === 'SUSPENDED') {
                <div class="suspended-banner">
                  This organization is suspended{{ org.suspensionReason ? ': ' + org.suspensionReason : '' }}.
                  Wallets keep their bindings but lose active-member status until reinstatement.
                </div>
              }
            }
          </mat-card-content>
        </mat-card>

        @if (selectedOrg; as org) {
          <mat-card class="wallets-card">
            <mat-card-header>
              <mat-icon mat-card-avatar>account_balance_wallet</mat-icon>
              <mat-card-title>Member wallets</mat-card-title>
              <mat-card-subtitle>
                Wallets acting on behalf of your company — bound by signing a one-time challenge
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              @if (walletsError) {
                <p class="error-message" role="alert">
                  {{ walletsError }}
                  <button mat-button type="button" (click)="loadWallets()">Retry</button>
                </p>
              } @else if (wallets.length === 0) {
                <div class="empty-state">
                  <mat-icon>wallet</mat-icon>
                  <p>No wallets bound yet.</p>
                </div>
              } @else {
                <div class="table-wrap"><table mat-table [dataSource]="wallets" style="width:100%">
                  <ng-container matColumnDef="walletAddress">
                    <th mat-header-cell *matHeaderCellDef>Wallet</th>
                    <td mat-cell *matCellDef="let w" style="font-family:'IBM Plex Mono',monospace;font-size:12px">
                      {{ w.walletAddress }}
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="label">
                    <th mat-header-cell *matHeaderCellDef>Label</th>
                    <td mat-cell *matCellDef="let w">{{ w.label ?? '—' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="roles">
                    <th mat-header-cell *matHeaderCellDef>Roles</th>
                    <td mat-cell *matCellDef="let w" class="roles-cell">{{ w.roles.join(', ') || '—' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="status">
                    <th mat-header-cell *matHeaderCellDef>Status</th>
                    <td mat-cell *matCellDef="let w"><span class="status-chip {{ w.status }}">{{ w.status }}</span></td>
                  </ng-container>
                  <ng-container matColumnDef="actions">
                    <th mat-header-cell *matHeaderCellDef></th>
                    <td mat-cell *matCellDef="let w" style="text-align:right">
                      @if (w.status === 'ACTIVE' || w.status === 'PENDING') {
                        <button mat-icon-button color="warn" type="button" [disabled]="walletBusyIds.has(w.id)" (click)="removeWallet(w)"
                                matTooltip="Unbind this wallet">
                          <mat-icon>link_off</mat-icon>
                        </button>
                      }
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="walletColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: walletColumns"></tr>
                </table></div>
              }
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-raised-button color="primary" type="button"
                      [disabled]="org.status !== 'ACTIVE'"
                      (click)="openAddWallet()">
                <mat-icon>add_link</mat-icon>
                Bind wallet
              </button>
            </mat-card-actions>
          </mat-card>

          <mat-card class="wallets-card">
            <mat-card-header>
              <mat-icon mat-card-avatar>verified_user</mat-icon>
              <mat-card-title>Permissions</mat-card-title>
              <mat-card-subtitle>
                Permissions granted to your organization — delegate them to member roles
                or restrict them so only delegated roles may use them
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              @if (grantsError) {
                <p class="error-message" role="alert">
                  {{ grantsError }}
                  <button mat-button type="button" (click)="loadGrants()">Retry</button>
                </p>
              } @else if (orgGrants.length === 0 && roleGrants.length === 0) {
                <div class="empty-state">
                  <mat-icon>verified_user</mat-icon>
                  <p>No permissions granted yet. Permissions are granted by the registry operator.</p>
                </div>
              } @else {
                <div class="table-wrap"><table mat-table [dataSource]="orgGrants" style="width:100%">
                  <ng-container matColumnDef="permissionCode">
                    <th mat-header-cell *matHeaderCellDef>Permission</th>
                    <td mat-cell *matCellDef="let g" style="font-family:'IBM Plex Mono',monospace;font-size:12px">
                      {{ g.permissionCode ?? g.permissionDefinitionId }}
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="status">
                    <th mat-header-cell *matHeaderCellDef>Status</th>
                    <td mat-cell *matCellDef="let g"><span class="status-chip {{ g.status }}">{{ g.status }}</span></td>
                  </ng-container>
                  <ng-container matColumnDef="delegations">
                    <th mat-header-cell *matHeaderCellDef>Delegated to roles</th>
                    <td mat-cell *matCellDef="let g" class="roles-cell">
                      {{ delegationsFor(g).join(', ') || '—' }}
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="restricted">
                    <th mat-header-cell *matHeaderCellDef>Role-restricted</th>
                    <td mat-cell *matCellDef="let g">
                      <mat-slide-toggle
                        [checked]="g.roleRestricted"
                        [disabled]="g.status !== 'ACTIVE' || restrictionBusy"
                        (change)="toggleRestriction(g, $event.checked)">
                      </mat-slide-toggle>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="actions">
                    <th mat-header-cell *matHeaderCellDef></th>
                    <td mat-cell *matCellDef="let g" style="text-align:right">
                      @if (g.status === 'ACTIVE') {
                        <button mat-icon-button color="primary" type="button" (click)="openDelegateDialog(g)"
                                matTooltip="Delegate to a member role">
                          <mat-icon>group_add</mat-icon>
                        </button>
                      }
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="grantColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: grantColumns"></tr>
                </table></div>

                @if (roleGrants.length > 0) {
                  <p class="org-field-label" style="margin-top:20px">Role delegations</p>
                  <div class="table-wrap"><table mat-table [dataSource]="roleGrants" style="width:100%">
                    <ng-container matColumnDef="roleCode">
                      <th mat-header-cell *matHeaderCellDef>Role</th>
                      <td mat-cell *matCellDef="let g">{{ g.roleCode }}</td>
                    </ng-container>
                    <ng-container matColumnDef="permissionCode">
                      <th mat-header-cell *matHeaderCellDef>Permission</th>
                      <td mat-cell *matCellDef="let g" style="font-family:'IBM Plex Mono',monospace;font-size:12px">
                        {{ g.permissionCode ?? g.permissionDefinitionId }}
                      </td>
                    </ng-container>
                    <ng-container matColumnDef="status">
                      <th mat-header-cell *matHeaderCellDef>Status</th>
                      <td mat-cell *matCellDef="let g"><span class="status-chip {{ g.status }}">{{ g.status }}</span></td>
                    </ng-container>
                    <ng-container matColumnDef="actions">
                      <th mat-header-cell *matHeaderCellDef></th>
                      <td mat-cell *matCellDef="let g" style="text-align:right">
                        @if (g.status === 'ACTIVE' || g.status === 'PENDING') {
                          <button mat-icon-button color="warn" type="button" [disabled]="grantBusyIds.has(g.id)" (click)="revokeDelegation(g)"
                                  matTooltip="Revoke this delegation">
                            <mat-icon>group_remove</mat-icon>
                          </button>
                        }
                      </td>
                    </ng-container>
                    <tr mat-header-row *matHeaderRowDef="roleGrantColumns"></tr>
                    <tr mat-row *matRowDef="let row; columns: roleGrantColumns"></tr>
                  </table></div>
                }
              }
            </mat-card-content>
          </mat-card>
        }
      }
    </div>

    <!-- Delegate permission to role dialog -->
    <ng-template #delegateDialog>
      <h2 mat-dialog-title>Delegate permission to role</h2>
      <mat-dialog-content class="dialog-content">
        <p class="dialog-hint">
          Member wallets holding this role may use
          <strong style="font-family:'IBM Plex Mono',monospace">{{ delegateGrant?.permissionCode }}</strong>
          when the permission is role-restricted.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Role code</mat-label>
          <input matInput [(ngModel)]="delegateRole" placeholder="e.g. TRADER" />
        </mat-form-field>
        @if (dialogError) {
          <p class="error-message" role="alert">{{ dialogError }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" type="button"
                [disabled]="!delegateRole.trim() || delegating"
                (click)="submitDelegation()">
          <mat-icon>group_add</mat-icon>
          Delegate
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Bind wallet dialog -->
    <ng-template #addWalletDialog>
      <h2 mat-dialog-title>Bind wallet</h2>
      <mat-dialog-content class="dialog-content">
        <mat-form-field appearance="outline">
          <mat-label>Wallet address</mat-label>
          <input matInput [(ngModel)]="newWalletAddress" [disabled]="!!challengeMessage"
                 placeholder="0x…" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Label (optional)</mat-label>
          <input matInput [(ngModel)]="newWalletLabel" placeholder="e.g. Treasury desk laptop" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Roles (comma-separated, optional)</mat-label>
          <input matInput [(ngModel)]="newWalletRoles" placeholder="e.g. TRADER, SIGNER" />
        </mat-form-field>

        @if (!challengeMessage) {
          <p class="dialog-hint">
            Step 1 — request a one-time challenge for this wallet. You will then sign it to
            prove control of the key.
          </p>
        } @else {
          <p class="dialog-hint">Step 2 — sign this exact message with the wallet:</p>
          <div class="challenge-box">{{ challengeMessage }}</div>
          @if (hasBrowserWallet) {
            <button mat-stroked-button color="primary" type="button" (click)="signWithBrowserWallet()" [disabled]="binding">
              <mat-icon>account_balance_wallet</mat-icon>
              Sign with browser wallet
            </button>
          }
          <mat-form-field appearance="outline">
            <mat-label>Signature (0x…, paste if signing externally)</mat-label>
            <textarea matInput rows="2" [(ngModel)]="signature"></textarea>
          </mat-form-field>
        }

        @if (dialogError) {
          <p class="error-message" role="alert">{{ dialogError }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" mat-dialog-close>Cancel</button>
        @if (!challengeMessage) {
          <button mat-raised-button color="primary" type="button"
                  [disabled]="!isValidAddress(newWalletAddress) || requestingChallenge"
                  (click)="requestChallenge()">
            Request challenge
          </button>
        } @else {
          <button mat-raised-button color="primary" type="button"
                  [disabled]="!signature.trim() || binding"
                  (click)="bind()">
            <mat-icon>add_link</mat-icon>
            Bind wallet
          </button>
        }
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class OrgIdentityComponent implements OnInit {
  @ViewChild('addWalletDialog') addWalletDialogTpl!: TemplateRef<unknown>;
  @ViewChild('delegateDialog') delegateDialogTpl!: TemplateRef<unknown>;

  private readonly orgIdentityService = inject(OrgIdentityService);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly wallet = inject(WalletService);

  loading = true;
  loadError = false;
  orgs: OrgRegistrationView[] = [];
  selectedOrgId: string | null = null;

  wallets: OrgMemberWalletView[] = [];
  walletsError = '';
  readonly walletColumns = ['walletAddress', 'label', 'roles', 'status', 'actions'];

  orgGrants: PermissionGrantView[] = [];
  roleGrants: PermissionGrantView[] = [];
  grantsError = '';
  restrictionBusy = false;
  readonly grantColumns = ['permissionCode', 'status', 'delegations', 'restricted', 'actions'];
  readonly roleGrantColumns = ['roleCode', 'permissionCode', 'status', 'actions'];

  delegateGrant: PermissionGrantView | null = null;
  delegateRole = '';
  delegating = false;

  newWalletAddress = '';
  newWalletLabel = '';
  newWalletRoles = '';
  challengeMessage = '';
  signature = '';
  dialogError = '';
  requestingChallenge = false;
  binding = false;
  readonly walletBusyIds = new Set<string>();
  readonly grantBusyIds = new Set<string>();

  get selectedOrg(): OrgRegistrationView | null {
    return this.orgs.find((org) => org.id === this.selectedOrgId) ?? null;
  }

  get hasBrowserWallet(): boolean {
    return this.wallet.isAvailable;
  }

  ngOnInit(): void {
    this.loadOrganizations();
  }

  loadOrganizations(): void {
    this.loading = true;
    this.loadError = false;
    this.orgIdentityService.myOrgs().subscribe({
      next: (orgs) => {
        this.orgs = orgs;
        this.selectedOrgId = orgs[0]?.id ?? null;
        this.loading = false;
        this.cdr.markForCheck();
        if (this.selectedOrg) {
          this.loadWallets();
          this.loadGrants();
        }
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  onOrgChange(): void {
    this.loadWallets();
    this.loadGrants();
  }

  loadWallets(): void {
    const org = this.selectedOrg;
    if (!org) return;
    this.walletsError = '';

    this.orgIdentityService.myWallets(org.chainConfigId).subscribe({
      next: (wallets) => {
        this.wallets = wallets;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.walletsError = err?.error?.message ?? 'Failed to load wallets.';
        this.cdr.markForCheck();
      },
    });
  }

  isValidAddress(address: string): boolean {
    return /^0x[0-9a-fA-F]{40}$/.test(address.trim());
  }

  openAddWallet(): void {
    this.newWalletAddress = '';
    this.newWalletLabel = '';
    this.newWalletRoles = '';
    this.challengeMessage = '';
    this.signature = '';
    this.dialogError = '';
    this.dialog.open(this.addWalletDialogTpl, { width: '560px' });
  }

  requestChallenge(): void {
    const org = this.selectedOrg;
    if (!org) return;
    this.requestingChallenge = true;
    this.dialogError = '';

    this.orgIdentityService.createChallenge(org.chainConfigId, this.newWalletAddress.trim()).subscribe({
      next: (challenge) => {
        this.challengeMessage = challenge.message;
        this.requestingChallenge = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.dialogError = err?.error?.message ?? 'Failed to create challenge.';
        this.requestingChallenge = false;
        this.cdr.markForCheck();
      },
    });
  }

  async signWithBrowserWallet(): Promise<void> {
    this.dialogError = '';
    try {
      await this.wallet.connect();
      const connected = this.wallet.address();
      const target = this.newWalletAddress.trim().toLowerCase();
      if (!connected || connected.toLowerCase() !== target) {
        this.dialogError = `Browser wallet has no account ${target}. Switch accounts and retry.`;
        this.cdr.markForCheck();
        return;
      }
      this.signature = await this.wallet.signMessage(this.challengeMessage);
      this.cdr.markForCheck();
      this.bind();
    } catch (err: unknown) {
      this.dialogError = err instanceof Error ? err.message : 'Browser wallet signing failed.';
      this.cdr.markForCheck();
    }
  }

  bind(): void {
    const org = this.selectedOrg;
    if (!org) return;
    this.binding = true;
    this.dialogError = '';

    const roles = this.newWalletRoles
      .split(',')
      .map((role) => role.trim().toUpperCase())
      .filter((role) => role.length > 0);

    this.orgIdentityService
      .bindWallet({
        chainConfigId: org.chainConfigId,
        walletAddress: this.newWalletAddress.trim(),
        signature: this.signature.trim(),
        roles: [...new Set(roles)],
        label: this.newWalletLabel.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.binding = false;
          this.dialog.closeAll();
          this.loadWallets();
        },
        error: (err) => {
          this.binding = false;
          this.dialogError = err?.error?.message ?? 'Failed to bind wallet.';
          this.cdr.markForCheck();
        },
      });
  }

  removeWallet(wallet: OrgMemberWalletView): void {
    if (this.walletBusyIds.has(wallet.id)) return;
    if (!confirm(`Unbind wallet ${wallet.walletAddress}?`)) return;
    this.walletBusyIds.add(wallet.id);
    this.orgIdentityService.removeWallet(wallet.id).subscribe({
      next: () => {
        this.walletBusyIds.delete(wallet.id);
        this.loadWallets();
      },
      error: (err) => {
        this.walletBusyIds.delete(wallet.id);
        this.walletsError = err?.error?.message ?? 'Failed to unbind wallet.';
        this.cdr.markForCheck();
      },
    });
  }

  // ── Permissions & role delegation ─────────────────────────────────────────

  loadGrants(): void {
    const org = this.selectedOrg;
    if (!org) return;
    this.grantsError = '';

    this.orgIdentityService.myGrants(org.chainConfigId).subscribe({
      next: (grants) => {
        this.orgGrants = grants.filter((g) => g.grantType === 'ORG' && g.status !== 'REVOKED');
        this.roleGrants = grants.filter((g) => g.grantType === 'ROLE' && g.status !== 'REVOKED');
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.grantsError = err?.error?.message ?? 'Failed to load permissions.';
        this.cdr.markForCheck();
      },
    });
  }

  delegationsFor(orgGrant: PermissionGrantView): string[] {
    return this.roleGrants
      .filter((g) => g.permissionDefinitionId === orgGrant.permissionDefinitionId && g.status === 'ACTIVE')
      .map((g) => g.roleCode!)
      .filter((role) => role != null);
  }

  openDelegateDialog(grant: PermissionGrantView): void {
    this.delegateGrant = grant;
    this.delegateRole = '';
    this.dialogError = '';
    this.dialog.open(this.delegateDialogTpl, { width: '500px' });
  }

  submitDelegation(): void {
    const org = this.selectedOrg;
    const grant = this.delegateGrant;
    if (!org || !grant) return;
    this.delegating = true;
    this.dialogError = '';

    this.orgIdentityService
      .grantToRole({
        chainConfigId: org.chainConfigId,
        roleCode: this.delegateRole.trim().toUpperCase(),
        permissionDefinitionId: grant.permissionDefinitionId,
      })
      .subscribe({
        next: () => {
          this.delegating = false;
          this.dialog.closeAll();
          this.loadGrants();
        },
        error: (err) => {
          this.delegating = false;
          this.dialogError = err?.error?.message ?? 'Failed to delegate permission.';
          this.cdr.markForCheck();
        },
      });
  }

  revokeDelegation(grant: PermissionGrantView): void {
    if (this.grantBusyIds.has(grant.id)) return;
    if (!confirm(`Revoke the ${grant.roleCode ?? 'role'} delegation?`)) return;
    this.grantBusyIds.add(grant.id);
    this.orgIdentityService.revokeRoleGrant(grant.id).subscribe({
      next: () => {
        this.grantBusyIds.delete(grant.id);
        this.loadGrants();
      },
      error: (err) => {
        this.grantBusyIds.delete(grant.id);
        this.grantsError = err?.error?.message ?? 'Failed to revoke delegation.';
        this.cdr.markForCheck();
      },
    });
  }

  toggleRestriction(grant: PermissionGrantView, restricted: boolean): void {
    const org = this.selectedOrg;
    if (!org) return;
    this.restrictionBusy = true;

    this.orgIdentityService
      .setRoleRestriction({
        chainConfigId: org.chainConfigId,
        permissionDefinitionId: grant.permissionDefinitionId,
        restricted,
      })
      .subscribe({
        next: () => {
          this.restrictionBusy = false;
          this.loadGrants();
        },
        error: (err) => {
          this.restrictionBusy = false;
          this.grantsError = err?.error?.message ?? 'Failed to update role restriction.';
          this.loadGrants();
        },
      });
  }
}
