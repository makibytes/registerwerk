import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { StepUpDialogComponent, StepUpDialogResult } from '../../../../shared/components/step-up/step-up-dialog.component';
import { DecimalPipe } from '@angular/common';
import { SlotService } from '../../../../core/api/slot.service';
import { AssetSlot } from '../../../../core/models';

/**
 * ERC-3525 slot administration: create slots (bond tranches), pause/unpause them,
 * mint value into a slot, and run the regulatory token operations (freeze /
 * forced value transfer) that eWpG §17 / GwG §40 require the registry to be able
 * to execute.
 */
@Component({
  selector: 'app-slot-admin',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatIconModule, DecimalPipe],
  template: `
    <div class="slot-shell">
      <header class="slot-header">
        <span class="badge">ERC-3525 SLOTS</span>
        <h2 class="slot-title">Slot administration</h2>
        <p class="hint">
          Slots are the tranches of a semi-fungible bond — every token carries a slot and a
          value. Pausing a slot halts all value transfers within it; minting into a slot
          issues new value to a holder's token.
        </p>
      </header>

      <!-- Slot list -->
      @if (slots.length === 0) {
        <p class="empty-note">No slots created yet.</p>
      } @else {
        <div class="slot-table">
          <div class="s-row header">
            <span>Slot</span>
            <span>Name</span>
            <span>Supply cap</span>
            <span>Status</span>
            <span>Actions</span>
          </div>
          @for (s of slots; track s.id) {
            <div class="s-row">
              <span class="mono">#{{ s.slotId }}</span>
              <span>{{ s.name || '—' }}</span>
              <span class="mono">{{ s.supplyCap ? (+s.supplyCap | number) : '∞' }}</span>
              <span [class.paused]="s.paused">{{ s.paused ? 'PAUSED' : 'ACTIVE' }}</span>
              <span class="actions">
                @if (s.paused) {
                  <button type="button" mat-stroked-button (click)="unpause(s)" [disabled]="busy">Unpause</button>
                } @else {
                  <button type="button" mat-stroked-button (click)="pause(s)" [disabled]="busy">Pause</button>
                }
                <button type="button" mat-stroked-button (click)="mintTarget = mintTarget === s.slotId ? null : s.slotId">
                  Mint…
                </button>
              </span>
            </div>
            @if (mintTarget === s.slotId) {
              <div class="inline-form">
                <mat-form-field appearance="outline" class="grow">
                  <mat-label>Recipient address</mat-label>
                  <input matInput [(ngModel)]="mintForm.toAddress" placeholder="0x…" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Value</mat-label>
                  <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="mintForm.value" />
                </mat-form-field>
                <button type="button" mat-flat-button class="btn-accent"
                        [disabled]="busy || !mintForm.toAddress || !mintForm.value"
                        (click)="mint(s)">
                  <mat-icon>token</mat-icon> Mint into slot
                </button>
              </div>
            }
          }
        </div>
      }

      <!-- Create slot -->
      <h3 class="section-title">Create slot</h3>
      <div class="inline-form">
        <mat-form-field appearance="outline">
          <mat-label>Slot ID</mat-label>
          <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="createForm.slotId" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="grow">
          <mat-label>Name (e.g. "Tranche A — 2030")</mat-label>
          <input matInput [(ngModel)]="createForm.name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Supply cap (optional)</mat-label>
          <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="createForm.supplyCap" />
        </mat-form-field>
        <button type="button" mat-flat-button class="btn-accent"
                [disabled]="busy || !createForm.slotId"
                (click)="createSlot()">
          <mat-icon>add</mat-icon> Create
        </button>
      </div>

      <!-- Regulatory token operations -->
      <h3 class="section-title">Token operations (regulatory)</h3>
      <p class="hint">
        Freeze blocks a single token (eWpG §17 / GwG §40); a forced value transfer moves
        value between tokens on a documented legal basis — both are audited on-chain
        operations.
      </p>
      <div class="inline-form">
        <mat-form-field appearance="outline">
          <mat-label>Token ID</mat-label>
          <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="tokenOps.tokenId" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="grow">
          <mat-label>Reason / legal basis</mat-label>
          <input matInput [(ngModel)]="tokenOps.reason" placeholder="e.g. AWG §17 sanctions order ref…" />
        </mat-form-field>
        <button type="button" mat-stroked-button [disabled]="busy || !tokenOps.tokenId || !tokenOps.reason"
                (click)="freeze()">
          <mat-icon>lock</mat-icon> Freeze
        </button>
        <button type="button" mat-stroked-button [disabled]="busy || !tokenOps.tokenId" (click)="unfreeze()">
          <mat-icon>lock_open</mat-icon> Unfreeze
        </button>
      </div>
      <div class="inline-form">
        <mat-form-field appearance="outline">
          <mat-label>From token ID</mat-label>
          <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="forcedForm.tokenId" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>To token ID</mat-label>
          <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="forcedForm.toTokenId" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Value</mat-label>
          <input matInput type="text" inputmode="numeric" autocomplete="off" [(ngModel)]="forcedForm.value" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="grow">
          <mat-label>Legal basis</mat-label>
          <input matInput [(ngModel)]="forcedForm.legalBasis" />
        </mat-form-field>
        <button type="button" mat-flat-button color="warn"
                [disabled]="busy || !forcedForm.tokenId || !forcedForm.toTokenId || !forcedForm.value || !forcedForm.legalBasis"
                (click)="forcedTransfer()">
          <mat-icon>gavel</mat-icon> Forced transfer
        </button>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      --accent: var(--rw-accent, #F59E0B);
      --border: rgba(245,158,11,.18);
    }
    .slot-shell { padding: 1.5rem 0; }
    .slot-header { margin-bottom: 1rem; }
    .badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .625rem;
      letter-spacing: .2em;
      color: var(--accent);
      background: rgba(245,158,11,.1);
      border: 1px solid var(--border);
      border-radius: 2px;
      padding: .2rem .625rem;
    }
    .slot-title { margin: .5rem 0 .25rem; font-size: 1.125rem; }
    .section-title { margin: 1.5rem 0 .5rem; font-size: .9375rem; }
    .hint { font-size: .8125rem; color: var(--rw-text-secondary, #7b8aac); margin: 0 0 .75rem; max-width: 640px; }
    .empty-note { color: var(--rw-text-secondary, #7b8aac); font-size: .875rem; }
    .slot-table { border: 1px solid var(--border); border-radius: 4px; }
    .s-row {
      display: grid;
      grid-template-columns: 90px 1fr 130px 90px 240px;
      gap: .75rem;
      align-items: center;
      padding: .5rem .75rem;
      font-size: .8125rem;
      border-bottom: 1px solid rgba(255,255,255,.04);
    }
    .s-row.header { font-weight: 600; font-size: .75rem; color: var(--rw-text-secondary, #7b8aac); }
    .s-row:last-child { border-bottom: none; }
    .mono { font-family: 'IBM Plex Mono', monospace; }
    .paused { color: #f87171; font-weight: 600; }
    .actions { display: flex; gap: .5rem; }
    .inline-form { display: flex; gap: .75rem; align-items: baseline; flex-wrap: wrap; padding: .5rem 0; }
    .grow { flex: 1 1 220px; }
    .btn-accent { background: var(--accent); color: #0e1124; }
  `],
})
export class SlotAdminComponent implements OnInit {
  @Input({ required: true }) deploymentId!: string;

