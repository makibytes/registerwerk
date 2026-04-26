import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { RegistryOverviewService } from '../../../core/api/registry-overview.service';
import {
  RegistryEntityNode,
  RegistryOverview,
  RegistryRelationship,
} from '../../../core/models';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';

type RoleFilter = 'ALL' | 'ISSUER' | 'INVESTOR' | 'DUAL';

@Component({
  selector: 'app-registry-overview',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatChipsModule,
    StatusBadgeComponent,
    DecimalPipe,
  ],
  template: `
    <div class="registry-page">
      <section class="hero-card">
        <div class="hero-copy">
          <span class="eyebrow">Registry atlas</span>
          <h1>Entities and capital relationships</h1>
          <p>
            A live registry view of issuers, investors, dual-role entities, and the capital links
            connecting them.
          </p>
        </div>

        <div class="hero-stats">
          <div class="hero-stat">
            <span class="hero-value">{{ overview()?.summary?.entityCount ?? 0 }}</span>
            <span class="hero-label">Entities</span>
          </div>
          <div class="hero-stat">
            <span class="hero-value">{{ overview()?.summary?.dualRoleCount ?? 0 }}</span>
            <span class="hero-label">Dual role</span>
          </div>
          <div class="hero-stat accent">
            <span class="hero-value">{{ overview()?.summary?.relationshipCount ?? 0 }}</span>
            <span class="hero-label">Links</span>
          </div>
        </div>
      </section>

      <section class="toolbar-card">
        <div class="role-toggle">
          @for (option of roleOptions; track option.value) {
            <button
              type="button"
              class="toggle-pill"
              [class.active]="roleFilter() === option.value"
              (click)="roleFilter.set(option.value)"
            >
              <mat-icon>{{ option.icon }}</mat-icon>
              {{ option.label }}
            </button>
          }
        </div>

        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search-field">
          <mat-label>Search entities or assets</mat-label>
          <input matInput [ngModel]="searchText()" (ngModelChange)="searchText.set($event)" />
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>
      </section>

      @if (loading()) {
        <section class="loading-shell">
          <div class="loading-orbit"></div>
          <p>Mapping the registry graph…</p>
        </section>
      } @else if (overview()) {
        <section class="content-grid">
          <div class="directory-card">
            <div class="section-head">
              <div>
                <span class="section-kicker">Directory</span>
                <h2>{{ filteredEntities().length }} visible entities</h2>
              </div>
              <span class="section-meta">Updated {{ overview()!.generatedAt | date:'shortTime' }}</span>
            </div>

            <div class="entity-stack">
              @for (entity of filteredEntities(); track entity.id) {
                <article class="entity-card" [class.dual]="isDualRole(entity)">
                  <div class="entity-card-head">
                    <div>
                      <p class="entity-name">{{ entity.currentName }}</p>
                      <p class="entity-number">{{ entity.entityNumber }}</p>
                    </div>
                    <div class="role-badges">
                      @for (role of entity.roles; track role) {
                        <span class="role-badge" [class.dual-role]="role === 'INVESTOR' && isDualRole(entity)">
                          {{ roleLabel(role) }}
                        </span>
                      }
                    </div>
                  </div>

                  <div class="entity-badges">
                    <app-status-badge [status]="entity.status" />
                    <app-status-badge [status]="entity.kycStatus" />
                  </div>

                  <div class="metric-row">
                    <div>
                      <span class="metric-label">Issued</span>
                      <span class="metric-value">{{ entity.issuedAssetCount }}</span>
                    </div>
                    <div>
                      <span class="metric-label">Investments</span>
                      <span class="metric-value">{{ entity.investmentCount }}</span>
                    </div>
                    <div>
                      <span class="metric-label">Counterparties</span>
                      <span class="metric-value">{{ entity.linkedInvestorCount + entity.linkedIssuerCount }}</span>
                    </div>
                  </div>
                </article>
              } @empty {
                <div class="empty-note">No entities match the current filter.</div>
              }
            </div>
          </div>

          <div class="graph-card">
            <div class="section-head">
              <div>
                <span class="section-kicker">Relationship map</span>
                <h2>Issuer ↔ investor flows</h2>
              </div>
              <span class="section-meta">{{ visibleRelationships().length }} active paths</span>
            </div>

            <div class="graph-surface">
              <div class="lane lane-left">
                <span class="lane-label">Issuers</span>
                @for (entity of issuerLane(); track entity.id; let i = $index) {
                  <div class="graph-node issuer" [style.top.%]="nodeTop(i, issuerLane().length)">
                    <span class="graph-node-name">{{ entity.currentName }}</span>
                    <span class="graph-node-meta">{{ entity.issuedAssetCount }} assets</span>
                  </div>
                }
              </div>

              <svg class="graph-lines" viewBox="0 0 1000 760" preserveAspectRatio="none" aria-hidden="true">
                @for (link of graphLines(); track link.id) {
                  <path
                    [attr.d]="link.path"
                    [attr.stroke-width]="link.strokeWidth"
                    [attr.stroke]="link.color"
                    [attr.opacity]="link.opacity"
                    fill="none"
                    stroke-linecap="round"
                  />
                }
              </svg>

              <div class="lane lane-right">
                <span class="lane-label">Investors</span>
                @for (entity of investorLane(); track entity.id; let i = $index) {
                  <div class="graph-node investor" [style.top.%]="nodeTop(i, investorLane().length)">
                    <span class="graph-node-name">{{ entity.currentName }}</span>
                    <span class="graph-node-meta">{{ entity.investmentCount }} holdings</span>
                  </div>
                }
              </div>
            </div>

            <div class="relationship-list">
              @for (link of visibleRelationships(); track link.assetId + ':' + link.investorId) {
                <article class="relationship-card">
                  <div>
                    <p class="relationship-asset">{{ link.assetName }}</p>
                    <p class="relationship-meta">{{ entityName(link.issuerId) }} → {{ entityName(link.investorId) }}</p>
                  </div>
                  <div class="relationship-side">
                    <span class="relationship-amount">{{ link.nominalAmount | number:'1.0-0' }}</span>
                    <span class="relationship-chip" [class.whitelisted]="link.whitelisted">
                      {{ link.whitelisted ? 'Whitelisted' : 'Review' }}
                    </span>
                  </div>
                </article>
              } @empty {
                <div class="empty-note graph-empty">No relationships in scope for this filter.</div>
              }
            </div>
          </div>
        </section>
      }
    </div>
  `,
  styles: [],
})
export class RegistryOverviewComponent implements OnInit {
  private readonly registryOverviewService = inject(RegistryOverviewService);

