import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
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
import { ChainService } from '../../core/api/chain.service';
import { ChainHealth, RpcNode } from '../../core/models';
import { interval, Subscription } from 'rxjs';
import { switchMap, startWith } from 'rxjs/operators';
import { AddNodeDialogComponent } from './add-node-dialog.component';

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

    @keyframes spin {
      from { transform: rotate(0deg); }
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

    .chain-icon.evm   { background: rgba(98, 126, 234, 0.15); color: #627EEA; }
    .chain-icon.solana { background: rgba(153, 69, 255, 0.15); color: #9945FF; }

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
      font-size: 13px;
    }

    .loading-state {
      padding: 60px 0;
      text-align: center;
      color: var(--rw-text-muted);
    }

    .failures-text {
      font-size: 11px;
      color: #ef4444;
    }

    .stopped-row td {
      opacity: 0.55;
    }
  `],
  template: `
    <div class="page-header">
      <div>
        <h1>Network Nodes</h1>
        <p class="page-subtitle">RPC endpoint health for all configured blockchains</p>
      </div>
      @if (loading()) {
        <div class="refresh-info">
          <mat-icon>refresh</mat-icon>
          <span>Refreshing…</span>
        </div>
      } @else {
        <div class="refresh-info">
          <mat-icon style="animation: none; opacity: 0.5">schedule</mat-icon>
          <span>Auto-refreshes every 30s</span>
        </div>
      }
    </div>

    @if (chains().length === 0 && loading()) {
      <div class="loading-state">Loading node health data…</div>
    }

    @for (chain of chains(); track chain.id) {
      <div class="chain-card">
        <div class="chain-header">
          <div class="chain-icon" [class.evm]="chain.chainType === 'EVM'" [class.solana]="chain.chainType === 'SOLANA'">
            {{ chain.chainType === 'SOLANA' ? '◎' : 'Ξ' }}
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

                        <button mat-icon-button class="delete-btn"
                                matTooltip="Remove this node"
                                (click)="deleteNode(chain, node)">
                          <mat-icon style="font-size: 18px">delete_outline</mat-icon>
                        </button>
                      </div>
                    </td>
                  </tr>
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

  private pollSub?: Subscription;

  ngOnInit() {
    this.pollSub = interval(30_000).pipe(
      startWith(0),
      switchMap(() => {
        this.loading.set(true);
        return this.chainService.getHealth();
      }),
    ).subscribe({
      next: data => {
        this.chains.set(data);
        this.loading.set(false);
      },
      error: err => {
        console.error('Failed to load RPC health data:', err);
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy() {
    this.pollSub?.unsubscribe();
  }

  toggleEnabled(chain: ChainHealth, node: RpcNode, enabled: boolean) {
    const call = enabled
      ? this.chainService.enableNode(chain.id, node.id)
      : this.chainService.disableNode(chain.id, node.id);

    call.subscribe({
      next: () => {
        node.enabled = enabled;
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
        node.exclusive = newValue;
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
        chain.nodes = chain.nodes.filter(n => n.id !== node.id);
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

    ref.afterClosed().subscribe((result: { url: string; label: string } | undefined) => {
      if (!result) return;
      this.chainService.addNode(chain.id, result.url, result.label).subscribe({
        next: node => {
          chain.nodes.push(node);
          this.snackBar.open('Node added — health check will run shortly', 'OK', { duration: 3000 });
        },
        error: () => this.snackBar.open('Failed to add node', 'OK', { duration: 3000 }),
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

  private refresh() {
    this.chainService.getHealth().subscribe(data => this.chains.set(data));
  }
}