  private readonly slotService = inject(SlotService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);

  slots: AssetSlot[] = [];
  busy = false;
  mintTarget: string | null = null;

  createForm = { slotId: '', name: '', supplyCap: '' };
  mintForm = { toAddress: '', value: '' };
  tokenOps = { tokenId: '', reason: '' };
  forcedForm = { tokenId: '', toTokenId: '', value: '', legalBasis: '' };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.slotService.getSlots(this.deploymentId).subscribe({
      next: (slots) => {
        this.slots = slots;
        this.cdr.markForCheck();
      },
      error: () => {
        this.cdr.markForCheck();
      },
    });
  }

  /** Step-up + second approver; the approver's token is bound to this exact request (target + body). */
  private withDualControl(action: string, reason: string, target: string, targetBody: Record<string, unknown>,
                          submit: (tokens: { stepUpToken: string; dualControlToken: string }) => Observable<unknown>,
                          successMsg: string, onSuccess?: () => void): void {
    this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: true, reason, action, target, targetBody },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken || !result.dualControlToken) return;
      this.run(submit({ stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken }), successMsg, onSuccess);
    });
  }

  createSlot(): void {
    const body = {
      slotId: String(this.createForm.slotId),
      ...(this.createForm.name ? { name: this.createForm.name } : {}),
      ...(this.createForm.supplyCap ? { supplyCap: String(this.createForm.supplyCap) } : {}),
    };
    // ERC-3525 slot creation (carries the supply cap): step-up + second approver.
    this.withDualControl('ERC3525_SLOT_CREATE', `Create ERC-3525 slot #${body.slotId}`,
      `POST /api/v1/deployments/${this.deploymentId}/slots`, body,
      (tokens) => this.slotService.createSlot(this.deploymentId, body, tokens),
      'Slot creation submitted.', () => {
        this.createForm = { slotId: '', name: '', supplyCap: '' };
        this.load();
      });
  }

  pause(slot: AssetSlot): void {
    this.run(this.slotService.pauseSlot(this.deploymentId, slot.slotId), `Pause of slot #${slot.slotId} submitted.`, () => this.load());
  }

  unpause(slot: AssetSlot): void {
    this.run(this.slotService.unpauseSlot(this.deploymentId, slot.slotId), `Unpause of slot #${slot.slotId} submitted.`, () => this.load());
  }

  mint(slot: AssetSlot): void {
    const body = { toAddress: this.mintForm.toAddress.trim(), value: String(this.mintForm.value) };
    // Minting value into a slot is the SFT counterpart of the issuer mint: step-up + second approver.
    this.withDualControl('ERC3525_SLOT_MINT', `Mint ${body.value} into slot #${slot.slotId} for ${body.toAddress}`,
      `POST /api/v1/deployments/${this.deploymentId}/slots/${slot.slotId}/mint`, body,
      (tokens) => this.slotService.mintIntoSlot(this.deploymentId, slot.slotId, body, tokens),
      `Mint into slot #${slot.slotId} submitted.`, () => {
        this.mintForm = { toAddress: '', value: '' };
        this.mintTarget = null;
      });
  }

  freeze(): void {
    this.run(this.slotService.freezeToken(this.deploymentId, String(this.tokenOps.tokenId), this.tokenOps.reason),
        `Freeze of token #${this.tokenOps.tokenId} submitted.`);
  }

  unfreeze(): void {
    this.run(this.slotService.unfreezeToken(this.deploymentId, String(this.tokenOps.tokenId)),
        `Unfreeze of token #${this.tokenOps.tokenId} submitted.`);
  }

  private isUint(v: unknown): boolean {
    return /^[1-9]\d*$/.test(String(v ?? '').trim());
  }

  forcedTransfer(): void {
    const f = this.forcedForm;
    if (!this.isUint(f.tokenId) || !this.isUint(f.toTokenId) || !this.isUint(f.value)) {
      this.snackBar.open('Token IDs and value must be whole numbers greater than 0.', 'Dismiss', { duration: 5000 });
      return;
    }
    if (f.legalBasis.trim().length < 10) {
      this.snackBar.open('The legal basis needs at least 10 characters.', 'Dismiss', { duration: 5000 });
      return;
    }
    if (!confirm('Execute a forced value transfer? This is a regulatory intervention and is fully audited.')) return;
    // ERC-3525 forced value transfer: step-up + second approver.
    this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Forced value transfer ${f.value} from token #${f.tokenId} to token #${f.toTokenId}`,
        action: 'ERC3525_FORCED_VALUE_TRANSFER_EWG24',
        target: `POST /api/v1/deployments/${this.deploymentId}/tokens/${String(f.tokenId).trim()}/forced-value-transfer`,
        targetBody: { toTokenId: String(f.toTokenId).trim(), value: String(f.value).trim(), legalBasis: f.legalBasis.trim() },
      },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken || !result.dualControlToken) return;
      this.run(this.slotService.forcedValueTransfer(this.deploymentId, String(f.tokenId).trim(), {
        toTokenId: String(f.toTokenId).trim(),
        value: String(f.value).trim(),
        legalBasis: f.legalBasis.trim(),
      }, { stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken }), 'Forced value transfer submitted.', () => {
        this.forcedForm = { tokenId: '', toTokenId: '', value: '', legalBasis: '' };
      });
    });
  }

  private run(obs: Observable<unknown>, successMsg: string, onSuccess?: () => void): void {
    this.busy = true;
    this.cdr.markForCheck();
    obs.subscribe({
      next: () => {
        this.busy = false;
        onSuccess?.();
        this.snackBar.open(successMsg, 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy = false;
        this.snackBar.open(err?.error?.message ?? 'Operation failed.', 'Dismiss', { duration: 6000 });
        this.cdr.markForCheck();
      },
    });
  }
}
