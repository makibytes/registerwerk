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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { PermissionService } from '../../../core/api/permission.service';
import { OrgIdentityService } from '../../../core/api/org-identity.service';
import { OrgRegistrationView, PermissionDefinitionView, PermissionGrantView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';

@Component({
  selector: 'app-permission-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [`
    .definition-card {
      padding: 18px;
      margin-bottom: 20px;
      background: var(--rw-surface);
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius-md);
      box-shadow: var(--rw-shadow-xs);

      .definition-code {
        font-family: 'IBM Plex Mono', monospace;
        font-size: 14px;
        margin: 0 0 6px;
      }

      .definition-hash {
        font-family: 'IBM Plex Mono', monospace;
        font-size: 12px;
        color: var(--rw-text-muted);
        margin: 0 0 8px;
        word-break: break-all;
      }

      .definition-description {
        font-size: 13px;
        color: var(--rw-text-secondary);
        margin: 0;
      }
    }
  `],
  template: `
    <app-page-header
      [title]="definition?.name ?? 'Permission'"
      subtitle="Organizations granted this permission — enforcement composed by the PermissionOracle">
      <button mat-stroked-button routerLink="/permissions">
        <mat-icon>arrow_back</mat-icon>
        All permissions
      </button>
      <button mat-raised-button color="primary" (click)="openGrantDialog()">
        <mat-icon>add_task</mat-icon>
        Grant to organization
      </button>
    </app-page-header>

    @if (definition; as def) {
      <div class="definition-card">
        <p class="definition-code">{{ def.code }}</p>
        <p class="definition-hash">{{ def.permissionHash }}</p>
        @if (def.description) {
          <p class="definition-description">{{ def.description }}</p>
        }
      </div>
    }

    <rw-data-table
      [columns]="grantColumns"
      [rows]="grants"
      [state]="grantState"
      (retry)="loadGrants()"
      filterPlaceholder="Filter by entity, role…"
      emptyMessage="No organizations hold this permission yet."
      [actionsTemplate]="grantActions">
    </rw-data-table>

    <ng-template #grantActions let-grant>
      @if (grant.status === 'ACTIVE' || grant.status === 'PENDING') {
        <button mat-icon-button color="warn" (click)="revoke(grant)"
                matTooltip="Revoke (step-up)">
          <mat-icon>remove_done</mat-icon>
        </button>
      }
    </ng-template>

    <!-- Grant to org dialog -->
    <ng-template #grantDialog>
      <h2 mat-dialog-title>Grant to organization</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Grants <strong>{{ definition?.code }}</strong> org-wide; company admins can then
          delegate it to member roles. Requires step-up.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Organization</mat-label>
          <mat-select [(ngModel)]="selectedOrgId">
            @for (org of orgs; track org.id) {
              <mat-option [value]="org.id">
                {{ org.entityName ?? org.legalEntityId }} — {{ org.chainIdentifier }}
              </mat-option>
            }
          </mat-select>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!selectedOrgId" (click)="submitGrant()">
          <mat-icon>add_task</mat-icon>
          Grant
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class PermissionDetailComponent implements OnInit {
  @ViewChild('grantDialog') grantDialogTpl!: TemplateRef<unknown>;

  private readonly permissionService = inject(PermissionService);
  private readonly orgIdentityService = inject(OrgIdentityService);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  definition: PermissionDefinitionView | null = null;
  grants: PermissionGrantView[] = [];
  grantState: AsyncSectionStatus = 'pending';

  orgs: OrgRegistrationView[] = [];
  selectedOrgId: string | null = null;

  readonly grantColumns: TableColumn[] = [
    { key: 'entityName', header: 'Organization', cell: (r: PermissionGrantView) => r.entityName ?? r.orgRegistrationId },
    { key: 'grantType', header: 'Type', cell: (r: PermissionGrantView) => r.grantType, type: 'badge' },
    { key: 'roleCode', header: 'Role', cell: (r: PermissionGrantView) => r.roleCode ?? '—' },
    {
      key: 'roleRestricted',
      header: 'Role-restricted',
      cell: (r: PermissionGrantView) => (r.grantType === 'ORG' ? (r.roleRestricted ? 'Yes' : 'No') : '—'),
    },
    { key: 'status', header: 'Status', cell: (r: PermissionGrantView) => r.status, type: 'badge' },
    { key: 'createdAt', header: 'Granted', cell: (r: PermissionGrantView) => r.createdAt, type: 'date' },
  ];

  private get definitionId(): string {
    return this.route.snapshot.paramMap.get('id')!;
  }

  ngOnInit(): void {
    this.permissionService.list().subscribe({
      next: (permissions) => {
        this.definition = permissions.find((p) => p.id === this.definitionId) ?? null;
        this.cdr.markForCheck();
      },
    });
    this.loadGrants();
  }

  loadGrants(): void {
    this.grantState = 'pending';
    this.cdr.markForCheck();
    this.permissionService.grants(this.definitionId).subscribe({
      next: (grants) => {
        this.grants = grants;
        this.grantState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.grantState = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openGrantDialog(): void {
    this.selectedOrgId = null;
    this.orgIdentityService.list({ status: 'ACTIVE', size: 200 }).subscribe({
      next: (page) => {
        this.orgs = page.content;
        this.cdr.markForCheck();
      },
    });
    this.dialog.open(this.grantDialogTpl, { width: '500px' });
  }

  submitGrant(): void {
    const orgId = this.selectedOrgId!;
    this.dialog.closeAll();

    this.withStepUp('Permission grant to organization', (token, dualControlToken) =>
      this.permissionService.grantToOrg(this.definitionId, orgId, token, dualControlToken).subscribe({
        next: () => {
          this.snackBar.open('Grant submitted.', 'Dismiss', { duration: 5000 });
          this.loadGrants();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to grant permission.', 'Dismiss', {
            duration: 6000,
          });
        },
      }),
    );
  }

  revoke(grant: PermissionGrantView): void {
    this.withStepUp('Permission grant revocation', (token, dualControlToken) =>
      this.permissionService.revokeGrant(grant.id, token, dualControlToken).subscribe({
        next: () => {
          this.snackBar.open('Revocation submitted.', 'Dismiss', { duration: 5000 });
          this.loadGrants();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to revoke grant.', 'Dismiss', {
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
