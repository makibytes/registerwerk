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
                  <button mat-stroked-button (click)="unpause(s)" [disabled]="busy">Unpause</button>
                } @else {
                  <button mat-stroked-button (click)="pause(s)" [disabled]="busy">Pause</button>
                }
                <button mat-stroked-button (click)="mintTarget = mintTarget === s.slotId ? null : s.slotId">
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
                  <input matInput type="number" [(ngModel)]="mintForm.value" min="1" />
                </mat-form-field>
                <button mat-flat-button class="btn-accent"
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
          <input matInput type="number" [(ngModel)]="createForm.slotId" min="1" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="grow">
          <mat-label>Name (e.g. "Tranche A — 2030")</mat-label>
          <input matInput [(ngModel)]="createForm.name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Supply cap (optional)</mat-label>
          <input matInput type="number" [(ngModel)]="createForm.supplyCap" min="1" />
        </mat-form-field>
        <button mat-flat-button class="btn-accent"
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
          <input matInput type="number" [(ngModel)]="tokenOps.tokenId" min="1" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="grow">
          <mat-label>Reason / legal basis</mat-label>
          <input matInput [(ngModel)]="tokenOps.reason" placeholder="e.g. AWG §17 sanctions order ref…" />
        </mat-form-field>
        <button mat-stroked-button [disabled]="busy || !tokenOps.tokenId || !tokenOps.reason"
                (click)="freeze()">
          <mat-icon>lock</mat-icon> Freeze
        </button>
        <button mat-stroked-button [disabled]="busy || !tokenOps.tokenId" (click)="unfreeze()">
          <mat-icon>lock_open</mat-icon> Unfreeze
        </button>
      </div>
      <div class="inline-form">
        <mat-form-field appearance="outline">
          <mat-label>From token ID</mat-label>
          <input matInput type="number" [(ngModel)]="forcedForm.tokenId" min="1" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>To token ID</mat-label>
          <input matInput type="number" [(ngModel)]="forcedForm.toTokenId" min="1" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Value</mat-label>
          <input matInput type="number" [(ngModel)]="forcedForm.value" min="1" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="grow">
          <mat-label>Legal basis</mat-label>
          <input matInput [(ngModel)]="forcedForm.legalBasis" />
        </mat-form-field>
        <button mat-flat-button color="warn"
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

  createSlot(): void {
    this.run(this.slotService.createSlot(this.deploymentId, {
      slotId: String(this.createForm.slotId),
      name: this.createForm.name || undefined,
      supplyCap: this.createForm.supplyCap ? String(this.createForm.supplyCap) : undefined,
    }), 'Slot creation submitted.', () => {
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
    this.run(this.slotService.mintIntoSlot(this.deploymentId, slot.slotId, {
      toAddress: this.mintForm.toAddress.trim(),
      value: String(this.mintForm.value),
    }), `Mint into slot #${slot.slotId} submitted.`, () => {
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

  forcedTransfer(): void {
    if (!confirm('Execute a forced value transfer? This is a regulatory intervention and is fully audited.')) return;
    this.run(this.slotService.forcedValueTransfer(this.deploymentId, String(this.forcedForm.tokenId), {
      toTokenId: String(this.forcedForm.toTokenId),
      value: String(this.forcedForm.value),
      legalBasis: this.forcedForm.legalBasis,
    }), 'Forced value transfer submitted.', () => {
      this.forcedForm = { tokenId: '', toTokenId: '', value: '', legalBasis: '' };
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
