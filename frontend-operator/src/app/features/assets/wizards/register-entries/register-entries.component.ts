import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DecimalPipe, NgTemplateOutlet } from '@angular/common';
import {
  HolderChangeRequest, INSTRUCTING_PARTIES, InstructingParty, RegisterEntry, RegisterEntryService,
} from '../../../../core/api/register-entry.service';
import { StepUpDialogComponent, StepUpDialogResult } from '../../../../shared/components/step-up/step-up-dialog.component';
import { DualControlTokens } from '../../../../core/api/dual-control-headers';

/**
 * T3-13: operator-executed register-entry changes. The operator adds entries and changes §17(2)
 * attributes against a recorded instruction (party + reference); clearing a right or restriction needs
 * an explicit flag and a second approver. Issuer change requests are executed (4-eyes) or rejected here.
 */
@Component({
  selector: 'app-register-entries',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatSelectModule, MatTooltipModule, DatePipe, DecimalPipe, NgTemplateOutlet],
  template: `
    <section class="re-shell" aria-labelledby="re-title">
      <div class="re-header">
        <h3 id="re-title" class="re-title">Register entries</h3>
        <div>
          @if (canMutate) {
            <button type="button" mat-stroked-button (click)="openAdd()">
              <mat-icon>person_add</mat-icon> Add register entry
            </button>
          }
          <button type="button" mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
        </div>
      </div>
      <p class="dimmed small">
        Entries are made by the operator against a recorded instruction. On a chain-deployed asset a manual entry is
        only a wallet mapping (nominal 0); the balance comes from the chain.
      </p>

      @if (requests.length > 0) {
        <h4 class="re-sub">Issuer requests</h4>
        <div class="re-table">
          @for (r of requests; track r.id) {
            <div class="re-row">
              <span>{{ r.requestType === 'ADD_HOLDER' ? 'New entry' : 'Change §17(2)' }}</span>
              <span class="small dimmed">{{ instructionLabel(r.instructingParty) }} · {{ r.instructionReference }}</span>
              <span class="small dimmed">{{ r.requestedAt | date:'short' }}</span>
              <span class="status" [class]="r.status.toLowerCase()">{{ r.status }}</span>
              <div class="row-actions">
                @if (r.status === 'REQUESTED' && canMutate) {
                  <button type="button" mat-icon-button color="primary" matTooltip="Execute (4-eyes)" (click)="execute(r)">
                    <mat-icon>check_circle</mat-icon>
                  </button>
                  <button type="button" mat-icon-button color="warn" matTooltip="Reject" (click)="openReject(r)">
                    <mat-icon>cancel</mat-icon>
                  </button>
                }
                @if (r.decisionReason) {
                  <span class="small dimmed" [matTooltip]="r.decisionReason">Reason ⓘ</span>
                }
              </div>
            </div>
          }
        </div>
      }

      <h4 class="re-sub">Active entries</h4>
      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:16px">Loading…</p>
      } @else if (entries.length === 0) {
        <p class="dimmed" style="text-align:center;padding:16px">No register entries.</p>
      } @else {
        <div class="re-table">
          @for (e of entries; track e.id) {
            <div class="re-row entries">
              <span class="small mono">{{ e.walletAddress }}</span>
              <span class="small dimmed">{{ e.investorId }}</span>
              <span>{{ e.nominalAmount | number:'1.0-2' }}</span>
              <span class="small">
                {{ e.entryType === 'INDIVIDUAL' ? 'Single entry' : 'Collective' }}
                @if (e.thirdPartyRights) { <mat-icon class="flag" matTooltip="Third-party rights: {{ e.thirdPartyRights }}">gavel</mat-icon> }
                @if (e.disposalRestrictions) { <mat-icon class="flag" matTooltip="Disposal restrictions: {{ e.disposalRestrictions }}">lock</mat-icon> }
              </span>
              <div class="row-actions">
                @if (e.entryType === 'INDIVIDUAL' && canMutate) {
                  <button type="button" mat-icon-button matTooltip="Change §17(2) attributes (4-eyes)" (click)="openEdit(e)">
                    <mat-icon>edit</mat-icon>
                  </button>
                }
              </div>
            </div>
          }
        </div>
      }
    </section>

    <ng-template #addTpl>
      <h2 mat-dialog-title>Add register entry</h2>
      <mat-dialog-content class="dlg">
        <mat-form-field appearance="outline">
          <mat-label>Investor legal entity ID</mat-label>
          <input matInput [(ngModel)]="form.investorId" autocomplete="off">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Wallet address</mat-label>
          <input matInput [(ngModel)]="form.walletAddress" autocomplete="off">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Nominal amount (0 on a chain-deployed asset)</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="form.nominalAmount">
        </mat-form-field>
        <mat-checkbox [(ngModel)]="form.singleEntry">Single entry (Einzeleintragung, §8/§17 eWpG)</mat-checkbox>
        @if (form.singleEntry) {
          <mat-checkbox [(ngModel)]="form.isConsumer">Holder is a consumer (§19 statements)</mat-checkbox>
          <mat-form-field appearance="outline">
            <mat-label>Third-party rights (optional)</mat-label>
            <input matInput [(ngModel)]="form.thirdPartyRights">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Disposal restrictions (optional)</mat-label>
            <input matInput [(ngModel)]="form.disposalRestrictions">
          </mat-form-field>
        }
        <ng-container [ngTemplateOutlet]="instructionTpl"></ng-container>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!addValid()" (click)="submitAdd()">Add entry</button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #editTpl>
      <h2 mat-dialog-title>Change §17(2) attributes</h2>
      <mat-dialog-content class="dlg">
        <p class="dimmed small" style="margin:0">
          A blank field means <strong>no change</strong>. To remove a right or restriction, tick the box next to it.
          Requires a second approver.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Third-party rights (current: {{ editing?.thirdPartyRights || 'none' }})</mat-label>
          <input matInput [(ngModel)]="edit.thirdPartyRights" [disabled]="edit.clearThirdPartyRights">
        </mat-form-field>
        <mat-checkbox [(ngModel)]="edit.clearThirdPartyRights" [disabled]="!editing?.thirdPartyRights">Remove third-party rights</mat-checkbox>
        <mat-form-field appearance="outline">
          <mat-label>Disposal restrictions (current: {{ editing?.disposalRestrictions || 'none' }})</mat-label>
          <input matInput [(ngModel)]="edit.disposalRestrictions" [disabled]="edit.clearDisposalRestrictions">
        </mat-form-field>
        <mat-checkbox [(ngModel)]="edit.clearDisposalRestrictions" [disabled]="!editing?.disposalRestrictions">Remove disposal restrictions</mat-checkbox>
        <mat-form-field appearance="outline">
          <mat-label>Legal-capacity note</mat-label>
          <input matInput [(ngModel)]="edit.legalCapacityNote">
        </mat-form-field>
        <ng-container [ngTemplateOutlet]="instructionTpl"></ng-container>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!editValid()" (click)="submitEdit()">Apply change</button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #rejectTpl>
      <h2 mat-dialog-title>Reject request</h2>
      <mat-dialog-content class="dlg">
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="rejectReason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!rejectReason.trim()" (click)="submitReject()">Reject</button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #instructionTpl>
      <mat-form-field appearance="outline">
        <mat-label>Instruction from</mat-label>
        <mat-select [(ngModel)]="instruction.party">
          @for (p of parties; track p.value) { <mat-option [value]="p.value">{{ p.label }}</mat-option> }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Instruction reference (file number, letter…)</mat-label>
        <input matInput [(ngModel)]="instruction.reference">
      </mat-form-field>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .re-shell { padding: 1rem 0; }
    .re-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: .5rem; }
    .re-header div { display: flex; gap: 8px; }
    .re-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .re-sub { font-size: .8125rem; font-weight: 700; margin: 1rem 0 .25rem; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', 'Courier New', monospace; overflow-wrap: anywhere; }
    .re-table { display: flex; flex-direction: column; }
    .re-row { display: grid; grid-template-columns: 1fr 2fr 1fr 110px 110px; gap: .5rem; align-items: center;
      padding: .5rem; border-bottom: 1px solid var(--rw-border); font-size: .8125rem; }
    .re-row.entries { grid-template-columns: 2fr 2fr 1fr 1.4fr 70px; }
    .row-actions { display: flex; justify-content: flex-end; gap: 4px; align-items: center; }
    .status { font-size: .6875rem; font-weight: 700; }
    .status.requested { color: #f59e0b; }
    .status.executed { color: #4ade80; }
    .status.rejected { color: #f87171; }
    .flag { font-size: 16px; height: 16px; width: 16px; vertical-align: middle; margin-left: 4px; }
    .dlg { display: flex; flex-direction: column; gap: 8px; padding-top: 8px; min-width: 420px; }
  `],
})
export class RegisterEntriesComponent implements OnInit {
  @Input() assetId!: string;
  @Input() canMutate = false;
  @ViewChild('addTpl') addTpl!: TemplateRef<unknown>;
  @ViewChild('editTpl') editTpl!: TemplateRef<unknown>;
  @ViewChild('rejectTpl') rejectTpl!: TemplateRef<unknown>;