  readonly overview = signal<RegistryOverview | null>(null);
  readonly loading = signal(true);
  readonly roleFilter = signal<RoleFilter>('ALL');
  readonly searchText = signal('');

  readonly roleOptions = [
    { value: 'ALL' as const, label: 'All', icon: 'hub' },
    { value: 'ISSUER' as const, label: 'Issuers', icon: 'north_west' },
    { value: 'INVESTOR' as const, label: 'Investors', icon: 'south_east' },
    { value: 'DUAL' as const, label: 'Dual role', icon: 'sync_alt' },
  ];

  readonly filteredEntities = computed(() => {
    const current = this.overview();
    if (!current) return [];

    const query = this.searchText().trim().toLowerCase();
    return current.entities.filter(entity => {
      const matchesRole = this.matchesRole(entity, this.roleFilter());
      const matchesText = !query
        || entity.currentName.toLowerCase().includes(query)
        || entity.entityNumber.toLowerCase().includes(query);
      return matchesRole && matchesText;
    });
  });

  readonly visibleRelationships = computed(() => {
    const current = this.overview();
    if (!current) return [];

    const visibleIds = new Set(this.filteredEntities().map(entity => entity.id));
    const query = this.searchText().trim().toLowerCase();

    return current.relationships.filter(link => {
      const roleMatch = this.roleFilter() === 'ALL'
        || (this.roleFilter() === 'ISSUER' && visibleIds.has(link.issuerId))
        || (this.roleFilter() === 'INVESTOR' && visibleIds.has(link.investorId))
        || (this.roleFilter() === 'DUAL' && (visibleIds.has(link.issuerId) || visibleIds.has(link.investorId)));
      const queryMatch = !query
        || link.assetName.toLowerCase().includes(query)
        || link.assetNumber.toLowerCase().includes(query)
        || visibleIds.has(link.issuerId)
        || visibleIds.has(link.investorId);
      return roleMatch && queryMatch;
    });
  });

