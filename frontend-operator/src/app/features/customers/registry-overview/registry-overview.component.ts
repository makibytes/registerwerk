import {
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RegistryOverviewService } from '../../../core/api/registry-overview.service';
import { RegistryEntityNode, RegistryOverview } from '../../../core/models';
import { StatusBadgeComponent } from '@registerwerk/ui';

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
          <!-- Directory -->
          <div class="directory-card">
            <div class="section-head">
              <div>
                <span class="section-kicker">Directory</span>
                <h2>{{ filteredEntities().length }} visible entities</h2>
              </div>
              <span class="section-meta">Updated {{ overview()!.generatedAt | date:'shortTime' }}</span>
            </div>

            <div class="entity-stack" #entityStack>
              @for (entity of filteredEntities(); track entity.id) {
                <article
                  class="entity-card"
                  [class.selected]="selectedEntityId() === entity.id"
                  [attr.data-id]="entity.id"
                  (click)="selectEntity(entity.id)"
                >
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

          <!-- Graph + Asset Panel -->
          <div class="graph-card">
            <div class="section-head">
              <div>
                <span class="section-kicker">Relationship map</span>
                <h2>Issuer ↔ investor flows</h2>
              </div>
              <span class="section-meta">{{ visibleRelationships().length }} active paths</span>
            </div>

            <div class="graph-surface" #graphSurface>
              <div class="graph-inner">
                <div class="lane lane-left">
                  <span class="lane-label">Issuers</span>
                  @for (entity of issuerLane(); track entity.id; let i = $index) {
                    <div
                      class="graph-node issuer"
                      [class.selected]="selectedEntityId() === entity.id"
                      [class.dimmed]="selectedEntityId() !== null && selectedEntityId() !== entity.id"
                      [style.top.%]="nodeTop(i, issuerLane().length)"
                      [attr.data-id]="entity.id"
                      (click)="selectEntity(entity.id)"
                    >
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
                    <div
                      class="graph-node investor"
                      [class.selected]="selectedEntityId() === entity.id"
                      [class.dimmed]="selectedEntityId() !== null && selectedEntityId() !== entity.id"
                      [style.top.%]="nodeTop(i, investorLane().length)"
                      [attr.data-id]="entity.id"
                      (click)="selectEntity(entity.id)"
                    >
                      <span class="graph-node-name">{{ entity.currentName }}</span>
                      <span class="graph-node-meta">{{ entity.investmentCount }} holdings</span>
                    </div>
                  }
                </div>
              </div>
            </div>

            <!-- Asset panel -->
            @if (selectedEntity(); as entity) {
              <div class="asset-panel">
                <div class="asset-panel-header">
                  <div class="asset-panel-identity">
                    <div class="asset-panel-avatar">{{ entity.currentName.charAt(0) }}</div>
                    <div>
                      <p class="asset-panel-name">{{ entity.currentName }}</p>
                      <p class="asset-panel-number">{{ entity.entityNumber }}</p>
                    </div>
                  </div>
                  <div class="asset-panel-actions">
                    <div class="asset-panel-badges">
                      @for (role of entity.roles; track role) {
                        <span class="role-badge" [class.dual-role]="role === 'INVESTOR' && isDualRole(entity)">
                          {{ roleLabel(role) }}
                        </span>
                      }
                      <app-status-badge [status]="entity.kycStatus" />
                    </div>
                    <button class="panel-close-btn" (click)="selectedEntityId.set(null)" title="Deselect entity">
                      <mat-icon>close</mat-icon>
                    </button>
                  </div>
                </div>

                <div class="asset-sections">
                  @if (selectedIssuances().length > 0) {
                    <div class="asset-section">
                      <div class="asset-section-header issuer-header">
                        <mat-icon>north_west</mat-icon>
                        <span>Issued assets</span>
                        <span class="asset-count-pill issuer-pill">{{ selectedIssuances().length }}</span>
                      </div>
                      <div class="asset-rel-list">
                        @for (rel of selectedIssuances(); track rel.assetId + ':' + rel.investorId) {
                          <div class="asset-rel-card">
                            <div class="asset-rel-info">
                              <span class="asset-rel-name">{{ rel.assetName }}</span>
                              <span class="asset-rel-sub">{{ rel.assetNumber }} · {{ entityName(rel.investorId) }}</span>
                            </div>
                            <div class="asset-rel-tail">
                              <span class="asset-rel-amount">{{ rel.nominalAmount | number:'1.0-0' }}</span>
                              <span
                                class="asset-status-chip"
                                [class.issued]="rel.assetStatus === 'ISSUED'"
                                [class.approved]="rel.assetStatus === 'APPROVED'"
                              >{{ rel.assetStatus }}</span>
                              <span
                                class="whitelist-dot"
                                [class.whitelisted]="rel.whitelisted"
                                [title]="rel.whitelisted ? 'Whitelisted' : 'Pending review'"
                              ></span>
                            </div>
                          </div>
                        }
                      </div>
                    </div>
                  }

                  @if (selectedInvestments().length > 0) {
                    <div class="asset-section">
                      <div class="asset-section-header investor-header">
                        <mat-icon>south_east</mat-icon>
                        <span>Held investments</span>
                        <span class="asset-count-pill investor-pill">{{ selectedInvestments().length }}</span>
                      </div>
                      <div class="asset-rel-list">
                        @for (rel of selectedInvestments(); track rel.assetId + ':' + rel.issuerId) {
                          <div class="asset-rel-card">
                            <div class="asset-rel-info">
                              <span class="asset-rel-name">{{ rel.assetName }}</span>
                              <span class="asset-rel-sub">{{ rel.assetNumber }} · {{ entityName(rel.issuerId) }}</span>
                            </div>
                            <div class="asset-rel-tail">
                              <span class="asset-rel-amount">{{ rel.nominalAmount | number:'1.0-0' }}</span>
                              <span
                                class="asset-status-chip"
                                [class.issued]="rel.assetStatus === 'ISSUED'"
                                [class.approved]="rel.assetStatus === 'APPROVED'"
                              >{{ rel.assetStatus }}</span>
                              <span
                                class="whitelist-dot"
                                [class.whitelisted]="rel.whitelisted"
                                [title]="rel.whitelisted ? 'Whitelisted' : 'Pending review'"
                              ></span>
                            </div>
                          </div>
                        }
                      </div>
                    </div>
                  }

                  @if (!selectedIssuances().length && !selectedInvestments().length) {
                    <div class="asset-empty-state">
                      <mat-icon>account_balance_wallet</mat-icon>
                      <p>No active relationships visible for this entity under the current filter.</p>
                    </div>
                  }
                </div>
              </div>
            } @else {
              <div class="asset-panel-prompt">
                <div class="prompt-icon">
                  <mat-icon>hub</mat-icon>
                </div>
                <p>Select an entity from the directory or the diagram to explore its capital relationships</p>
              </div>
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: [],
})
export class RegistryOverviewComponent implements OnInit {
  @ViewChild('graphSurface') private readonly graphSurfaceEl?: ElementRef<HTMLDivElement>;
  @ViewChild('entityStack') private readonly entityStackEl?: ElementRef<HTMLDivElement>;