  private readonly service = inject(RegisterEntryService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly parties = INSTRUCTING_PARTIES;
  entries: RegisterEntry[] = [];
  requests: HolderChangeRequest[] = [];
  loading = false;

  instruction: { party: InstructingParty | ''; reference: string } = { party: '', reference: '' };
  form = this.blankForm();
  editing: RegisterEntry | null = null;
  edit = { thirdPartyRights: '', disposalRestrictions: '', legalCapacityNote: '',
    clearThirdPartyRights: false, clearDisposalRestrictions: false };
  rejecting: HolderChangeRequest | null = null;
  rejectReason = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.listEntries(this.assetId).subscribe({
      next: (page) => { this.entries = page.content; this.loading = false; this.cdr.markForCheck(); },
      error: () => { this.loading = false; this.cdr.markForCheck(); },
    });
    this.service.listRequests(this.assetId).subscribe({
      next: (r) => { this.requests = r; this.cdr.markForCheck(); },
      error: () => { /* requests are optional context */ },
    });
  }

  instructionLabel(party: InstructingParty): string {
    return this.parties.find((p) => p.value === party)?.label ?? party;
  }

  private blankForm() {
    return { investorId: '', walletAddress: '', nominalAmount: 0 as number | null, singleEntry: false, isConsumer: false,
      thirdPartyRights: '', disposalRestrictions: '' };
  }

