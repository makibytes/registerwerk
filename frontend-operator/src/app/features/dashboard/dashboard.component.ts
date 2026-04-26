import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { forkJoin } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { EntityService } from '../../core/api/entity.service';
import { AssetService } from '../../core/api/asset.service';
import { AuditService } from '../../core/api/audit.service';
import { AuditEvent } from '../../core/models';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    RouterLink,
    DatePipe,
    StatusBadgeComponent,
  ],
  styles: [`
    .dashboard-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 20px;
      margin-bottom: 28px;
    }

    .stat-card {
      padding: 20px;

      .stat-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 12px;

        .stat-label {
          font-size: 11px;
          color: var(--rw-text-muted);
          text-transform: uppercase;
          letter-spacing: 0.5px;
          font-weight: 600;
        }

        mat-icon {
          color: var(--rw-text-muted);
          font-size: 22px;
          width: 22px;
          height: 22px;
        }
      }

      .stat-value {
        font-size: 32px;
        font-weight: 700;
        color: var(--rw-text-primary);
        line-height: 1;
        margin-bottom: 6px;
        letter-spacing: -0.4px;
      }

      .stat-sub {
        font-size: 12px;
        color: var(--rw-text-secondary);
      }
    }

    .bottom-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 20px;

      @media (max-width: 900px) {
        grid-template-columns: 1fr;
      }
    }

    .section-title {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      margin: 0 0 16px;
      color: var(--rw-text-muted);
    }

    .status-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .status-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 14px;
      color: var(--rw-text-secondary);

      .count {
        font-weight: 600;
        color: var(--rw-text-primary);
      }
    }

    .event-list {
      display: flex;
      flex-direction: column;
      gap: 0;
    }

    .event-row {
      padding: 10px 0;
      border-bottom: 1px solid var(--rw-border-subtle);

      &:last-child {
        border-bottom: none;
      }

      .event-type {
        font-size: 13px;
        font-weight: 500;
        color: var(--rw-text-primary);
      }

      .event-meta {
        font-size: 12px;
        color: var(--rw-text-muted);
        margin-top: 2px;
      }
    }

    .card-footer {
      display: flex;
      justify-content: flex-end;
      margin-top: 16px;
      padding-top: 12px;
      border-top: 1px solid var(--rw-border-subtle);
    }

    .loading-text {
      color: var(--rw-text-muted);
      font-size: 14px;
      text-align: center;
      padding: 24px;
    }
  `],
  template: `
    <div class="page-header">
      <h1>Dashboard</h1>
    </div>

    <div class="dashboard-grid">
      <div class="content-card stat-card">
        <div class="stat-header">
          <span class="stat-label">Active Entities</span>
          <mat-icon>people</mat-icon>
        </div>
        <div class="stat-value">{{ stats.activeEntities }}</div>
        <div class="stat-sub">Legal entities registered</div>
      </div>

      <div class="content-card stat-card">
        <div class="stat-header">
          <span class="stat-label">Pending KYC</span>
          <mat-icon>pending_actions</mat-icon>
        </div>
        <div class="stat-value">{{ stats.pendingKyc }}</div>
        <div class="stat-sub">Reviews awaiting action</div>
      </div>

      <div class="content-card stat-card">
        <div class="stat-header">
          <span class="stat-label">Total Assets</span>
          <mat-icon>account_balance_wallet</mat-icon>
        </div>
        <div class="stat-value">{{ stats.totalAssets }}</div>
        <div class="stat-sub">Across all issuers</div>
      </div>

      <div class="content-card stat-card">
        <div class="stat-header">
          <span class="stat-label">Issued Assets</span>
          <mat-icon>check_circle</mat-icon>
        </div>
        <div class="stat-value">{{ stats.issuedAssets }}</div>
        <div class="stat-sub">Live on-chain</div>
      </div>
    </div>

    <div class="bottom-grid">
      <div class="content-card">
        <p class="section-title">Assets by Status</p>
        @if (loading) {
          <p class="loading-text">Loading...</p>
        } @else {
          <div class="status-list">
            @for (entry of assetStatusBreakdown; track entry.status) {
              <div class="status-row">
                <app-status-badge [status]="entry.status" />
                <span class="count">{{ entry.count }}</span>
              </div>
            }
          </div>
        }
        <div class="card-footer">
          <a mat-button color="primary" routerLink="/assets">View all assets</a>
        </div>
      </div>

      <div class="content-card">
        <p class="section-title">Recent Audit Events</p>
        @if (loading) {
          <p class="loading-text">Loading...</p>
        } @else {
          <div class="event-list">
            @for (event of recentEvents; track event.id) {
              <div class="event-row">
                <div class="event-type">{{ event.eventType }}</div>
                <div class="event-meta">
                  {{ event.subjectType }} · {{ event.occurredAt | date:'short' }}
                </div>
              </div>
            } @empty {
              <p class="loading-text">No recent events</p>
            }
          </div>
        }
        <div class="card-footer">
          <a mat-button color="primary" routerLink="/audit">View audit log</a>
        </div>
      </div>
    </div>
  `,
})
export class DashboardComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly entityService = inject(EntityService);
  private readonly assetService = inject(AssetService);
  private readonly auditService = inject(AuditService);

  loading = true;
  stats = {
    activeEntities: 0,
    pendingKyc: 0,
    totalAssets: 0,
    issuedAssets: 0,
  };
  assetStatusBreakdown: { status: string; count: number }[] = [];
  recentEvents: AuditEvent[] = [];

  ngOnInit(): void {
    forkJoin({
      entities: this.entityService.getEntities({ status: 'ACTIVE', size: 1 }),
      pendingKyc: this.entityService.getEntities({ kycStatus: 'IN_PROGRESS', size: 1 }),
      assets: this.assetService.getAssets({ size: 200 }),
      audit: this.auditService.searchEvents({ size: 5 }),
    }).subscribe({
      next: ({ entities, pendingKyc, assets, audit }) => {
        this.stats.activeEntities = entities.totalElements;
        this.stats.pendingKyc = pendingKyc.totalElements;
        this.stats.totalAssets = assets.totalElements;

        // Compute breakdown from the first page
        const countByStatus: Record<string, number> = {};
        for (const asset of assets.content) {
          countByStatus[asset.status] = (countByStatus[asset.status] ?? 0) + 1;
        }
        this.assetStatusBreakdown = Object.entries(countByStatus).map(
          ([status, count]) => ({ status, count })
        );
        this.stats.issuedAssets = countByStatus['ISSUED'] ?? 0;
        this.recentEvents = audit.content;
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
