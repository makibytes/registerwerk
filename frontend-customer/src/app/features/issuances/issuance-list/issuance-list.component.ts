import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { IssuanceService } from '../../../core/api/issuance.service';
import { Asset, AssetStatus } from '../../../core/models';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { ChainNamePipe } from '../../../shared/pipes/chain-name.pipe';

@Component({
  selector: 'app-issuance-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    FormsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatChipsModule,
    MatTooltipModule,
    MatPaginatorModule,
    StatusBadgeComponent,
    ChainNamePipe,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>My Issuances</h1>
        <a mat-raised-button color="primary" routerLink="/issuances/new">
          <mat-icon>add</mat-icon>
          New Issuance
        </a>
      </div>

      <!-- Filters -->
      <mat-card class="filter-card">
        <mat-card-content>
          <mat-form-field appearance="outline" style="width:200px">
            <mat-label>Filter by Status</mat-label>
            <mat-select [(ngModel)]="filterStatus" (ngModelChange)="applyFilter()">
              <mat-option [value]="null">All Statuses</mat-option>
              @for (s of statusOptions; track s.value) {
                <mat-option [value]="s.value">{{ s.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </mat-card-content>
      </mat-card>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else {
        <mat-card>
          <table mat-table [dataSource]="issuances" class="mat-elevation-z0">

            <!-- Asset Number -->
            <ng-container matColumnDef="assetNumber">
              <th mat-header-cell *matHeaderCellDef>Number</th>
              <td mat-cell *matCellDef="let row">
                <code>{{ row.assetNumber }}</code>
              </td>
            </ng-container>

            <!-- Name -->
            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef>Name</th>
              <td mat-cell *matCellDef="let row">{{ row.name }}</td>
            </ng-container>

            <!-- ISIN -->
            <ng-container matColumnDef="isin">
              <th mat-header-cell *matHeaderCellDef>ISIN</th>
              <td mat-cell *matCellDef="let row">{{ row.isin ?? '—' }}</td>
            </ng-container>

            <!-- Token Standard -->
            <ng-container matColumnDef="tokenStandard">
              <th mat-header-cell *matHeaderCellDef>Standard</th>
              <td mat-cell *matCellDef="let row">
                @if (row.tokenStandard) {
                  <mat-chip>{{ row.tokenStandard }}</mat-chip>
                } @else { — }
              </td>
            </ng-container>

            <!-- Status -->
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let row">
                <app-status-badge [status]="row.status"></app-status-badge>
              </td>
            </ng-container>

            <!-- Chain -->
            <ng-container matColumnDef="chain">
              <th mat-header-cell *matHeaderCellDef>Chain</th>
              <td mat-cell *matCellDef="let row">{{ row.chain | chainName }}</td>
            </ng-container>

            <!-- Actions -->
            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let row" class="actions-cell">
                <a mat-icon-button [routerLink]="['/issuances', row.id]" matTooltip="View details">
                  <mat-icon>arrow_forward</mat-icon>
                </a>
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

            <!-- Empty state -->
            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                No issuances found.
                <a routerLink="/issuances/new">Create your first issuance.</a>
              </td>
            </tr>
          </table>

          <mat-paginator
            [length]="totalElements"
            [pageSize]="pageSize"
            [pageIndex]="pageIndex"
            [pageSizeOptions]="[10, 25, 50]"
            (page)="onPage($event)"
          ></mat-paginator>
        </mat-card>
      }
    </div>
  `,
  styles: [`
    .filter-card { margin-bottom: 16px; }
    .filter-card mat-card-content { padding: 12px !important; }
    .empty-row { text-align: center; padding: 32px; color: #78909c; }
  `]
})
export class IssuanceListComponent implements OnInit {
  private readonly issuanceService = inject(IssuanceService);

  issuances: Asset[] = [];
  loading = true;
  totalElements = 0;
  pageSize = 25;
  pageIndex = 0;
  filterStatus: AssetStatus | null = null;

  readonly displayedColumns = ['assetNumber', 'name', 'isin', 'tokenStandard', 'status', 'chain', 'actions'];

  readonly statusOptions: { value: AssetStatus; label: string }[] = [
    { value: 'DRAFT',            label: 'Draft' },
    { value: 'PENDING_APPROVAL', label: 'Pending Approval' },
    { value: 'APPROVED',         label: 'Approved' },
    { value: 'ISSUED',           label: 'Issued' },
    { value: 'REJECTED',         label: 'Rejected' },
    { value: 'REVOKED',          label: 'Revoked' },
  ];

  ngOnInit(): void {
    this.load();
  }

  applyFilter(): void {
    this.pageIndex = 0;
    this.load();
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize  = event.pageSize;
    this.load();
  }

  private load(): void {
    this.loading = true;
    const params: Record<string, string | number> = {
      page: this.pageIndex,
      size: this.pageSize,
    };
    if (this.filterStatus) params['status'] = this.filterStatus;

    this.issuanceService.getIssuances(params).subscribe({
      next: (res) => {
        this.issuances = res.content;
        this.totalElements = res.totalElements;
        this.loading = false;
      },
      error: () => (this.loading = false),
    });
  }
}