  private resetInstruction(): void {
    this.instruction = { party: '', reference: '' };
  }

  openAdd(): void {
    this.form = this.blankForm();
    this.resetInstruction();
    this.dialog.open(this.addTpl, { width: '520px' });
  }

  addValid(): boolean {
    return !!this.form.investorId.trim() && !!this.form.walletAddress.trim() && this.form.nominalAmount !== null
      && this.form.nominalAmount >= 0 && !!this.instruction.party && !!this.instruction.reference.trim();
  }

  submitAdd(): void {
    if (!this.addValid()) return;
    this.dialog.closeAll();
    this.withStepUp(false, 'REGISTER_ENTRY_CREATE', 'Create a register entry', (result) =>
      this.service.addEntry(this.assetId, {
        investorId: this.form.investorId.trim(),
        walletAddress: this.form.walletAddress.trim(),
        nominalAmount: this.form.nominalAmount,
        singleEntry: this.form.singleEntry,
        isConsumer: this.form.isConsumer,
        thirdPartyRights: this.form.thirdPartyRights.trim() || undefined,
        disposalRestrictions: this.form.disposalRestrictions.trim() || undefined,
        instructingParty: this.instruction.party as InstructingParty,
        instructionReference: this.instruction.reference.trim(),
      }, result.stepUpToken).subscribe({
        next: () => { this.snackBar.open('Register entry added.', 'Dismiss', { duration: 5000 }); this.load(); },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to add the entry.', 'Dismiss', { duration: 8000 }),
      }));
  }

