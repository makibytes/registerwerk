import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ChainService, ChainConfigCreateRequest, ChainConfigUpdateRequest, RpcNodeWriteRequest } from '../../core/api/chain.service';
import { ChainConfig, ChainHealth, RpcNode } from '../../core/models';
import { environment } from '../../../environments/environment';
import { EMPTY, Subject, Subscription, catchError, exhaustMap, finalize, merge, timer } from 'rxjs';
import { AddNodeDialogComponent } from './add-node-dialog.component';
import { ChainConfigDialogComponent } from './chain-config-dialog.component';

@Component({
  selector: 'app-network-nodes',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  styles: [`
    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 24px;
    }

    .page-header h1 {
      font-size: 21px;
      font-weight: 700;
      color: var(--rw-text-primary);
      letter-spacing: -0.4px;
      margin: 0;
    }

    .page-subtitle {
      font-size: 13px;
      color: var(--rw-text-secondary);
      margin: 4px 0 0;
    }

    .refresh-info {
      font-size: 12px;
      color: var(--rw-text-muted);
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .refresh-info mat-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
      animation: spin 2s linear infinite;
    }

    .refresh-info.error { color: var(--rw-text-danger); }
    .refresh-info.error mat-icon { animation: none; }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .chain-card {
      background: var(--rw-surface);
      border: 1px solid var(--rw-border);
      border-radius: 10px;
      margin-bottom: 16px;
      overflow: hidden;
    }

    .chain-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px 20px;
      border-bottom: 1px solid var(--rw-border);
      background: var(--rw-surface-raised);
    }

    .chain-icon {
      width: 36px;
      height: 36px;
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
      font-weight: 700;
      flex-shrink: 0;
    }

    .chain-icon.evm      { background: rgba(98, 126, 234, 0.15); color: #627EEA; }
    .chain-icon.solana   { background: rgba(153, 69, 255, 0.15); color: #9945FF; }
    .chain-icon.starknet { background: rgba(255, 138, 0, 0.15);  color: #FF8A00; }
    .chain-icon.stellar  { background: rgba(0, 182, 255, 0.15);  color: #00B6FF; }
    .chain-icon.canton   { background: rgba(22, 170, 120, 0.15); color: #16AA78; }

    .chain-info { flex: 1; min-width: 0; }

    .chain-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--rw-text-primary);
      margin: 0;
    }

    .chain-meta {
      font-size: 11px;
      color: var(--rw-text-muted);
      margin: 2px 0 0;
      font-family: 'IBM Plex Mono', monospace;
    }

    .chain-badges {
      display: flex;
      gap: 6px;
      align-items: center;
      flex-shrink: 0;
    }

    .badge {
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.5px;
      text-transform: uppercase;
    }

    .badge-mainnet { background: rgba(22, 163, 74, 0.12); color: #16a34a; }
    .badge-testnet { background: rgba(245, 158, 11, 0.12); color: #d97706; }
    .badge-disabled { background: rgba(140, 152, 174, 0.15); color: var(--rw-text-muted); }

    .add-node-btn {
      font-size: 12px;
      height: 30px;
      line-height: 30px;
      padding: 0 12px;
    }

    .nodes-table-wrap {
      overflow-x: auto;
    }

    table {
      width: 100%;
      border-collapse: collapse;
    }

    thead th {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      color: var(--rw-text-muted);
      padding: 10px 20px;
      text-align: left;
      border-bottom: 1px solid var(--rw-border);
      white-space: nowrap;
    }

    tbody tr {
      transition: background 0.1s ease;
    }

    tbody tr:hover {
      background: var(--rw-bg);
    }

    tbody tr:not(:last-child) td {
      border-bottom: 1px solid var(--rw-border);
    }

    td {
      padding: 12px 20px;
      font-size: 13px;
      color: var(--rw-text-primary);
      vertical-align: middle;
    }

    .node-url {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      color: var(--rw-text-primary);
      max-width: 300px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .node-label {
      font-size: 11px;
      color: var(--rw-text-muted);
      margin-top: 2px;
    }

    .health-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
      margin-right: 6px;
      flex-shrink: 0;
    }

    .health-row {
      display: flex;
      align-items: center;
    }

    .health-dot.healthy   { background: #22c55e; box-shadow: 0 0 6px rgba(34,197,94,0.4); }
    .health-dot.unhealthy { background: #ef4444; box-shadow: 0 0 6px rgba(239,68,68,0.3); }
    .health-dot.unknown   { background: var(--rw-text-muted); }

    .health-label {
      font-size: 12px;
      font-weight: 600;
    }

    .health-label.healthy   { color: #22c55e; }
    .health-label.unhealthy { color: #ef4444; }
    .health-label.unknown   { color: var(--rw-text-muted); }

    .block-num {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      color: var(--rw-text-secondary);
    }

    .lag-chip {
      display: inline-flex;
      align-items: center;
      padding: 1px 6px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 600;
    }

    .lag-chip.ok    { background: rgba(34,197,94,0.12); color: #16a34a; }
    .lag-chip.warn  { background: rgba(245,158,11,0.12); color: #d97706; }
    .lag-chip.crit  { background: rgba(239,68,68,0.12); color: #dc2626; }

    .syncing-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 6px;
      border-radius: 4px;
      background: rgba(99, 102, 241, 0.12);
      color: #6366f1;
      font-size: 11px;
      font-weight: 600;
    }

    .syncing-badge mat-icon {
      font-size: 12px;
      width: 12px;
      height: 12px;
    }

    .last-seen {
      font-size: 11px;
      color: var(--rw-text-muted);
    }

    .action-row {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .exclusive-btn {
      min-width: 0;
      padding: 0 8px;
      height: 28px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 5px;
      border: 1px solid var(--rw-border);
      background: transparent;
      color: var(--rw-text-secondary);
      cursor: pointer;
      transition: all 0.15s ease;
      display: flex;
      align-items: center;
      gap: 4px;
      white-space: nowrap;
    }

    .exclusive-btn mat-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
    }

    .exclusive-btn.active {
      background: rgba(245,158,11,0.12);
      border-color: #f59e0b;
      color: #d97706;
    }

    .exclusive-btn:hover:not(.active) {
      background: var(--rw-bg);
      color: var(--rw-text-primary);
    }

    .delete-btn {
      color: var(--rw-text-muted);
    }

    .delete-btn:hover {
      color: #ef4444;
    }

    .no-nodes {
      padding: 32px 20px;
      text-align: center;
      color: var(--rw-text-muted);
    }

    .loading-state {
      padding: 60px 0;
      text-align: center;
      color: var(--rw-text-muted);
    }

    .loading-state.error {
      display: grid;
      justify-items: center;
      gap: 10px;
      color: var(--rw-text-danger);
    }

    @media (max-width: 720px) {
      .page-header { align-items: flex-start; gap: 12px; flex-direction: column; }
      .chain-header { align-items: flex-start; flex-wrap: wrap; }
      .chain-badges { width: 100%; flex-wrap: wrap; }
      table { min-width: 780px; }
    }

    .failures-text {
      font-size: 11px;
      color: #ef4444;
    }

    .stopped-row td {
      opacity: 0.55;
    }

    .add-chain-btn { font-size: 12px; height: 32px; }
    .chain-actions { display: flex; gap: 8px; align-items: center; }
    .settings-btn { color: var(--rw-text-muted); }
    /* .finality-source-chip, .kind-badge, .capabilities-*, .chaincheck-link — global, see styles.scss
       "chaincache capability comparison" section: reused wherever a node exposes chaincache
       capability data, not just this page, and kept this component under its style budget. */
  `],
  template: `
    <div class="page-header">
      <div>
        <h1>Network Nodes</h1>
        <p class="page-subtitle">RPC endpoint health for all configured blockchains</p>
      </div>
      <div class="chain-actions">
        @if (loading()) {
          <div class="refresh-info">
            <mat-icon>refresh</mat-icon>
            <span>Refreshing…</span>
          </div>
        } @else if (loadError()) {
          <div class="refresh-info error">
            <mat-icon>sync_problem</mat-icon>
            <span>Last refresh failed</span>
            <button mat-button type="button" (click)="refresh()">Retry</button>
          </div>
        } @else {
          <div class="refresh-info">
            <mat-icon style="animation: none; opacity: 0.5">schedule</mat-icon>
            <span>Auto-refreshes every 30s</span>
          </div>
        }
        <button mat-stroked-button class="add-chain-btn" (click)="openAddChain()">
          <mat-icon style="font-size: 16px; width: 16px; height: 16px; margin-right: 4px">add</mat-icon>
          Add chain
        </button>
      </div>
    </div>

    @if (chains().length === 0 && loading()) {
      <div class="loading-state">Loading node health data…</div>
    } @else if (chains().length === 0 && loadError()) {
      <div class="loading-state error" role="alert">
        <mat-icon>cloud_off</mat-icon>
        <span>Node health data could not be loaded.</span>
        <button mat-stroked-button type="button" (click)="refresh()">Retry</button>
      </div>
    }

    @for (chain of chains(); track chain.id) {
      <div class="chain-card">
        <div class="chain-header">
          <div class="chain-icon"
               [class.evm]="chain.chainType === 'EVM'"
               [class.solana]="chain.chainType === 'SOLANA'"
               [class.starknet]="chain.chainType === 'STARKNET'"
               [class.stellar]="chain.chainType === 'STELLAR'"
               [class.canton]="chain.chainType === 'CANTON'">
            {{ chain.chainType === 'SOLANA' ? '◎' : chain.chainType === 'STARKNET' ? '★' : chain.chainType === 'STELLAR' ? '✦' : chain.chainType === 'CANTON' ? '⬡' : 'Ξ' }}
          </div>
          <div class="chain-info">
            <p class="chain-name">{{ chain.displayName }}</p>
            <p class="chain-meta">{{ chain.identifier }}@if (chain.chainId) { · chain {{ chain.chainId }} }</p>
          </div>
          <div class="chain-badges">
            <span class="badge" [class.badge-mainnet]="chain.networkType === 'MAINNET'" [class.badge-testnet]="chain.networkType === 'TESTNET'">
              {{ chain.networkType.toLowerCase() }}
            </span>
            @if (!chain.enabled) {
              <span class="badge badge-disabled">disabled</span>
            }
            <span class="finality-source-chip" [matTooltip]="finalitySourceTooltip(chain)">
              {{ chainConfigOf(chain)?.finalitySource === 'CHAINCACHE' ? 'via chaincache' : 'self-probed' }}
            </span>
            <button mat-icon-button class="settings-btn" matTooltip="Chain settings" (click)="openEditChain(chain)">
              <mat-icon style="font-size: 18px">tune</mat-icon>
            </button>
            <button mat-stroked-button class="add-node-btn" (click)="openAddNode(chain)">
              <mat-icon style="font-size: 14px; width: 14px; height: 14px; margin-right: 4px">add</mat-icon>
              Add node
            </button>
          </div>
        </div>

        <div class="nodes-table-wrap">
          @if (chain.nodes.length === 0) {
            <div class="no-nodes">No RPC nodes configured for this chain.</div>
          } @else {
            <table>
              <thead>
                <tr>
                  <th>Health</th>
                  <th>Endpoint</th>
                  <th>Block</th>
                  <th>Lag</th>
                  <th>Last seen</th>
                  <th>Controls</th>
                </tr>
              </thead>
              <tbody>
                @for (node of chain.nodes; track node.id) {
                  <tr [class.stopped-row]="!node.enabled">
                    <td>
                      <div class="health-row">
                        <span class="health-dot" [class.healthy]="node.healthy && node.enabled"
                              [class.unhealthy]="!node.healthy && node.enabled"
                              [class.unknown]="!node.enabled"></span>
                        @if (!node.enabled) {
                          <span class="health-label unknown">Stopped</span>
                        } @else if (node.syncing) {
                          <span class="syncing-badge"><mat-icon>sync</mat-icon> Syncing</span>
                        } @else if (!node.lastCheckedAt) {
                          <span class="health-label unknown">Pending</span>
                        } @else if (node.healthy) {
                          <span class="health-label healthy">Healthy</span>
                        } @else {
                          <span class="health-label unhealthy">Unhealthy</span>
                        }
                      </div>
                      @if (node.consecutiveFailures > 0 && node.enabled) {
                        <div class="failures-text">{{ node.consecutiveFailures }} failures</div>
                      }
                    </td>
                    <td>
                      <div class="node-url" [matTooltip]="node.url">{{ node.url }}</div>
                      @if (node.label) {
                        <div class="node-label">{{ node.label }}</div>
                      }
                      @if (node.kind === 'CHAINCACHE') {
                        <div class="kind-badge chaincache">
                          <mat-icon>bolt</mat-icon> chaincache
                          <button mat-icon-button class="expand-btn" style="width: 20px; height: 20px; line-height: 20px"
                                  [matTooltip]="isExpanded(node.id) ? 'Hide capabilities' : 'Show capabilities'"
                                  (click)="toggleExpanded(node.id)">
                            <mat-icon style="font-size: 16px; width: 16px; height: 16px">
                              {{ isExpanded(node.id) ? 'expand_less' : 'expand_more' }}
                            </mat-icon>
                          </button>
                        </div>
                      }
                    </td>
                    <td>
                      @if (node.latestBlockNumber != null) {
                        <span class="block-num">{{ node.latestBlockNumber | number }}</span>
                      } @else {
                        <span class="last-seen">—</span>
                      }
                    </td>
                    <td>
                      @if (node.lagFromBest != null) {
                        <span class="lag-chip"
                              [class.ok]="node.lagFromBest <= 1"
                              [class.warn]="node.lagFromBest === 2"
                              [class.crit]="node.lagFromBest > 2">
                          +{{ node.lagFromBest }}
                        </span>
                      } @else {
                        <span class="last-seen">—</span>
                      }
                    </td>
                    <td>
                      @if (node.lastSuccessAt) {
                        <span class="last-seen">{{ formatRelative(node.lastSuccessAt) }}</span>
                      } @else {
                        <span class="last-seen">Never</span>
                      }
                    </td>
                    <td>
                      <div class="action-row">
                        <mat-slide-toggle
                          [checked]="node.enabled"
                          [matTooltip]="node.enabled ? 'Stop this node' : 'Start this node'"
                          (change)="toggleEnabled(chain, node, $event.checked)">
                        </mat-slide-toggle>

                        <button class="exclusive-btn" [class.active]="node.exclusive"
                                [matTooltip]="node.exclusive ? 'Clear exclusive (resume normal routing)' : 'Set exclusive (only use this node)'"
                                (click)="toggleExclusive(chain, node)">
                          <mat-icon>push_pin</mat-icon>
                          {{ node.exclusive ? 'Pinned' : 'Pin' }}
                        </button>

                        <button mat-icon-button matTooltip="Edit node"
                                (click)="openEditNode(chain, node)">
                          <mat-icon style="font-size: 18px">edit</mat-icon>
                        </button>

                        @if (node.kind === 'CHAINCACHE') {
                          <button mat-icon-button matTooltip="Re-probe capabilities"
                                  (click)="refreshCapabilities(chain, node)">
                            <mat-icon style="font-size: 18px">sync</mat-icon>
                          </button>
                        }

                        <button mat-icon-button class="delete-btn"
                                matTooltip="Remove this node"
                                (click)="deleteNode(chain, node)">
                          <mat-icon style="font-size: 18px">delete_outline</mat-icon>
                        </button>
                      </div>
                    </td>
                  </tr>
                  @if (node.kind === 'CHAINCACHE' && isExpanded(node.id)) {
                    <tr class="capabilities-row">
                      <td colspan="6">
                        @if (node.capabilities) {
                          <div class="capabilities-panel">
                            <div class="capability-item">
                              <div class="label">Finality model</div>
                              <div class="value">{{ node.capabilities.finalityModel || '—' }}</div>
                            </div>
                            <div class="capability-item">
                              <div class="label">Safe / Finalized confirmations</div>
                              <div class="value">{{ node.capabilities.safeConfirmations ?? '—' }} / {{ node.capabilities.finalizedConfirmations ?? '—' }}</div>
                            </div>
                            <div class="capability-item">
                              <div class="label">Durable stream</div>
                              <div class="value" [class.yes]="node.capabilities.durableStreamAvailable" [class.no]="!node.capabilities.durableStreamAvailable">
                                {{ node.capabilities.durableStreamAvailable ? 'Available' : 'Unavailable' }}
                              </div>
                            </div>
                            <div class="capability-item">
                              <div class="label">Address trace</div>
                              <div class="value">{{ node.capabilities.addressTraceCapability || 'Unknown' }}</div>
                            </div>
                            <div class="capability-item">
                              <div class="label">debug_* configured</div>
                              <div class="value" [class.yes]="node.capabilities.debugApiConfiguredOnAnyNode" [class.no]="!node.capabilities.debugApiConfiguredOnAnyNode">
                                {{ node.capabilities.debugApiConfiguredOnAnyNode ? 'Yes' : 'No' }}
                              </div>
                            </div>
                            <div class="capability-item">
                              <div class="label">Probed</div>
                              <div class="value">{{ node.capabilities.probedAt ? formatRelative(node.capabilities.probedAt) : '—' }}</div>
                            </div>
                          </div>
                        } @else {
                          <div class="capabilities-empty">
                            No capability data yet — the last probe failed or hasn't run. Try "Re-probe capabilities".
                          </div>
                        }
                        <div class="capabilities-actions">
                          @if (chaincheckUrl) {
                            <a class="chaincheck-link" [href]="chaincheckUrl" target="_blank" rel="noopener">
                              <mat-icon>open_in_new</mat-icon> View in chaincheck
                            </a>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          }
        </div>
      </div>
    }
  `,
})
export class NetworkNodesComponent implements OnInit, OnDestroy {
  private readonly chainService = inject(ChainService);
  private readonly snackBar     = inject(MatSnackBar);
  private readonly dialog       = inject(MatDialog);

