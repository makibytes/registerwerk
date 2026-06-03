import { ChangeDetectorRef, Component, OnInit, ViewChild, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe } from '@angular/common';
import { AssetService } from '../../../core/api/asset.service';
import { Asset } from '../../../core/models';
import { StatusBadgeComponent } from '@registerwerk/ui';

@Component({
  selector: 'app-asset-list',
  standalone: true,
  imports: [
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
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

      mat-form-field { min-width: 160px; }
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

    code.isin {
      font-size: 12px;
      background: var(--rw-bg);
      padding: 2px 6px;
      border-radius: var(--rw-radius-sm);
      color: var(--rw-text-secondary);
    }
  `],
  template: `
    <div class="page-header">
      <h1>Assets</h1>
    </div>

    <div class="content-card">
      <div class="filter-row">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Search</mat-label>
          <input matInput [(ngModel)]="searchText" (ngModelChange)="onFilterChange()" placeholder="Name, ISIN..." />
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Status</mat-label>
          <mat-select [(ngModel)]="filterStatus" (ngModelChange)="onFilterChange()">
            <mat-option value="">All</mat-option>
            <mat-option value="DRAFT">Draft</mat-option>
            <mat-option value="PENDING_APPROVAL">Pending Approval</mat-option>
            <mat-option value="APPROVED">Approved</mat-option>
            <mat-option value="ISSUED">Issued</mat-option>
            <mat-option value="SUSPENDED">Suspended</mat-option>
            <mat-option value="REDEEMED">Redeemed</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Token Standard</mat-label>
          <mat-select [(ngModel)]="filterStandard" (ngModelChange)="onFilterChange()">
            <mat-option value="">All</mat-option>
            <mat-option value="ERC20">ERC-20</mat-option>
            <mat-option value="ERC721">ERC-721</mat-option>
            <mat-option value="ERC1155">ERC-1155</mat-option>
            <mat-option value="ERC3643">ERC-3643</mat-option>
            <mat-option value="CONF_ERC20">Conf. ERC-20</mat-option>
            <mat-option value="CONF_ERC3643">Conf. ERC-3643</mat-option>
            <mat-option value="SPL">SPL</mat-option>
          </mat-select>
        </mat-form-field>
      </div>

      @if (loading) {
        <div class="spinner-container">
          <mat-spinner diameter="40" />
        </div>
      } @else {
        <table mat-table [dataSource]="dataSource" class="full-width-table">
          <ng-container matColumnDef="assetNumber">
            <th mat-header-cell *matHeaderCellDef>Asset Number</th>
            <td mat-cell *matCellDef="let row"><code>{{ row.assetNumber }}</code></td>
          </ng-container>

          <ng-container matColumnDef="name">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let row">{{ row.name }}</td>
          </ng-container>

          <ng-container matColumnDef="isin">
            <th mat-header-cell *matHeaderCellDef>ISIN</th>
            <td mat-cell *matCellDef="let row">
              @if (row.isin) {
                <code class="isin">{{ row.isin }}</code>
              } @else {
                <span class="text-muted">—</span>
              }
            </td>
          </ng-container>

          <ng-container matColumnDef="tokenStandard">
            <th mat-header-cell *matHeaderCellDef>Standard</th>
            <td mat-cell *matCellDef="let row">{{ row.tokenStandard }}</td>
          </ng-container>

          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let row">
              <app-status-badge [status]="row.status" />
            </td>
          </ng-container>

          <ng-container matColumnDef="createdAt">
            <th mat-header-cell *matHeaderCellDef>Created</th>
            <td mat-cell *matCellDef="let row">{{ row.createdAt | date:'mediumDate' }}</td>
          </ng-container>

          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-icon-button color="primary" (click)="viewAsset(row)" matTooltip="View">
                <mat-icon>open_in_new</mat-icon>
              </button>
              <button mat-icon-button (click)="editAsset(row)" matTooltip="Edit">
                <mat-icon>edit</mat-icon>
              </button>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;" style="cursor:pointer" (click)="viewAsset(row)"></tr>
        </table>

        @if (dataSource.data.length === 0) {
          <div class="no-data">No assets found.</div>
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
export class AssetListComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly assetService = inject(AssetService);
  private readonly router = inject(Router);

  readonly displayedColumns = [
    'assetNumber', 'name', 'isin', 'tokenStandard', 'status', 'createdAt', 'actions',
  ];

  dataSource = new MatTableDataSource<Asset>([]);
  loading = false;
  totalElements = 0;
  pageSize = 25;
  pageIndex = 0;

  searchText = '';
  filterStatus = '';
  filterStandard = '';

  @ViewChild(MatPaginator) paginator!: MatPaginator;

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.assetService
      .getAssets({
        search: this.searchText || undefined,
        status: this.filterStatus || undefined,
        tokenStandard: this.filterStandard || undefined,
        page: this.pageIndex,
        size: this.pageSize,
      })
      .subscribe({
        next: (resp) => {
          this.dataSource.data = resp.content;
          this.totalElements = resp.totalElements;
          this.loading = false;
          this.cdr.detectChanges();
        },
        error: () => {
          this.loading = false;
          this.cdr.detectChanges();
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

  viewAsset(asset: Asset): void {
    this.router.navigate(['/assets', asset.id]);
  }

  editAsset(asset: Asset): void {
    this.router.navigate(['/assets', asset.id, 'edit']);
  }
}
