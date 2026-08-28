import {
  ChangeDetectorRef,
  Component,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EntityService } from '../../../core/api/entity.service';
import { LegalEntity } from '../../../core/models';
import { StatusBadgeComponent } from '@registerwerk/ui';
import { DatePipe } from '@angular/common';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-customer-list',
  standalone: true,
  imports: [
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    StatusBadgeComponent,
    DatePipe,
  ],
  styles: [`
    .filter-row {
      display: flex;
      gap: 16px;
      align-items: flex-start;
      margin-bottom: 20px;
      flex-wrap: wrap;

      mat-form-field {
        min-width: 160px;
      }
    }

    .spinner-container {
      display: flex;
      justify-content: center;
      padding: 48px;
    }

    .no-data {
      text-align: center;
      padding: 48px;
      color: var(--rw-text-muted);
    }

    .request-error {
      display: grid;
      justify-items: center;
      gap: 12px;
      padding: 48px 20px;
      text-align: center;
      color: var(--rw-text-danger);
    }

    .table-scroll { overflow-x: auto; }
    table { min-width: 820px; }

    @media (max-width: 640px) {
      .page-header { align-items: flex-start; gap: 12px; flex-direction: column; }
      .filter-row mat-form-field { width: 100%; }
    }
  `],
  template: `
    <div class="page-header">
      <h1>Customers</h1>
      @if (canCreate) {
      <button type="button" mat-raised-button color="primary" (click)="createNew()">
        <mat-icon>add</mat-icon>
        New Entity
      </button>
      }
    </div>

    <div class="content-card">
      <div class="filter-row">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Search</mat-label>
          <input matInput [(ngModel)]="searchText" (ngModelChange)="onFilterChange()" placeholder="Name or number..." />
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Type</mat-label>
          <mat-select [(ngModel)]="filterType" (ngModelChange)="onFilterChange()">
            <mat-option value="">All</mat-option>
            <mat-option value="ISSUER">Issuer</mat-option>
            <mat-option value="INVESTOR">Investor</mat-option>
            <mat-option value="AUDITOR">Auditor</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Status</mat-label>
          <mat-select [(ngModel)]="filterStatus" (ngModelChange)="onFilterChange()">
            <mat-option value="">All</mat-option>
            <mat-option value="PENDING_ONBOARDING">Pending Onboarding</mat-option>
            <mat-option value="ACTIVE">Active</mat-option>
            <mat-option value="SUSPENDED">Suspended</mat-option>
            <mat-option value="DISSOLVED">Dissolved</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>KYC Status</mat-label>
          <mat-select [(ngModel)]="filterKycStatus" (ngModelChange)="onFilterChange()">
            <mat-option value="">All</mat-option>
            <mat-option value="NOT_STARTED">Not Started</mat-option>
            <mat-option value="IN_PROGRESS">In Progress</mat-option>
            <mat-option value="APPROVED">Approved</mat-option>
            <mat-option value="REJECTED">Rejected</mat-option>
            <mat-option value="EXPIRED">Expired</mat-option>
          </mat-select>
        </mat-form-field>
      </div>

      @if (loading) {
        <div class="spinner-container">
          <mat-spinner diameter="40" />
        </div>
      } @else if (loadError) {
        <div class="request-error" role="alert">
          <mat-icon>cloud_off</mat-icon>
          <span>Customers could not be loaded.</span>
          <button mat-stroked-button type="button" (click)="loadData()">Retry</button>
        </div>
      } @else {
        <div class="table-scroll">
        <table mat-table [dataSource]="dataSource" class="full-width-table">
          <ng-container matColumnDef="entityNumber">
            <th mat-header-cell *matHeaderCellDef>Entity Number</th>
            <td mat-cell *matCellDef="let row">
              <code>{{ row.entityNumber }}</code>
            </td>
          </ng-container>

          <ng-container matColumnDef="currentName">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let row">{{ row.currentName }}</td>
          </ng-container>

          <ng-container matColumnDef="type">
            <th mat-header-cell *matHeaderCellDef>Type</th>
            <td mat-cell *matCellDef="let row">{{ row.type }}</td>
          </ng-container>

          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let row">
              <app-status-badge [status]="row.status" />
            </td>
          </ng-container>

          <ng-container matColumnDef="kycStatus">
            <th mat-header-cell *matHeaderCellDef>KYC</th>
            <td mat-cell *matCellDef="let row">
              <app-status-badge [status]="row.kycStatus" />
            </td>
          </ng-container>

          <ng-container matColumnDef="createdAt">
            <th mat-header-cell *matHeaderCellDef>Created</th>
            <td mat-cell *matCellDef="let row">{{ row.createdAt | date:'mediumDate' }}</td>
          </ng-container>

          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <div class="actions-cell">
                <button type="button" mat-icon-button color="primary" (click)="$event.stopPropagation(); viewEntity(row)"
                        [attr.aria-label]="'View ' + row.currentName" matTooltip="View details">
                  <mat-icon>open_in_new</mat-icon>
                </button>
              </div>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;" class="interactive-row"
              tabindex="0" [attr.aria-label]="'Open entity ' + row.currentName"
              (click)="viewEntity(row)" (keydown.enter)="viewEntity(row)"
              (keydown.space)="viewEntity(row); $event.preventDefault()"></tr>
        </table>
        </div>

        @if (dataSource.data.length === 0) {
          <div class="no-data">No entities found.</div>
        }

        <mat-paginator
          [length]="totalElements"
          [pageSize]="pageSize"
          [pageIndex]="pageIndex"
          [pageSizeOptions]="[10, 25, 50]"
          (page)="onPage($event)"
          showFirstLastButtons
        />
      }
    </div>
  `,
})
export class CustomerListComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly entityService = inject(EntityService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  readonly canCreate = this.auth.hasRole('REGISTRY_ADMIN');

  readonly displayedColumns = [
    'entityNumber',
    'currentName',
    'type',
    'status',
    'kycStatus',
    'createdAt',
    'actions',
  ];

  dataSource = new MatTableDataSource<LegalEntity>([]);
  loading = false;
  loadError = false;
  totalElements = 0;
  pageSize = 25;
  pageIndex = 0;

  searchText = '';
  filterType = '';
  filterStatus = '';
  filterKycStatus = '';

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.loadError = false;
    this.entityService
      .getEntities({
        search: this.searchText || undefined,
        type: this.filterType || undefined,
        status: this.filterStatus || undefined,
        kycStatus: this.filterKycStatus || undefined,
        page: this.pageIndex,
        size: this.pageSize,
      })
      .subscribe({
        next: (resp) => {
          this.dataSource.data = resp.content;
          this.totalElements = resp.totalElements;
          this.loading = false;
          this.loadError = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loading = false;
          this.loadError = true;
          this.cdr.markForCheck();
        },
      });
  }

  onFilterChange(): void {
    this.pageIndex = 0;
    this.loadData();
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadData();
  }

  viewEntity(entity: LegalEntity): void {
    this.router.navigate(['/customers', entity.id]);
  }

  createNew(): void {
    this.router.navigate(['/onboarding/create']);
  }
}