  readonly chains  = signal<ChainHealth[]>([]);
  readonly loading = signal(false);
  readonly loadError = signal(false);

  /** Full ChainConfig rows (finalityModel/avgBlockSeconds/finalitySource/etc.) — health() only
   *  returns the health-dashboard shape, so this is fetched separately and joined by id. */
  private readonly chainConfigs = signal<ChainConfig[]>([]);

  private readonly expandedNodeIds = signal<Set<string>>(new Set());

  readonly chaincheckUrl = environment.chaincheckUrl;

  private pollSub?: Subscription;
  private readonly refreshTrigger = new Subject<void>();

  ngOnInit(): void {
    this.chainService.listChains().subscribe({
      next: configs => this.chainConfigs.set(configs),
      error: () => {}, // finality-source chip just shows nothing if this fails; not fatal
    });

    this.pollSub = merge(timer(0, 30_000), this.refreshTrigger).pipe(
      exhaustMap(() => {
        this.loading.set(true);
        return this.chainService.getHealth().pipe(
          catchError(() => {
            this.loadError.set(true);
            return EMPTY;
          }),
          finalize(() => this.loading.set(false)),
        );
      }),
    ).subscribe((data) => {
      this.chains.set(data);
      this.loadError.set(false);
    });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    this.refreshTrigger.complete();
  }

