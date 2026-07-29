import { CommonModule, DatePipe } from '@angular/common';
import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { CompanyService } from '../../../core/api/company.service';
import {
  CompanyExternalReferenceRecord,
  ExternalReferenceSubjectType,
  LegalEntity,
} from '../../../core/models';

@Component({
  selector: 'app-external-id-admin',
  standalone: true,
  imports: [
    CommonModule,
    DatePipe,
    FormsModule,
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTableModule,
    MatTabsModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>External IDs</h1>
          <p class="subtitle">Map Registerwerk objects to the identifiers used in your internal systems.</p>
        </div>
      </div>

      <nav mat-tab-nav-bar [tabPanel]="tabPanel">
        <a mat-tab-link routerLink="/company-admin/users" routerLinkActive #usersRla="routerLinkActive" [active]="usersRla.isActive">
          <mat-icon>people</mat-icon>&nbsp;Users
        </a>
        <a mat-tab-link routerLink="/company-admin/idp" routerLinkActive #idpRla="routerLinkActive" [active]="idpRla.isActive">
          <mat-icon>vpn_key</mat-icon>&nbsp;IdP Settings
        </a>
        <a mat-tab-link routerLink="/company-admin/external-ids" routerLinkActive #extRla="routerLinkActive" [active]="extRla.isActive">
          <mat-icon>tag</mat-icon>&nbsp;External IDs
        </a>
        <a mat-tab-link routerLink="/company-admin/org-identity" routerLinkActive #orgRla="routerLinkActive" [active]="orgRla.isActive">
          <mat-icon>fingerprint</mat-icon>&nbsp;Organization
        </a>
      </nav>
      <mat-tab-nav-panel #tabPanel></mat-tab-nav-panel>

      <mat-card class="filter-card">
        <mat-card-header>
          <mat-card-title>Lookup and assign</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="filter-grid">
            <mat-form-field appearance="outline">
              <mat-label>Object type</mat-label>
              <mat-select [(ngModel)]="selectedSubjectType">
                <mat-option [value]="null">All object types</mat-option>
                @for (option of subjectTypeOptions; track option.value) {
                  <mat-option [value]="option.value">{{ option.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>External ID</mat-label>
              <input matInput [(ngModel)]="lookupExternalId" placeholder="e.g. ERP-12345" />
            </mat-form-field>

            <div class="action-row">
              <button mat-raised-button color="primary" [disabled]="loading || !lookupExternalId.trim()" (click)="lookup()">
                <mat-icon>search</mat-icon>
                Lookup
              </button>
              <button mat-stroked-button [disabled]="loading" (click)="loadAll()">
                <mat-icon>list</mat-icon>
                Show all
              </button>
            </div>
          </div>

          <div class="assign-grid">
            <mat-form-field appearance="outline">
              <mat-label>Assign type</mat-label>
              <mat-select [(ngModel)]="assignSubjectType">
                @for (option of subjectTypeOptions; track option.value) {
                  <mat-option [value]="option.value">{{ option.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Registerwerk object ID</mat-label>
              <input matInput [(ngModel)]="assignSubjectId" placeholder="UUID" />
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>External ID</mat-label>
              <input matInput [(ngModel)]="assignExternalId" placeholder="Your system ID" />
            </mat-form-field>

            <button
              mat-raised-button
              color="accent"
              class="assign-button"
              [disabled]="savingAssignment || !assignSubjectType || !assignSubjectId.trim() || !assignExternalId.trim()"
              (click)="saveAssignment()"
            >
              @if (savingAssignment) {
                <mat-spinner diameter="18"></mat-spinner>
              } @else {
                <ng-container>
                  <mat-icon>save</mat-icon>
                  Save mapping
                </ng-container>
              }
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header>
          <mat-card-title>
            {{ lookupMode ? 'Lookup results' : 'Assigned external IDs' }} ({{ records.length }})
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          @if (loading) {
            <div class="loading-overlay"><mat-spinner diameter="40"></mat-spinner></div>
          } @else {
            <table mat-table [dataSource]="records" class="mat-elevation-z0 full-width-table">
              <ng-container matColumnDef="externalId">
                <th mat-header-cell *matHeaderCellDef>External ID</th>
                <td mat-cell *matCellDef="let row">
                  <span class="external-id-chip">{{ row.externalId }}</span>
                </td>
              </ng-container>

              <ng-container matColumnDef="subjectType">
                <th mat-header-cell *matHeaderCellDef>Object type</th>
                <td mat-cell *matCellDef="let row">{{ subjectTypeLabel(row.subjectType) }}</td>
              </ng-container>

              <ng-container matColumnDef="displayName">
                <th mat-header-cell *matHeaderCellDef>Object</th>
                <td mat-cell *matCellDef="let row">
                  <div class="object-cell">
                    <span class="object-name">{{ row.displayName }}</span>
                    <span class="object-meta">{{ row.subjectId }}</span>
                  </div>
                </td>
              </ng-container>

              <ng-container matColumnDef="contextLabel">
                <th mat-header-cell *matHeaderCellDef>Context</th>
                <td mat-cell *matCellDef="let row">{{ row.contextLabel || '—' }}</td>
              </ng-container>

              <ng-container matColumnDef="updatedAt">
                <th mat-header-cell *matHeaderCellDef>Updated</th>
                <td mat-cell *matCellDef="let row">{{ row.updatedAt | date:'medium' }}</td>
              </ng-container>

              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let row">
                  @let link = linkFor(row);
                  @if (link) {
                    <a mat-button [routerLink]="link">
                      <mat-icon>open_in_new</mat-icon>
                      Open
                    </a>
                  }
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

              <tr class="mat-row" *matNoDataRow>
                <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                  No external IDs found for the current selection.
                </td>
              </tr>
            </table>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .subtitle {
      margin: 6px 0 0;
      color: var(--rw-text-secondary);
      font-size: 14px;
    }

    .filter-card {
      margin: 16px 0;
    }

    .filter-grid,
    .assign-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
      align-items: start;
    }

    .assign-grid {
      margin-top: 16px;
    }

    .action-row {
      display: flex;
      gap: 8px;
      align-items: center;
      min-height: 56px;
    }

    .assign-button {
      align-self: stretch;
      min-height: 56px;
    }

    .external-id-chip {
      display: inline-flex;
      align-items: center;
      padding: 6px 10px;
      border-radius: 999px;
      background: rgba(13, 148, 136, 0.1);
      color: var(--rw-accent);
      font-weight: 700;
      font-size: 12px;
    }

    .object-cell {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .object-name {
      color: var(--rw-text-primary);
      font-weight: 600;
    }

    .object-meta {
      color: var(--rw-text-muted);
      font-size: 11px;
      font-family: 'IBM Plex Mono', 'Courier New', monospace;
    }

    .empty-row {
      text-align: center;
      padding: 32px;
      color: var(--rw-text-muted);
    }
  `],
})
export class ExternalIdAdminComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly companyService = inject(CompanyService);
  private readonly snackBar = inject(MatSnackBar);

  readonly displayedColumns = ['externalId', 'subjectType', 'displayName', 'contextLabel', 'updatedAt', 'actions'];
  readonly subjectTypeOptions: Array<{ value: ExternalReferenceSubjectType; label: string }> = [
    { value: 'ASSET', label: 'Assets / issuances' },
    { value: 'LEGAL_ENTITY', label: 'Legal entities' },
    { value: 'ASSET_HOLDER', label: 'Asset holders / investments' },
    { value: 'ERC3643_IDENTITY_REGISTRY_ENTRY', label: 'ERC-3643 identity entries' },
  ];

  entity: LegalEntity | null = null;
  records: CompanyExternalReferenceRecord[] = [];
  selectedSubjectType: ExternalReferenceSubjectType | null = null;
  lookupExternalId = '';
  lookupMode = false;
  loading = true;

  assignSubjectType: ExternalReferenceSubjectType = 'ASSET';
  assignSubjectId = '';
  assignExternalId = '';
  savingAssignment = false;

  ngOnInit(): void {
    this.companyService.getMyEntity().subscribe({
      next: (entity) => {
        this.entity = entity;
        this.cdr.detectChanges();
      },
    });
    this.loadAll();
  }

  loadAll(): void {
    this.loading = true;
    this.lookupMode = false;
    this.companyService.listExternalIds(this.selectedSubjectType ?? undefined).subscribe({
      next: (records) => {
        this.records = records;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.records = [];
        this.loading = false;
        this.cdr.detectChanges();
      },
    });
  }

  lookup(): void {
    const externalId = this.lookupExternalId.trim();
    if (!externalId) {
      return;
    }
    this.loading = true;
    this.lookupMode = true;
    this.companyService.lookupExternalIds(externalId, this.selectedSubjectType ?? undefined).subscribe({
      next: (records) => {
        this.records = records;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.records = [];
        this.loading = false;
        this.cdr.detectChanges();
      },
    });
  }

  saveAssignment(): void {
    this.savingAssignment = true;
    this.companyService
      .saveExternalId(this.assignSubjectType, this.assignSubjectId.trim(), this.assignExternalId.trim())
      .subscribe({
        next: () => {
          this.savingAssignment = false;
          this.assignSubjectId = '';
          this.assignExternalId = '';
          this.snackBar.open('External ID saved.', 'OK', { duration: 3000 });
          this.loadAll();
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.savingAssignment = false;
          this.snackBar.open(err?.error?.message ?? 'Failed to save external ID.', 'Dismiss', { duration: 4000 });
          this.cdr.detectChanges();
        },
      });
  }

  subjectTypeLabel(subjectType: ExternalReferenceSubjectType): string {
    return this.subjectTypeOptions.find(option => option.value === subjectType)?.label ?? subjectType;
  }

  linkFor(record: CompanyExternalReferenceRecord): string[] | null {
    switch (record.subjectType) {
      case 'ASSET':
        return ['/issuances', record.subjectId];
      case 'ASSET_HOLDER':
        return ['/investments', record.subjectId];
      case 'ERC3643_IDENTITY_REGISTRY_ENTRY':
        return record.relatedAssetId ? ['/issuances', record.relatedAssetId] : null;
      case 'LEGAL_ENTITY':
        return this.entity?.id === record.subjectId ? ['/company-admin', 'users'] : null;
      default:
        return null;
    }
  }
}