  private readonly registryOverviewService = inject(RegistryOverviewService);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly overview = signal<RegistryOverview | null>(null);
  readonly loading = signal(true);
  readonly roleFilter = signal<RoleFilter>('ALL');
  readonly searchText = signal('');
  readonly selectedEntityId = signal<string | null>(null);

  readonly roleOptions = [
    { value: 'ALL' as const, label: 'All', icon: 'hub' },
    { value: 'ISSUER' as const, label: 'Issuers', icon: 'north_west' },
    { value: 'INVESTOR' as const, label: 'Investors', icon: 'south_east' },
    { value: 'DUAL' as const, label: 'Dual role', icon: 'sync_alt' },
  ];

  readonly selectedEntity = computed(() => {
    const id = this.selectedEntityId();
    if (!id) return null;
    return this.overview()?.entities.find(e => e.id === id) ?? null;
  });

  readonly selectedIssuances = computed(() =>
    this.selectedEntityId()
      ? (this.overview()?.relationships.filter(r => r.issuerId === this.selectedEntityId()) ?? [])
      : []
  );

  readonly selectedInvestments = computed(() =>
    this.selectedEntityId()
      ? (this.overview()?.relationships.filter(r => r.investorId === this.selectedEntityId()) ?? [])
      : []
  );

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
    const visibleIds = new Set(this.filteredEntities().map(e => e.id));
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
    this.uniqueEntitiesForRelationships('issuerId').filter(e => e.roles.includes('ISSUER'))
  ));

  readonly investorLane = computed(() => this.sortLane(
    this.uniqueEntitiesForRelationships('investorId').filter(e => e.roles.includes('INVESTOR'))
  ));

  readonly graphLines = computed(() => {
    const issuers = this.issuerLane();
    const investors = this.investorLane();
    const issuerIndex = new Map(issuers.map((e, i) => [e.id, i]));
    const investorIndex = new Map(investors.map((e, i) => [e.id, i]));
    const selectedId = this.selectedEntityId();

    return this.visibleRelationships()
      .filter(link => issuerIndex.has(link.issuerId) && investorIndex.has(link.investorId))
      .map(link => {
        const issuerY = this.nodeSvgY(issuerIndex.get(link.issuerId)!, issuers.length);
        const investorY = this.nodeSvgY(investorIndex.get(link.investorId)!, investors.length);
        const baseStroke = Math.min(8, Math.max(2, link.nominalAmount / 500_000));
        const isConnected = !selectedId || link.issuerId === selectedId || link.investorId === selectedId;
        const color = isConnected && selectedId
          ? (link.whitelisted ? 'rgba(16, 185, 129, 0.9)' : 'rgba(245, 158, 11, 0.9)')
          : (link.whitelisted ? 'rgba(16, 185, 129, 0.54)' : 'rgba(245, 158, 11, 0.54)');
        const opacity = selectedId ? (isConnected ? 1 : 0.12) : (link.whitelisted ? 0.9 : 0.6);

        return {
          id: `${link.assetId}:${link.investorId}`,
          path: `M 286 ${issuerY} C 420 ${issuerY}, 580 ${investorY}, 714 ${investorY}`,
          strokeWidth: selectedId && isConnected ? baseStroke * 1.5 : baseStroke,
          color,
          opacity,
        };
      });
  });

  ngOnInit(): void {
    this.registryOverviewService.getOverview().subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.loading.set(false);
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading.set(false);
        this.cdr.markForCheck();
      },
    });
  }

  selectEntity(entityId: string): void {
    const same = this.selectedEntityId() === entityId;
    this.selectedEntityId.set(same ? null : entityId);
    if (!same) {
      setTimeout(() => {
        this.centerEntityInGraph(entityId);
        this.scrollDirectoryToEntity(entityId);
      }, 60);
    }
  }

  roleLabel(role: RegistryEntityNode['roles'][number]): string {
    switch (role) {
      case 'ISSUER': return 'Issuer';
      case 'INVESTOR': return 'Investor';
      case 'AUDITOR': return 'Audit';
    }
  }

  isDualRole(entity: RegistryEntityNode): boolean {
    return entity.roles.includes('ISSUER') && entity.roles.includes('INVESTOR');
  }

  entityName(entityId: string): string {
    return this.overview()?.entities.find(e => e.id === entityId)?.currentName ?? entityId;
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
      case 'ISSUER': return entity.roles.includes('ISSUER');
      case 'INVESTOR': return entity.roles.includes('INVESTOR');
      case 'DUAL': return this.isDualRole(entity);
      default: return true;
    }
  }

  private uniqueEntitiesForRelationships(side: 'issuerId' | 'investorId'): RegistryEntityNode[] {
    const current = this.overview();
    if (!current) return [];
    const ids = new Set(this.visibleRelationships().map(link => link[side]));
    return current.entities.filter(e => ids.has(e.id));
  }

  private sortLane(entities: RegistryEntityNode[]): RegistryEntityNode[] {
    return [...entities].sort((a, b) =>
      (b.issuedAssetCount + b.investmentCount) - (a.issuedAssetCount + a.investmentCount)
    );
  }

  private centerEntityInGraph(entityId: string): void {
    const surface = this.graphSurfaceEl?.nativeElement;
    if (!surface) return;
    const nodeEl = surface.querySelector<HTMLElement>(`[data-id="${entityId}"]`);
    if (!nodeEl) return;
    const scrollTarget = nodeEl.offsetTop - surface.clientHeight / 2;
    surface.scrollTo({ top: Math.max(0, scrollTarget), behavior: 'smooth' });
  }

  private scrollDirectoryToEntity(entityId: string): void {
    const stack = this.entityStackEl?.nativeElement;
    if (!stack) return;
    const cardEl = stack.querySelector<HTMLElement>(`[data-id="${entityId}"]`);
    if (!cardEl) return;
    const scrollTarget = cardEl.offsetTop - stack.clientHeight / 2 + cardEl.offsetHeight / 2;
    stack.scrollTo({ top: Math.max(0, scrollTarget), behavior: 'smooth' });
  }
}
