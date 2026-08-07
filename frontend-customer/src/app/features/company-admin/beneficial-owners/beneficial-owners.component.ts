import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BeneficialOwnerService } from '../../../core/api/beneficial-owner.service';
import { AuthService } from '../../../core/auth/auth.service';
import { BeneficialOwner } from '../../../core/models';

/**
 * Read-only view of the entity's own beneficial-owner (UBO) register — GwG §3 / AMLR Art. 42.
 * Wraps `kyc.web.BeneficialOwnerController` GET, which previously had no customer frontend
 * caller: the nightly sanctions/PEP screening of these records was invisible to the entity
 * they applied to.
 */
@Component({
  selector: 'app-beneficial-owners',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    RouterLinkActive,
    MatCardModule,
    MatIconModule,
    MatTableModule,
    MatProgressSpinnerModule,
    MatTabsModule,
    MatTooltipModule,
  ],
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
        <a mat-tab-link routerLink="/company-admin/beneficial-owners" routerLinkActive #rla5="routerLinkActive" [active]="rla5.isActive">
          <mat-icon>diversity_3</mat-icon>&nbsp;Beneficial Owners
        </a>
      </nav>
      <mat-tab-nav-panel #tabPanel></mat-tab-nav-panel>

      <p class="info-text">
        Beneficial owners registered against this entity (GwG §3, AMLR Art. 42). Registering or
        ceasing an owner is done by Registerwerk operators/compliance staff — contact support to
        request a change. Each owner is automatically re-screened for sanctions/PEP status.
      </p>

      @if (loading) {
        <div class="empty-state"><mat-spinner diameter="36" style="margin:0 auto"></mat-spinner></div>
      } @else if (owners.length === 0) {
        <mat-card class="bo-card">
          <mat-card-content>
            <p class="dimmed" style="text-align:center;padding:16px">No beneficial owners registered.</p>
          </mat-card-content>
        </mat-card>
      } @else {
        <mat-card class="bo-card">
          <mat-card-content>
            <table mat-table [dataSource]="owners" style="width:100%">
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef>Name</th>
                <td mat-cell *matCellDef="let bo">{{ bo.givenName }} {{ bo.familyName }}</td>
              </ng-container>
              <ng-container matColumnDef="country">
                <th mat-header-cell *matHeaderCellDef>Country</th>
                <td mat-cell *matCellDef="let bo">{{ bo.country ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="ownershipPct">
                <th mat-header-cell *matHeaderCellDef>Ownership</th>
                <td mat-cell *matCellDef="let bo">{{ bo.ownershipPct !== null ? (bo.ownershipPct + '%') : '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="controlType">
                <th mat-header-cell *matHeaderCellDef>Control Type</th>
                <td mat-cell *matCellDef="let bo">{{ bo.controlType.replace('_',' ') }}</td>
              </ng-container>
              <ng-container matColumnDef="registeredAt">
                <th mat-header-cell *matHeaderCellDef>Registered</th>
                <td mat-cell *matCellDef="let bo">{{ bo.registeredAt | date:'mediumDate' }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="columns"></tr>
              <tr mat-row *matRowDef="let row; columns: columns;"></tr>
            </table>
          </mat-card-content>
        </mat-card>
      }
    </div>
  `,
  styles: [`
    .bo-card { margin-top: 16px; }
    .info-text { font-size: 14px; color: var(--rw-text-secondary); margin: 16px 0; }
    .dimmed { color: var(--rw-text-secondary); }
    .empty-state { padding: 40px 0; }
  `],
})
export class BeneficialOwnersComponent implements OnInit {
  private readonly beneficialOwnerService = inject(BeneficialOwnerService);
  private readonly authService = inject(AuthService);
  private readonly cdr = inject(ChangeDetectorRef);

  owners: BeneficialOwner[] = [];
  loading = true;
  readonly columns = ['name', 'country', 'ownershipPct', 'controlType', 'registeredAt'];

  ngOnInit(): void {
    const entityId = this.authService.getEntityId();
    if (!entityId) {
      this.loading = false;
      return;
    }
    this.beneficialOwnerService.list(entityId).subscribe({
      next: (owners) => {
        this.owners = owners;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.loading = false;
        this.cdr.detectChanges();
      },
    });
  }
}
