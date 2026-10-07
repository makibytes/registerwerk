import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DataTableComponent, PageHeaderComponent, TableColumn } from '@registerwerk/ui';
import { AuthService } from '../../../core/auth/auth.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  CreatedTravelRulePeer, RegisterPeerRequest, TravelRulePeer, TravelRulePeerService, peerPath,
} from '../../../core/api/travel-rule-peer.service';
import { openStepUp } from '../../../shared/components/step-up/open-step-up';

/**
 * Travel Rule peers (VASPs allowed to post to the inbox) and their HMAC credentials. Registering an
 * existing peer rotates its key but never reactivates a DISABLED one: that is the explicit Enable
 * action, a separate four-eyes step. Only registry administrators may change peers (the backend is
 * the boundary); compliance officers see the list.
 */
@Component({
  selector: 'app-travel-rule-peers',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatTooltipModule, DataTableComponent, PageHeaderComponent,
  ],
  styles: [`
    .key-box {
      display: block; padding: 10px 12px; margin: 8px 0; word-break: break-all;
      font-family: 'IBM Plex Mono', monospace; font-size: 12px;
      color: var(--rw-code-fg); background: var(--rw-code-bg); border-radius: var(--rw-radius);
    }
    .warn { color: var(--rw-text-warning); font-size: 13px; }
    .status-disabled { color: var(--rw-text-danger); font-weight: 600; }
    .status-active { color: var(--rw-text-success); font-weight: 600; }
  `],
  template: `
    <app-page-header
      title="Travel Rule peers"
      subtitle="VASPs that may post Travel Rule messages to the inbox, with their HMAC credentials">
      @if (canManage) {
        <button type="button" mat-raised-button color="primary" (click)="openRegister()">
          <mat-icon>add</mat-icon>
          Register peer
        </button>
      }
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="peers"
      [state]="state"
      (retry)="reload()"
      filterPlaceholder="Filter by VASP id, name, LEI…"
      emptyMessage="No peers are registered. Register the VASPs you exchange Travel Rule messages with."
      [actionsTemplate]="canManage ? rowActions : undefined">
    </rw-data-table>

    <ng-template #rowActions let-peer>
      @if (peer.status === 'DISABLED') {
        <button type="button" mat-stroked-button color="primary" (click)="enable(peer)"
                matTooltip="Re-activate this peer (step-up and a second approver). Its key is unchanged.">
          Enable
        </button>
      } @else {
        <button type="button" mat-stroked-button color="warn" (click)="disable(peer)"
                matTooltip="Stop accepting messages from this peer (step-up and a second approver).">
          Disable
        </button>
      }
    </ng-template>

    <ng-template #registerDialog>
      <h2 mat-dialog-title>Register or rotate a peer</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <p class="warn">
          A new HMAC key is issued and shown once. Registering an existing peer rotates its key but does
          not re-activate a disabled peer.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>VASP id</mat-label>
          <input matInput [(ngModel)]="form.vaspId" maxlength="255" placeholder="did:example:casp" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal name (optional)</mat-label>
          <input matInput [(ngModel)]="form.legalName" maxlength="1000" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>LEI (optional)</mat-label>
          <input matInput [(ngModel)]="form.lei" maxlength="20" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button type="button" mat-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!form.vaspId.trim()" (click)="register()">
          Continue
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #keyDialog>
      <h2 mat-dialog-title>HMAC key for {{ created?.peer?.vaspId }}</h2>
      <mat-dialog-content>
        <p class="warn">Copy the key now and hand it to the peer over a secure channel. It is never shown again.</p>
        <code class="key-box">{{ created?.hmacKey }}</code>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button type="button" mat-button (click)="copyKey()">Copy key</button>
        <button type="button" mat-raised-button color="primary" mat-dialog-close>Done</button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class TravelRulePeersComponent implements OnInit {
  private readonly service = inject(TravelRulePeerService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  @ViewChild('registerDialog') registerDialogTpl!: TemplateRef<unknown>;
  @ViewChild('keyDialog') keyDialogTpl!: TemplateRef<unknown>;

  peers: TravelRulePeer[] = [];
  state: AsyncSectionStatus = 'pending';
  created: CreatedTravelRulePeer | null = null;
  form = { vaspId: '', legalName: '', lei: '' };

  /** Register / disable / enable are REGISTRY_ADMIN only on the backend. */
  readonly canManage = this.auth.hasRole('REGISTRY_ADMIN');

  readonly columns: TableColumn[] = [
    { key: 'vaspId', header: 'VASP id', cell: (p: TravelRulePeer) => p.vaspId, type: 'mono', sortable: true },
    { key: 'legalName', header: 'Legal name', cell: (p: TravelRulePeer) => p.legalName ?? '', sortable: true },
    { key: 'lei', header: 'LEI', cell: (p: TravelRulePeer) => p.lei ?? '', type: 'mono' },
    { key: 'status', header: 'Status', cell: (p: TravelRulePeer) => p.status, type: 'badge', sortable: true },
    { key: 'createdAt', header: 'Registered', cell: (p: TravelRulePeer) => p.createdAt, type: 'date', sortable: true },
  ];

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.state = this.peers.length ? 'updating' : 'pending';
    this.cdr.markForCheck();
    this.service.list().subscribe({
      next: (peers) => {
        this.peers = peers;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = this.peers.length ? 'stale' : 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openRegister(): void {
    this.form = { vaspId: '', legalName: '', lei: '' };
    this.dialog.open(this.registerDialogTpl, { width: '500px', maxWidth: '95vw' });
  }

  /** The step-up approval is bound to this exact body, so build it once and send what was approved. */
  register(): void {
    const body: RegisterPeerRequest = { vaspId: this.form.vaspId.trim() };
    if (this.form.legalName.trim()) body.legalName = this.form.legalName.trim();
    if (this.form.lei.trim()) body.lei = this.form.lei.trim();
    this.dialog.closeAll();
    openStepUp(this.dialog, {
      requireDualControl: true,
      reason: `Register Travel Rule peer ${body.vaspId}`,
      action: 'TRAVEL_RULE_PEER_REGISTER',
      target: 'POST /api/v1/compliance/travel-rule/peers',
      targetBody: body,
    }).subscribe((tokens) => {
      if (!tokens?.stepUpToken || !tokens.dualControlToken) return;
      this.service.register(body, { stepUpToken: tokens.stepUpToken, dualControlToken: tokens.dualControlToken }).subscribe({
        next: (created) => {
          this.created = created;
          this.dialog.open(this.keyDialogTpl, { width: '520px', maxWidth: '95vw', disableClose: true });
          this.reload();
        },
        error: (err) => this.fail(err, 'The peer could not be registered.'),
      });
    });
  }

  disable(peer: TravelRulePeer): void {
    this.change(peer, 'disable', 'TRAVEL_RULE_PEER_DISABLE', 'DELETE', `Disabled ${peer.vaspId}.`);
  }

  /** Re-registering never reactivates a peer; this is the only way back. */
  enable(peer: TravelRulePeer): void {
    this.change(peer, 'enable', 'TRAVEL_RULE_PEER_ENABLE', 'POST', `Enabled ${peer.vaspId}.`);
  }

  copyKey(): void {
    const key = this.created?.hmacKey;
    if (key) void navigator.clipboard?.writeText(key).then(() => this.snackBar.open('Key copied.', 'OK', { duration: 2500 }));
  }

  private change(peer: TravelRulePeer, verb: 'disable' | 'enable', action: string, method: 'DELETE' | 'POST', done: string): void {
    const path = peerPath(peer.vaspId);
    openStepUp(this.dialog, {
      requireDualControl: true,
      reason: `${verb === 'enable' ? 'Enable' : 'Disable'} Travel Rule peer ${peer.vaspId}`,
      action,
      target: `${method} ${verb === 'enable' ? `${path}/enable` : path}`,
      // POST /enable sends an empty JSON object; DELETE sends no body.
      ...(verb === 'enable' ? { targetBody: {} } : {}),
    }).subscribe((tokens) => {
      if (!tokens?.stepUpToken || !tokens.dualControlToken) return;
      const t = { stepUpToken: tokens.stepUpToken, dualControlToken: tokens.dualControlToken };
      (verb === 'enable' ? this.service.enable(peer.vaspId, t) : this.service.disable(peer.vaspId, t)).subscribe({
        next: () => {
          this.snackBar.open(done, 'OK', { duration: 3500 });
          this.reload();
        },
        error: (err) => this.fail(err, `The peer could not be ${verb === 'enable' ? 'enabled' : 'disabled'}.`),
      });
    });
  }

  private fail(err: { error?: { message?: string } }, fallback: string): void {
    this.snackBar.open(err?.error?.message ?? fallback, 'OK', { duration: 6000 });
    this.cdr.markForCheck();
  }
}