  chainConfigOf(chain: ChainHealth) {
    return this.chainConfigs().find(c => c.id === chain.id);
  }

  finalitySourceTooltip(chain: ChainHealth): string {
    return this.chainConfigOf(chain)?.finalitySource === 'CHAINCACHE'
      ? 'SAFE/FINALIZED promotions and reorg retractions come from chaincache, push-based and gap-free'
      : 'SAFE/FINALIZED promotions come from this registry own RPC polling';
  }

  isExpanded(nodeId: string): boolean {
    return this.expandedNodeIds().has(nodeId);
  }

  toggleExpanded(nodeId: string): void {
    this.expandedNodeIds.update(ids => {
      const next = new Set(ids);
      if (next.has(nodeId)) next.delete(nodeId); else next.add(nodeId);
      return next;
    });
  }

  toggleEnabled(chain: ChainHealth, node: RpcNode, enabled: boolean) {
    const call = enabled
      ? this.chainService.enableNode(chain.id, node.id)
      : this.chainService.disableNode(chain.id, node.id);

    call.subscribe({
      next: () => {
        this.updateNode(chain.id, node.id, { enabled });
        this.snackBar.open(enabled ? 'Node started' : 'Node stopped', 'OK', { duration: 2500 });
      },
      error: () => {
        this.snackBar.open('Failed to update node', 'OK', { duration: 3000 });
        // Re-fetch to restore correct state
        this.refresh();
      },
    });
  }

