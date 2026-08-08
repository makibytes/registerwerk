import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { Observable, catchError, forkJoin, of } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { EntityService } from '../../core/api/entity.service';
import { AssetService } from '../../core/api/asset.service';
import { AuditService } from '../../core/api/audit.service';
import { AuditEvent, PageResponse } from '../../core/models';
import { DonutChartComponent, DonutSlice, PageHeaderComponent } from '@registerwerk/ui';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    RouterLink,
    DatePipe,
    DonutChartComponent,
    PageHeaderComponent,
  ],
  styles: [`
    .dashboard-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 20px;
      margin-bottom: 28px;
    }

    .stat-card {
      position: relative;
      overflow: hidden;
      padding: 20px;

      &::before {
        position: absolute;
        inset: 0 auto 0 0;
        width: 3px;
        background: var(--stat-accent, var(--rw-accent));
        content: '';
      }

      &.entities { --stat-accent: var(--rw-text-info); }
      &.kyc { --stat-accent: var(--rw-text-warning); }
      &.assets { --stat-accent: var(--rw-text-secondary); }
      &.issued { --stat-accent: var(--rw-text-success); }

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

    .load-warning {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 20px;
      padding: 12px 14px;
      border: 1px solid var(--rw-pending-fg);
      border-radius: var(--rw-radius);
      background: var(--rw-pending-bg);
      color: var(--rw-pending-fg);

      mat-icon { flex: 0 0 auto; }
      span { flex: 1; font-size: 13px; }
    }

    .section-error {
      display: grid;
      justify-items: center;
      gap: 8px;
      padding: 24px;
      color: var(--rw-text-secondary);
      text-align: center;

      mat-icon { color: var(--rw-text-warning); }
      p { margin: 0; font-size: 13px; }
    }

    .sample-note {
      margin: 12px 0 0;
      color: var(--rw-text-muted);
      font-size: 11px;
      text-align: center;
    }

    .role-home {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: 18px;
      padding: 24px;

      > mat-icon {
        width: 42px;
        height: 42px;
        font-size: 42px;
        color: var(--rw-accent);
      }

      h2 { margin: 0 0 4px; font-size: 17px; }
      p { margin: 0; color: var(--rw-text-secondary); font-size: 13px; }
    }

    @media (max-width: 620px) {
      .role-home { grid-template-columns: 1fr; }
      .load-warning { align-items: flex-start; flex-wrap: wrap; }
    }
  `],
  template: `
    <app-page-header title="Dashboard" subtitle="Registry overview — entities, assets, and recent activity"></app-page-header>

    @if (!canViewRegistryOverview) {
      <section class="content-card role-home">
        <mat-icon>space_dashboard</mat-icon>
        <div>
          <h2>Your operator workspace is ready</h2>
          <p>Registry-wide metrics are limited to registry administrators and auditors. Use the navigation to open the workflows assigned to your role.</p>
        </div>
        @if (auth.hasRole('RELATIONSHIP_MANAGER')) {
          <a mat-flat-button routerLink="/my-clients">Open my clients</a>
        } @else if (auth.hasRole('COMPLIANCE_OFFICER')) {
          <a mat-flat-button routerLink="/compliance/screening">Open screening</a>
        }
      </section>
    } @else {
    @if (hasLoadFailures) {
      <div class="load-warning" role="alert">
        <mat-icon>warning_amber</mat-icon>
        <span>Some dashboard data could not be loaded. Available sections are shown below.</span>
        <button mat-button type="button" (click)="loadDashboard()">Retry</button>
      </div>
    }

    <div class="dashboard-grid" aria-label="Registry statistics">
      <div class="content-card stat-card entities">
        <div class="stat-header">
          <span class="stat-label">Active Entities</span>
          <mat-icon>people</mat-icon>
        </div>
        <div class="stat-value">{{ isUnavailable('entities') ? '—' : stats.activeEntities }}</div>
        <div class="stat-sub">Legal entities registered</div>
      </div>

      <div class="content-card stat-card kyc">
        <div class="stat-header">
          <span class="stat-label">Pending KYC</span>
          <mat-icon>pending_actions</mat-icon>
        </div>
        <div class="stat-value">{{ isUnavailable('kyc') ? '—' : stats.pendingKyc }}</div>
        <div class="stat-sub">Reviews awaiting action</div>
      </div>

      <div class="content-card stat-card assets">
        <div class="stat-header">
          <span class="stat-label">Total Assets</span>
          <mat-icon>account_balance_wallet</mat-icon>
        </div>
        <div class="stat-value">{{ isUnavailable('assets') ? '—' : stats.totalAssets }}</div>
        <div class="stat-sub">Across all issuers</div>
      </div>

      <div class="content-card stat-card issued">
        <div class="stat-header">
          <span class="stat-label">Issued Assets</span>
          <mat-icon>check_circle</mat-icon>
        </div>
        <div class="stat-value">{{ isUnavailable('issued') ? '—' : stats.issuedAssets }}</div>
        <div class="stat-sub">Live on-chain</div>
      </div>
    </div>

    <div class="bottom-grid">
      <div class="content-card">
        <p class="section-title">Assets by Status</p>
        @if (loading) {
          <p class="loading-text">Loading...</p>
        } @else if (!isUnavailable('assets')) {
          <app-donut-chart
            [slices]="assetDonutSlices"
            centerLabel="Assets"
            [centerValue]="stats.totalAssets.toString()">
          </app-donut-chart>
          @if (assetBreakdownIsPartial) {
            <p class="sample-note">Status mix is based on the first {{ loadedAssetCount }} of {{ stats.totalAssets }} assets.</p>
          }
        } @else {
          <div class="section-error" role="status">
            <mat-icon>cloud_off</mat-icon>
            <p>Asset statistics are temporarily unavailable.</p>
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
        } @else if (!isUnavailable('audit')) {
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
        } @else {
          <div class="section-error" role="status">
            <mat-icon>cloud_off</mat-icon>
            <p>Recent audit events are temporarily unavailable.</p>
          </div>
        }
        <div class="card-footer">
          <a mat-button color="primary" routerLink="/audit">View audit log</a>
        </div>
      </div>
    </div>
    }
  `,
})
export class DashboardComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly entityService = inject(EntityService);
  private readonly assetService = inject(AssetService);
  private readonly auditService = inject(AuditService);
  readonly auth = inject(AuthService);

  readonly canViewRegistryOverview = this.auth.hasRole('REGISTRY_ADMIN') || this.auth.hasRole('AUDIT');

  loading = true;
  stats = {
    activeEntities: 0,
    pendingKyc: 0,
    totalAssets: 0,
    issuedAssets: 0,
  };
  assetStatusBreakdown: { status: string; count: number }[] = [];
  assetDonutSlices: DonutSlice[] = [];
  recentEvents: AuditEvent[] = [];
  loadedAssetCount = 0;
  assetBreakdownIsPartial = false;

  private readonly unavailableSections = new Set<'entities' | 'kyc' | 'assets' | 'issued' | 'audit'>();

  get hasLoadFailures(): boolean { return this.unavailableSections.size > 0; }

  private readonly statusColors: Record<string, string> = {
    ISSUED:           'var(--rw-issued-fg)',
    APPROVED:         'var(--rw-approved-fg)',
    PENDING_APPROVAL: 'var(--rw-pending-fg)',
    DRAFT:            'var(--rw-text-muted)',
    SUSPENDED:        'var(--rw-rejected-fg)',
    REDEEMED:         'var(--rw-revoked-fg)',
    TRANSFERRED_OUT:  'var(--rw-text-secondary)',
  };

  ngOnInit(): void {
    if (this.canViewRegistryOverview) this.loadDashboard();
  }

  loadDashboard(): void {
    this.loading = true;
    this.unavailableSections.clear();

    forkJoin({
      entities: this.withFallback('entities', this.entityService.getEntities({ status: 'ACTIVE', size: 1 })),
      pendingKyc: this.withFallback('kyc', this.entityService.getEntities({ kycStatus: 'IN_PROGRESS', size: 1 })),
      assets: this.withFallback('assets', this.assetService.getAssets({ size: 200 })),
      issuedAssets: this.withFallback('issued', this.assetService.getAssets({ status: 'ISSUED', size: 1 })),
      audit: this.withFallback('audit', this.auditService.searchEvents({ size: 5 })),
    }).subscribe(({ entities, pendingKyc, assets, issuedAssets, audit }) => {
        this.stats.activeEntities = entities.totalElements;
        this.stats.pendingKyc = pendingKyc.totalElements;
        this.stats.totalAssets = assets.totalElements;
        this.stats.issuedAssets = issuedAssets.totalElements;
        this.loadedAssetCount = assets.content.length;
        this.assetBreakdownIsPartial = assets.content.length < assets.totalElements;

        const countByStatus: Record<string, number> = {};
        for (const asset of assets.content) {
          countByStatus[asset.status] = (countByStatus[asset.status] ?? 0) + 1;
        }
        this.assetStatusBreakdown = Object.entries(countByStatus).map(
          ([status, count]) => ({ status, count })
        );
        this.assetDonutSlices = this.assetStatusBreakdown
          .filter(e => e.count > 0)
          .map(e => ({
            label: e.status.replace(/_/g, ' '),
            value: e.count,
            color: this.statusColors[e.status] ?? 'var(--rw-text-muted)',
          }));
        this.recentEvents = audit.content;
        this.loading = false;
        this.cdr.markForCheck();
    });
  }

  isUnavailable(section: 'entities' | 'kyc' | 'assets' | 'issued' | 'audit'): boolean {
    return this.unavailableSections.has(section);
  }

  private withFallback<T>(
    section: 'entities' | 'kyc' | 'assets' | 'issued' | 'audit',
    request: Observable<PageResponse<T>>,
  ): Observable<PageResponse<T>> {
    return request.pipe(catchError(() => {
      this.unavailableSections.add(section);
      return of({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 0 });
    }));
  }
}