  readonly issuerLane = computed(() => this.sortLane(
    this.uniqueEntitiesForRelationships('issuerId').filter(entity => entity.roles.includes('ISSUER'))
  ));

  readonly investorLane = computed(() => this.sortLane(
    this.uniqueEntitiesForRelationships('investorId').filter(entity => entity.roles.includes('INVESTOR'))
  ));

  readonly graphLines = computed(() => {
    const issuers = this.issuerLane();
    const investors = this.investorLane();
    const issuerIndex = new Map(issuers.map((entity, index) => [entity.id, index]));
    const investorIndex = new Map(investors.map((entity, index) => [entity.id, index]));

    return this.visibleRelationships()
      .filter(link => issuerIndex.has(link.issuerId) && investorIndex.has(link.investorId))
      .map(link => {
        const issuerY = this.nodeSvgY(issuerIndex.get(link.issuerId)!, issuers.length);
        const investorY = this.nodeSvgY(investorIndex.get(link.investorId)!, investors.length);
        const strokeWidth = Math.min(8, Math.max(2, link.nominalAmount / 500_000));
        const color = link.whitelisted ? 'rgba(16, 185, 129, 0.54)' : 'rgba(245, 158, 11, 0.54)';

        return {
          id: `${link.assetId}:${link.investorId}`,
          path: `M 286 ${issuerY} C 420 ${issuerY}, 580 ${investorY}, 714 ${investorY}`,
          strokeWidth,
          color,
          opacity: link.whitelisted ? 0.9 : 0.6,
        };
      });
  });

  ngOnInit(): void {
    this.registryOverviewService.getOverview().subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  roleLabel(role: RegistryEntityNode['roles'][number]): string {
    switch (role) {
      case 'ISSUER':
        return 'Issuer';
      case 'INVESTOR':
        return 'Investor';
      case 'AUDITOR':
        return 'Audit';
    }
  }

  isDualRole(entity: RegistryEntityNode): boolean {
    return entity.roles.includes('ISSUER') && entity.roles.includes('INVESTOR');
  }

  entityName(entityId: string): string {
    return this.overview()?.entities.find(entity => entity.id === entityId)?.currentName ?? entityId;
  }

  nodeTop(index: number, total: number): number {
    if (total <= 1) return 50;
    return 12 + (index * 76) / Math.max(1, total - 1);
  }

  private nodeSvgY(index: number, total: number): number {
    if (total <= 1) return 380;
    return 90 + (index * 560) / Math.max(1, total - 1);
  }

  private matchesRole(entity: RegistryEntityNode, filter: RoleFilter): boolean {
    switch (filter) {
      case 'ISSUER':
        return entity.roles.includes('ISSUER');
      case 'INVESTOR':
        return entity.roles.includes('INVESTOR');
      case 'DUAL':
        return this.isDualRole(entity);
      default:
        return true;
    }
  }

  private uniqueEntitiesForRelationships(side: 'issuerId' | 'investorId'): RegistryEntityNode[] {
    const current = this.overview();
    if (!current) return [];

    const ids = new Set(this.visibleRelationships().map(link => link[side]));
    return current.entities.filter(entity => ids.has(entity.id));
  }

  private sortLane(entities: RegistryEntityNode[]): RegistryEntityNode[] {
    return [...entities].sort((left, right) =>
      (right.issuedAssetCount + right.investmentCount) - (left.issuedAssetCount + left.investmentCount)
    );
  }
}