  toggleExclusive(chain: ChainHealth, node: RpcNode) {
    const newValue = !node.exclusive;
    this.chainService.setExclusive(chain.id, node.id, newValue).subscribe({
      next: () => {
        this.updateNode(chain.id, node.id, { exclusive: newValue });
        this.snackBar.open(
          newValue ? 'Node pinned — all traffic routed here' : 'Node unpinned',
          'OK', { duration: 2500 });
      },
      error: () => this.snackBar.open('Failed to update pin', 'OK', { duration: 3000 }),
    });
  }

  deleteNode(chain: ChainHealth, node: RpcNode) {
    if (!confirm(`Remove node "${node.url}"?\n\nThis cannot be undone.`)) return;

    this.chainService.deleteNode(chain.id, node.id).subscribe({
      next: () => {
        this.chains.update((chains) => chains.map((current) => current.id === chain.id
          ? { ...current, nodes: current.nodes.filter((candidate) => candidate.id !== node.id) }
          : current));
        this.snackBar.open('Node removed', 'OK', { duration: 2500 });
      },
      error: () => this.snackBar.open('Failed to remove node', 'OK', { duration: 3000 }),
    });
  }

  openAddNode(chain: ChainHealth) {
    const ref = this.dialog.open(AddNodeDialogComponent, {
      width: '480px',
      data: { chain },
    });

    ref.afterClosed().subscribe((result: RpcNodeWriteRequest | undefined) => {
      if (!result) return;
      this.chainService.addNode(chain.id, result).subscribe({
        next: node => {
          this.chains.update((chains) => chains.map((current) => current.id === chain.id
            ? { ...current, nodes: [...current.nodes, node] }
            : current));
          this.snackBar.open(
            node.kind === 'CHAINCACHE' && !node.capabilities
              ? 'Node added — chaincache capability probe failed, will retry on refresh'
              : 'Node added — health check will run shortly',
            'OK', { duration: 3500 });
        },
        error: () => this.snackBar.open('Failed to add node', 'OK', { duration: 3000 }),
      });
    });
  }

