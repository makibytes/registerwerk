import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InvestmentService } from '../../../core/api/investment.service';
import { AssetHolder } from '../../../core/models';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';

@Component({
  selector: 'app-investment-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatPaginatorModule,
    MatTooltipModule,
    StatusBadgeComponent,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>My Investments</h1>
      </div>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else {

        <!-- Summary bar -->
        <div class="summary-bar">
          <div class="summary-item">
            <span class="summary-value">{{ totalElements }}</span>
            <span class="summary-label">Holdings</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">{{ whitelistedCount }}</span>
            <span class="summary-label">Whitelisted</span>
          </div>
          <div class="summary-item">
            <span class="summary-value">€ {{ totalNominal | number:'1.0-0' }}</span>
            <span class="summary-label">Total Nominal</span>
          </div>
        </div>

        <mat-card>
          <table mat-table [dataSource]="holdings" class="mat-elevation-z0">

            <!-- Asset Name -->
            <ng-container matColumnDef="assetName">
              <th mat-header-cell *matHeaderCellDef>Asset</th>
              <td mat-cell *matCellDef="let h">{{ h.assetName ?? h.assetId }}</td>
            </ng-container>

            <!-- Nominal Amount -->
            <ng-container matColumnDef="nominalAmount">
              <th mat-header-cell *matHeaderCellDef>Nominal Amount</th>
              <td mat-cell *matCellDef="let h">{{ h.nominalAmount | number:'1.0-2' }}</td>
            </ng-container>

            <!-- Wallet -->
            <ng-container matColumnDef="walletAddress">
              <th mat-header-cell *matHeaderCellDef>Wallet Address</th>
              <td mat-cell *matCellDef="let h">
                <code [matTooltip]="h.walletAddress">
                  {{ h.walletAddress | slice:0:18 }}…
                </code>
              </td>
            </ng-container>

            <!-- Whitelisted -->
            <ng-container matColumnDef="whitelisted">
              <th mat-header-cell *matHeaderCellDef>Whitelisted</th>
              <td mat-cell *matCellDef="let h">
                <app-status-badge [status]="h.whitelisted ? 'WHITELISTED' : 'NOT_WHITELISTED'"></app-status-badge>
              </td>
            </ng-container>

            <!-- Actions -->
            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let h" class="actions-cell">
                <a mat-icon-button [routerLink]="['/investments', h.id]" matTooltip="View details">
                  <mat-icon>arrow_forward</mat-icon>
                </a>
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                No investments found.
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
    .summary-bar {
      display: flex;
      gap: 32px;
      margin-bottom: 24px;
      background: white;
      padding: 16px 24px;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
    }
    .summary-item { display: flex; flex-direction: column; }
    .summary-value { font-size: 28px; font-weight: 700; color: #00695c; }
    .summary-label { font-size: 12px; color: #78909c; margin-top: 2px; }
    code { font-family: monospace; font-size: 12px; background: #f5f5f5; padding: 2px 4px; border-radius: 3px; cursor: help; }
    .empty-row { text-align: center; padding: 32px; color: #78909c; }
  `]
})
export class InvestmentListComponent implements OnInit {
  private readonly investmentService = inject(InvestmentService);

  holdings: AssetHolder[] = [];
  loading = true;
  totalElements = 0;
  pageSize = 25;
  pageIndex = 0;

  whitelistedCount = 0;
  totalNominal = 0;

  readonly displayedColumns = ['assetName', 'nominalAmount', 'walletAddress', 'whitelisted', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize  = event.pageSize;
    this.load();
  }

  private load(): void {
    this.loading = true;
    this.investmentService.getMyInvestments({ page: this.pageIndex, size: this.pageSize }).subscribe({
      next: (res) => {
        this.holdings      = res.content;
        this.totalElements = res.totalElements;
        this.whitelistedCount = res.content.filter(h => h.whitelisted).length;
        this.totalNominal     = res.content.reduce((s, h) => s + h.nominalAmount, 0);
        this.loading = false;
      },
      error: () => (this.loading = false),
    });
  }
}