  openEdit(entry: RegisterEntry): void {
    this.editing = entry;
    this.edit = { thirdPartyRights: '', disposalRestrictions: '', legalCapacityNote: '',
      clearThirdPartyRights: false, clearDisposalRestrictions: false };
    this.resetInstruction();
    this.dialog.open(this.editTpl, { width: '520px' });
  }

  editValid(): boolean {
    const changes = !!this.edit.thirdPartyRights.trim() || !!this.edit.disposalRestrictions.trim()
      || !!this.edit.legalCapacityNote.trim() || this.edit.clearThirdPartyRights || this.edit.clearDisposalRestrictions;
    return changes && !!this.instruction.party && !!this.instruction.reference.trim();
  }

  submitEdit(): void {
    const entry = this.editing;
    if (!entry || !this.editValid()) return;
    this.dialog.closeAll();
    this.withStepUp(true, 'REGISTER_ENTRY_RIGHTS_CHANGE', `Change §17(2) attributes of entry ${entry.holderReference ?? entry.id}`, (result) =>
      this.service.updateAttributes(this.assetId, entry.id, {
        thirdPartyRights: this.edit.thirdPartyRights.trim() || undefined,
        disposalRestrictions: this.edit.disposalRestrictions.trim() || undefined,
        legalCapacityNote: this.edit.legalCapacityNote.trim() || undefined,
        clearThirdPartyRights: this.edit.clearThirdPartyRights,
        clearDisposalRestrictions: this.edit.clearDisposalRestrictions,
        instructingParty: this.instruction.party as InstructingParty,
        instructionReference: this.instruction.reference.trim(),
      }, { stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken! }).subscribe({
        next: () => { this.snackBar.open('Attributes changed. Instruction and before/after recorded.', 'Dismiss', { duration: 5000 }); this.load(); },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to change the entry.', 'Dismiss', { duration: 8000 }),
      }));
  }

  execute(request: HolderChangeRequest): void {
    this.withStepUp(true, 'REGISTER_ENTRY_RIGHTS_CHANGE',
      `Execute issuer request (${this.instructionLabel(request.instructingParty)}: ${request.instructionReference})`, (result) => {
        const tokens: DualControlTokens = { stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken! };
        this.service.executeRequest(this.assetId, request.id, tokens).subscribe({
          next: () => { this.snackBar.open('Request executed.', 'Dismiss', { duration: 5000 }); this.load(); },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to execute the request.', 'Dismiss', { duration: 8000 }),
        });
      });
  }

  openReject(request: HolderChangeRequest): void {
    this.rejecting = request;
    this.rejectReason = '';
    this.dialog.open(this.rejectTpl, { width: '480px' });
  }

  submitReject(): void {
    const request = this.rejecting;
    if (!request || !this.rejectReason.trim()) return;
    this.dialog.closeAll();
    this.service.rejectRequest(this.assetId, request.id, this.rejectReason.trim()).subscribe({
      next: () => { this.snackBar.open('Request rejected.', 'Dismiss', { duration: 5000 }); this.load(); },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to reject the request.', 'Dismiss', { duration: 6000 }),
    });
  }

  private withStepUp(dual: boolean, action: string, reason: string, run: (result: StepUpDialogResult) => void): void {
    this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: dual, reason, action },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (result?.stepUpToken && (!dual || result.dualControlToken)) run(result);
    });
  }
}