  openEditNode(chain: ChainHealth, node: RpcNode) {
    const ref = this.dialog.open(AddNodeDialogComponent, {
      width: '480px',
      data: { chain, node },
    });

    ref.afterClosed().subscribe((result: RpcNodeWriteRequest | undefined) => {
      if (!result) return;
      this.chainService.updateNode(chain.id, node.id, result).subscribe({
        next: updated => {
          this.updateNode(chain.id, node.id, updated);
          this.snackBar.open('Node updated', 'OK', { duration: 2500 });
        },
        error: () => this.snackBar.open('Failed to update node', 'OK', { duration: 3000 }),
      });
    });
  }

  refreshCapabilities(chain: ChainHealth, node: RpcNode) {
    this.chainService.refreshCapabilities(chain.id, node.id).subscribe({
      next: updated => {
        this.updateNode(chain.id, node.id, updated);
        this.snackBar.open(
          updated.capabilities ? 'Capabilities refreshed' : 'Probe failed — chaincache may be unreachable',
          'OK', { duration: 3000 });
      },
      error: () => this.snackBar.open('Failed to refresh capabilities', 'OK', { duration: 3000 }),
    });
  }

  openAddChain() {
    const ref = this.dialog.open(ChainConfigDialogComponent, {
      width: '520px',
      data: {},
    });

    ref.afterClosed().subscribe((result: ChainConfigCreateRequest | undefined) => {
      if (!result) return;
      this.chainService.createChain(result).subscribe({
        next: created => {
          this.chainConfigs.update(configs => [...configs, created]);
          this.snackBar.open('Chain created — add an RPC node to start using it', 'OK', { duration: 3500 });
          this.refresh();
        },
        error: () => this.snackBar.open('Failed to create chain', 'OK', { duration: 3000 }),
      });
    });
  }

