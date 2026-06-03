import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';


import { StepUpDialogComponent } from '../../../shared/components/step-up/step-up-dialog.component';
import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { HolderBlockService } from '../../../core/api/holder-block.service';
import { HolderBlock, BlockType } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-holder-blocks',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [``],
  template: `
    <app-page-header
      title="Holder Blocks (Sperrvermerk)"
      subtitle="§16 eWpG — Legal blocks on holder wallets. All creates and lifts require step-up authentication and dual control.">
      <button mat-raised-button color="warn" (click)="openCreateDialog()">
        <mat-icon>lock</mat-icon>
        Create Block
      </button>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="blocks"
      [state]="state"
      filterPlaceholder="Filter by wallet, entity, type…"
      emptyMessage="No active holder blocks."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-block>
      <button mat-stroked-button color="warn"
              (click)="liftBlock(block)"
              matTooltip="Lift block — requires step-up + dual control">
        <mat-icon>lock_open</mat-icon>
        Lift
      </button>
    </ng-template>

    <!-- Create Block Dialog -->
    <ng-template #createDialog>
      <h2 mat-dialog-title>Create Holder Block (Sperrvermerk)</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:460px;padding-top:8px">
        <div style="font-size:12px;color:var(--rw-text-secondary);padding:10px 12px;background:rgba(239,68,68,0.07);border-radius:6px;border:1px solid rgba(239,68,68,0.18)">
          This action requires <strong>step-up authentication and dual control (4-eyes)</strong>.
          The block will be confirmed in the next step.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Wallet address *</mat-label>
          <input matInput [(ngModel)]="createForm.walletAddress" placeholder="0x…" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Block type *</mat-label>
          <mat-select [(ngModel)]="createForm.blockType">
            @for (t of blockTypes; track t) {
              <mat-option [value]="t">{{ t.replace('_',' ') }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal basis *</mat-label>
          <input matInput [(ngModel)]="createForm.legalBasis"
                 placeholder="e.g. Court order Ref. XY, §16 eWpG" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Court reference (optional)</mat-label>
          <input matInput [(ngModel)]="createForm.courtRef" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Entity ID (optional)</mat-label>
          <input matInput [(ngModel)]="createForm.entityId" placeholder="UUID" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Asset ID (optional)</mat-label>
          <input matInput [(ngModel)]="createForm.assetId" placeholder="UUID" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Expires at (optional, ISO-8601)</mat-label>
          <input matInput [(ngModel)]="createForm.expiresAt" placeholder="2025-12-31T00:00:00Z" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn"
                (click)="submitCreate()"
                [disabled]="!createForm.walletAddress || !createForm.blockType || !createForm.legalBasis">
          <mat-icon>lock</mat-icon>
          Create Block
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class HolderBlocksComponent implements OnInit {
  @ViewChild('createDialog') createDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(HolderBlockService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  blocks: HolderBlock[] = [];
  state: AsyncSectionStatus = 'pending';

  readonly blockTypes: BlockType[] = [
    'PFANDRECHT', 'PFAENDUNG', 'GERICHTSBESCHLUSS', 'NACHLASSSPERRE',
    'VERFUGUNGSVERBOT', 'INSOLVENZ', 'TOD', 'REGULATORISCH',
  ];

  createForm: {
    walletAddress: string;
    blockType: BlockType | '';
    legalBasis: string;
    courtRef: string;
    entityId: string;
    assetId: string;
    expiresAt: string;
  } = { walletAddress: '', blockType: '', legalBasis: '', courtRef: '', entityId: '', assetId: '', expiresAt: '' };

  readonly columns: TableColumn[] = [
    {
      key: 'blockType',
      header: 'Block Type',
      cell: (b: HolderBlock) => b.blockType.replace('_', ' '),
      type: 'badge',
    },
    {
      key: 'walletAddress',
      header: 'Wallet Address',
      cell: (b: HolderBlock) => b.walletAddress,
      type: 'mono',
    },
    {
      key: 'entityId',
      header: 'Entity',
      cell: (b: HolderBlock) => b.entityId ?? '—',
      type: 'mono',
    },
    {
      key: 'legalBasis',
      header: 'Legal Basis',
      cell: (b: HolderBlock) => b.legalBasis,
    },
    {
      key: 'courtRef',
      header: 'Court Ref.',
      cell: (b: HolderBlock) => b.courtRef ?? '—',
    },
    {
      key: 'startsAt',
      header: 'Active Since',
      cell: (b: HolderBlock) => b.startsAt,
      type: 'date',
    },
    {
      key: 'expiresAt',
      header: 'Expires',
      cell: (b: HolderBlock) => b.expiresAt,
      type: 'date',
    },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.service.listAllActive().subscribe({
      next: (blocks) => {
        this.blocks = blocks;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openCreateDialog(): void {
    this.createForm = { walletAddress: '', blockType: '', legalBasis: '', courtRef: '', entityId: '', assetId: '', expiresAt: '' };
    this.dialog.open(this.createDialogTpl, { width: '520px' });
  }

  submitCreate(): void {
    this.dialog.closeAll();

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Create Sperrvermerk on wallet ${this.createForm.walletAddress} (§16 eWpG)`,
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;

      this.service.create({
        walletAddress: this.createForm.walletAddress,
        blockType: this.createForm.blockType as BlockType,
        legalBasis: this.createForm.legalBasis,
        courtRef: this.createForm.courtRef || undefined,
        entityId: this.createForm.entityId || undefined,
        assetId: this.createForm.assetId || undefined,
        expiresAt: this.createForm.expiresAt || undefined,
      }, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (block) => {
          this.blocks = [block, ...this.blocks];
          this.cdr.markForCheck();
          this.snackBar.open('Holder block created. Audit event recorded.', 'Dismiss', { duration: 5000 });
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to create block.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  liftBlock(block: HolderBlock): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Lift Sperrvermerk on wallet ${block.walletAddress} (§16 eWpG)`,
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;

      const reason = prompt('Lift reason (required for audit trail):');
      if (!reason) return;

      this.service.lift(block.id, reason, result.stepUpToken, result.dualControlToken!).subscribe({
        next: () => {
          this.blocks = this.blocks.filter(b => b.id !== block.id);
          this.cdr.markForCheck();
          this.snackBar.open('Block lifted. Audit event recorded.', 'Dismiss', { duration: 5000 });
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to lift block.', 'Dismiss', { duration: 6000 }),
      });
    });
  }
}