  openEditChain(chain: ChainHealth) {
    const config = this.chainConfigOf(chain);
    const ref = this.dialog.open(ChainConfigDialogComponent, {
      width: '520px',
      data: { chain: config },
    });

    ref.afterClosed().subscribe((result: ChainConfigUpdateRequest | undefined) => {
      if (!result) return;
      this.chainService.updateChain(chain.id, result).subscribe({
        next: updated => {
          this.chainConfigs.update(configs => configs.map(c => c.id === chain.id ? updated : c));
          this.snackBar.open('Chain settings saved', 'OK', { duration: 2500 });
        },
        error: () => this.snackBar.open('Failed to save chain settings', 'OK', { duration: 3000 }),
      });
    });
  }

  formatRelative(iso: string): string {
    const diff = Date.now() - new Date(iso).getTime();
    const s = Math.floor(diff / 1000);
    if (s < 60) return `${s}s ago`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    return `${h}h ago`;
  }

  refresh(): void {
    this.refreshTrigger.next();
  }

  private updateNode(chainId: string, nodeId: string, changes: Partial<RpcNode>): void {
    this.chains.update((chains) => chains.map((chain) => chain.id === chainId
      ? {
          ...chain,
          nodes: chain.nodes.map((node) => node.id === nodeId ? { ...node, ...changes } : node),
        }
      : chain));
  }
}
