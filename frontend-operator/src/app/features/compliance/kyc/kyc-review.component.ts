import { kycRejectionText } from '@registerwerk/ui';
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { PageHeaderComponent, StatusBadgeComponent } from '@registerwerk/ui';
import { AuthService } from '../../../core/auth/auth.service';
import { BeneficialOwnerService } from '../../../core/api/beneficial-owner.service';
import { KycService } from '../../../core/api/kyc.service';
import { Jurisdiction, KycReview, KycReviewBeneficialOwner, KycReviewDocument } from '../../../core/models';
import { downloadBlob } from '../../../core/utils/download.util';
import { withDualControl } from '../../../shared/components/step-up/with-dual-control';
import { showActionError } from '../../../shared/utils/action-error';
import {
  KycRejectDialogComponent,
  KycRejectDialogResult,
} from '../../customers/customer-detail/kyc-reject-dialog.component';
import { reasonLabel } from './kyc-reasons';

const JURISDICTION_LABEL: Record<Jurisdiction, string> = {
  DE_EWPG: 'Germany - eWpG / BaFin',
  LU_CSSF: 'Luxembourg - CSSF',
  FR_AMF: 'France - AMF',
  LI_TVTG: 'Liechtenstein - TVTG / FMA',
};

/**
 * Scoped KYC review for the compliance officer (T8-03). Reads only `GET /kyc/entities/{id}/review` (identity,
 * documents, checklist, beneficial owners, screening state, previous decisions - no contact data, wallets or
 * balances) and takes the decisions through the existing KYC endpoints, each with step-up and a second approver.
 * EDD approval and the documented override of a failed gate stay registry-administrator actions.
 */
@Component({
  selector: 'app-kyc-review',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe, RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule,
    PageHeaderComponent, StatusBadgeComponent,
  ],
  styles: [`
    section { margin: 0 0 28px; }
    h3 { margin: 0 0 8px; font-size: 15px; font-weight: 600; }
    .hint { font-size: 12px; color: var(--rw-text-secondary); margin: 0 0 12px; }
    .facts { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px 24px; }
    .facts dt { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--rw-text-secondary); }
    .facts dd { margin: 2px 0 0; font-size: 14px; color: var(--rw-text-primary); }
    .chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .chip { padding: 2px 10px; border-radius: 12px; font-size: 12px; background: var(--rw-pending-bg); color: var(--rw-pending-fg); }
    .chip.bad { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
    .chip.ok { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
    table.grid { width: 100%; border-collapse: collapse; font-size: 13px; }
    table.grid th { text-align: left; font-weight: 600; color: var(--rw-text-secondary); padding: 6px 8px; border-bottom: 1px solid var(--rw-border); }
    table.grid td { padding: 8px; border-bottom: 1px solid var(--rw-border-subtle); vertical-align: middle; }
    .num { text-align: right; }
    .danger { color: var(--rw-text-danger); }
    .muted { color: var(--rw-text-secondary); }
    .empty { color: var(--rw-text-secondary); font-size: 13px; padding: 8px 0; }
    .spinner-wrap { display: flex; justify-content: center; padding: 40px; }
    .request-error { display: grid; justify-items: center; gap: 12px; padding: 40px 20px; color: var(--rw-text-danger); text-align: center; }
    .scroll { overflow-x: auto; }
  `],
  template: `
    <a mat-button routerLink="/compliance/kyc"><mat-icon>arrow_back</mat-icon> KYC Queue</a>

    @if (loading) {
      <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
    } @else if (loadError || !review) {
      <div class="request-error">
        <p>The KYC review could not be loaded.</p>
        <button type="button" mat-stroked-button (click)="load()">Retry</button>
      </div>
    } @else {
      <app-page-header [title]="review.legalName"
        subtitle="Scoped KYC review: what the decision rests on, without contact data, wallets or balances">
        @if (review.kycStatus !== 'APPROVED') {
          <button type="button" mat-stroked-button id="approve-kyc" (click)="approveKyc()"
                  matTooltip="Approve this entity's KYC. The server sets the expiry (at most 12 months). Needs step-up and a second approver.">
            <mat-icon>check_circle</mat-icon> Approve KYC
          </button>
          @if (canOverride) {
            <button type="button" mat-stroked-button id="approve-kyc-override" (click)="approveKyc(true)"
                    matTooltip="Approve despite a failed gate: a documented override, registry administrators only.">
              <mat-icon>gavel</mat-icon> Approve with override
            </button>
          }
        }
        @if (review.kycStatus !== 'REJECTED') {
          <button type="button" mat-stroked-button color="warn" id="reject-kyc" (click)="rejectKyc()"
                  matTooltip="Reject this entity's KYC: an internal reason plus the category the customer sees. Needs step-up and a second approver.">
            <mat-icon>cancel</mat-icon> Reject KYC
          </button>
        }
      </app-page-header>

      <section>
        <dl class="facts">
          <div><dt>KYC status</dt><dd><app-status-badge [status]="review.kycStatus" /></dd></div>
          <div><dt>KYC expiry</dt><dd>{{ review.kycExpiryDate ? (review.kycExpiryDate | date: 'mediumDate') : '-' }}</dd></div>
          <div><dt>Entity status</dt><dd>{{ review.entityStatus }}</dd></div>
          <div><dt>Type</dt><dd>{{ review.entityType }}</dd></div>
          <div><dt>Jurisdiction (home)</dt><dd>{{ jurisdictionLabel(review.homeJurisdiction) }}</dd></div>
          <div><dt>Registration</dt><dd>{{ review.registrationCountry || '-' }} {{ review.registrationNumber }}</dd></div>
          <div><dt>LEI</dt><dd>{{ review.leiCode || '-' }}</dd></div>
          <div><dt>Incorporated</dt><dd>{{ review.incorporationDate ? (review.incorporationDate | date: 'mediumDate') : '-' }}</dd></div>
        </dl>
      </section>

      <section>
        <h3>Open findings</h3>
        @if (review.gaps.length === 0) {
          <span class="chip ok">No open findings</span>
        } @else {
          <div class="chips">
            @for (gap of review.gaps; track gap) { <span class="chip bad">{{ label(gap) }}</span> }
          </div>
        }
      </section>

      <section>
        <h3>Screening</h3>
        <div class="chips">
          <span class="chip" [class.bad]="review.screening.entityHitUnresolved" [class.ok]="!review.screening.entityHitUnresolved">
            Entity: {{ review.screening.entityHitUnresolved ? 'hit unresolved' : 'no unresolved hit' }}
          </span>
          <span class="chip" [class.bad]="review.screening.beneficialOwnerHitUnresolved" [class.ok]="!review.screening.beneficialOwnerHitUnresolved">
            Beneficial owners: {{ review.screening.beneficialOwnerHitUnresolved ? 'hit unresolved' : 'no unresolved hit' }}
          </span>
          @if (review.screening.relyingOnStaleResult) {
            <span class="chip bad">Relying on a stale screening result (provider degraded)</span>
          }
        </div>
        <p class="hint">Hits are resolved in the <a routerLink="/compliance/screening">screening queue</a>.</p>
      </section>

      <section>
        <h3>Documents</h3>
        @if (review.documents.length === 0) {
          <div class="empty">No documents on file.</div>
        } @else {
          <div class="scroll">
            <table class="grid">
              <thead><tr><th>Type</th><th>File</th><th>Issued</th><th>Expires</th><th>Uploaded</th><th></th></tr></thead>
              <tbody>
                @for (doc of review.documents; track doc.id) {
                  <tr>
                    <td>{{ docType(doc.documentType) }}</td>
                    <td>{{ doc.fileName }}</td>
                    <td>{{ doc.issueDate ? (doc.issueDate | date: 'mediumDate') : '-' }}</td>
                    <td [class.danger]="isExpired(doc)">{{ doc.expiresAt ? (doc.expiresAt | date: 'mediumDate') : '-' }}</td>
                    <td>{{ doc.uploadedAt | date: 'medium' }}</td>
                    <td class="num">
                      <button type="button" mat-icon-button matTooltip="Download" (click)="downloadDoc(doc)">
                        <mat-icon>download</mat-icon>
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <section>
        <h3>Checklist - {{ jurisdictionLabel(review.homeJurisdiction) }}</h3>
        <p class="hint">
          {{ review.checklist.fullyCompliant ? 'Complete.' : 'Incomplete: ' + review.checklist.missingCount + ' missing, '
            + review.checklist.expiredCount + ' expired, ' + review.checklist.tooOldCount + ' too old.' }}
        </p>
        <div class="scroll">
          <table class="grid">
            <thead><tr><th>Document</th><th>Required</th><th>State</th></tr></thead>
            <tbody>
              @for (d of review.checklist.documents; track d.documentType) {
                <tr>
                  <td>{{ d.localName }}</td>
                  <td>{{ d.mandatory ? 'Mandatory' : 'Optional' }}</td>
                  <td [class.danger]="d.mandatory && (!d.present || d.expired || d.tooOld)">
                    {{ !d.present ? 'Missing' : d.expired ? 'Expired' : d.tooOld ? 'Too old' : 'Present' }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h3>Beneficial owners</h3>
        <p class="hint">
          Identified {{ review.ownership.identifiedPct }}% of ownership (needs {{ review.ownership.requiredIdentifiedPct }}%
          unless a managing-official fallback is documented).
          {{ review.ownership.coverageSufficient ? 'Coverage is sufficient.' : 'Coverage is not sufficient.' }}
        </p>
        @if (review.beneficialOwners.length === 0) {
          <div class="empty">No beneficial owners registered.</div>
        } @else {
          <div class="scroll">
            <table class="grid">
              <thead>
                <tr><th>Name</th><th>Country</th><th>PEP</th><th class="num">Ownership</th><th>Control</th>
                    <th>Verified</th><th>Screening</th><th>EDD</th><th></th></tr>
              </thead>
              <tbody>
                @for (o of review.beneficialOwners; track o.owner.id) {
                  <tr>
                    <td>{{ o.owner.givenName }} {{ o.owner.familyName }}</td>
                    <td>{{ o.owner.country || '-' }}</td>
                    <td [class.danger]="isPep(o)">{{ o.owner.pepStatus.replace('_', ' ') }}</td>
                    <td class="num">{{ o.owner.ownershipPct ?? '-' }}{{ o.owner.ownershipPct != null ? '%' : '' }}</td>
                    <td>{{ o.owner.controlType.replace('_', ' ') }}</td>
                    <td [class.danger]="!o.owner.verifiedAt && o.owner.controlType !== 'SENIOR_MANAGING_OFFICIAL'">
                      {{ o.owner.verifiedAt ? (o.owner.verifiedAt | date: 'mediumDate') : 'Not verified' }}
                    </td>
                    <td [class.danger]="o.screeningUnresolved">{{ o.screeningUnresolved ? 'Hit unresolved' : 'No unresolved hit' }}</td>
                    <td>
                      @if (o.eddInForce) { In force until {{ o.eddReviewDue | date: 'mediumDate' }} }
                      @else if (isPep(o)) { <span class="danger">Required</span> }
                      @else { <span class="muted">-</span> }
                    </td>
                    <td class="num">
                      @if (!o.owner.verifiedAt) {
                        <button type="button" mat-icon-button matTooltip="Verify against a stored document" (click)="verifyOwner(o)">
                          <mat-icon>verified_user</mat-icon>
                        </button>
                      }
                      @if (isPep(o) && !o.eddInForce) {
                        @if (canApproveEdd) {
                          <button type="button" mat-icon-button matTooltip="Approve EDD (step-up and a second approver)" (click)="approveEdd(o)">
                            <mat-icon>policy</mat-icon>
                          </button>
                        } @else {
                          <span class="muted" matTooltip="EDD approval is done by a registry administrator">admin</span>
                        }
                      }
                      <button type="button" mat-icon-button color="warn" matTooltip="Cease (no longer a beneficial owner)" (click)="ceaseOwner(o)">
                        <mat-icon>person_remove</mat-icon>
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <section>
        <h3>Jurisdiction approvals</h3>
        <div class="scroll">
          <table class="grid">
            <thead><tr><th>Jurisdiction</th><th>Status</th><th>Expires</th><th>Note</th><th></th></tr></thead>
            <tbody>
              @for (j of jurisdictions; track j) {
                <tr>
                  <td>{{ jurisdictionLabel(j) }}</td>
                  <td><app-status-badge [status]="jurisdictionStatus(j)" /></td>
                  <td>{{ jurisdictionExpiry(j) ? (jurisdictionExpiry(j) | date: 'mediumDate') : '-' }}</td>
                  <td class="muted">{{ jurisdictionNote(j) }}</td>
                  <td class="num">
                    @if (jurisdictionStatus(j) !== 'APPROVED') {
                      <button type="button" mat-stroked-button (click)="approveJurisdiction(j)">Approve</button>
                    }
                    @if (jurisdictionStatus(j) !== 'REJECTED') {
                      <button type="button" mat-stroked-button color="warn" (click)="rejectJurisdiction(j)">Reject</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h3>Previous decisions</h3>
        @if (review.decisions.length === 0) {
          <div class="empty">No entity-level approval has been recorded yet.</div>
        } @else {
          <div class="scroll">
            <table class="grid">
              <thead><tr><th>Decided</th><th>Jurisdiction</th><th>Valid until</th><th>Checklist</th><th class="num">Identified</th><th>Override</th></tr></thead>
              <tbody>
                @for (d of review.decisions; track d.id) {
                  <tr>
                    <td>{{ d.createdAt | date: 'medium' }}</td>
                    <td>{{ d.jurisdiction }}</td>
                    <td>{{ d.expiryDate ? (d.expiryDate | date: 'mediumDate') : '-' }}</td>
                    <td [class.danger]="!d.checklistCompliant">{{ d.checklistCompliant ? 'Compliant' : 'Incomplete' }}</td>
                    <td class="num">{{ d.identifiedPct }}%{{ d.smoFallback ? ' (fallback)' : '' }}</td>
                    <td>{{ d.overrideNote || '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    }
  `,
})
export class KycReviewComponent implements OnInit {
  @Input() id!: string;

  private readonly kycService = inject(KycService);
  private readonly beneficialOwnerService = inject(BeneficialOwnerService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  /** The documented override of a failed gate and EDD approval are registry-administrator decisions. */
  readonly canOverride = this.auth.hasRole('REGISTRY_ADMIN');
  readonly canApproveEdd = this.auth.hasRole('REGISTRY_ADMIN');
  readonly jurisdictions: Jurisdiction[] = ['DE_EWPG', 'LU_CSSF', 'FR_AMF', 'LI_TVTG'];

  review: KycReview | null = null;
  loading = true;
  loadError = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = false;
    this.cdr.markForCheck();
    this.kycService.getReview(this.id).subscribe({
      next: (review) => { this.review = review; this.loading = false; this.cdr.markForCheck(); },
      error: () => { this.loadError = true; this.loading = false; this.cdr.markForCheck(); },
    });
  }

  label(code: string): string { return reasonLabel(code); }

  jurisdictionLabel(jur: Jurisdiction): string { return JURISDICTION_LABEL[jur] ?? jur; }

  docType(type: string): string {
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }

  isExpired(doc: KycReviewDocument): boolean {
    return !!doc.expiresAt && new Date(doc.expiresAt).getTime() < Date.now();
  }

  isPep(o: KycReviewBeneficialOwner): boolean {
    return o.owner.pepStatus === 'CONFIRMED_PEP';
  }

  jurisdictionStatus(jur: Jurisdiction): string {
    return this.review?.jurisdictionApprovals.find(a => a.jurisdiction === jur)?.status ?? 'PENDING';
  }

  jurisdictionExpiry(jur: Jurisdiction): string | undefined {
    return this.review?.jurisdictionApprovals.find(a => a.jurisdiction === jur)?.expiresAt;
  }

  jurisdictionNote(jur: Jurisdiction): string {
    const a = this.review?.jurisdictionApprovals.find(x => x.jurisdiction === jur);
    // The stored rejection is a fixed category (the free-text reason lives in the audit trail only).
    return kycRejectionText(a?.rejectionReason) ?? a?.overrideNote ?? '';
  }

  downloadDoc(doc: KycReviewDocument): void {
    this.kycService.downloadDocument(this.id, doc.id).subscribe({
      next: (blob) => downloadBlob(blob, doc.fileName),
      error: (err) => this.fail('Failed to download the document.', err),
    });
  }

  // ── Decisions: the existing endpoints, each with step-up and a second approver ─────────────────────────

  approveKyc(withOverride = false): void {
    let overrideNote: string | undefined;
    if (withOverride) {
      const note = prompt('Override note (required, kept in the audit trail). Only a registry administrator may override a failed KYC gate:');
      if (!note || !note.trim()) return;
      overrideNote = note.trim();
    }
    this.dual('KYC_APPROVE', 'Approve KYC for this customer', `POST /api/v1/entities/${this.id}/kyc/approve`,
      { overrideNote }, tokens => {
      this.kycService.approveKyc(this.id, { overrideNote }, tokens).subscribe({
        next: () => this.done('KYC approved.'),
        error: (err) => this.fail('Failed to approve KYC.', err),
      });
    });
  }

  rejectKyc(): void {
    this.dialog.open(KycRejectDialogComponent, { width: '520px', maxWidth: '95vw' })
      .afterClosed().subscribe((decision: KycRejectDialogResult | undefined) => {
        if (!decision) return;
        this.dual('KYC_REJECT', 'Reject KYC for this customer', `POST /api/v1/entities/${this.id}/kyc/reject`,
          { reason: decision.reason, customerReasonCode: decision.customerReasonCode }, tokens => {
          this.kycService.rejectKyc(this.id, decision.reason, decision.customerReasonCode, tokens).subscribe({
            next: () => this.done('KYC rejected. The customer is told the selected category only.'),
            error: (err) => this.fail('Failed to reject KYC.', err),
          });
        });
      });
  }

  approveJurisdiction(jur: Jurisdiction): void {
    this.dual('KYC_JURISDICTION_APPROVE', `Approve KYC for ${this.jurisdictionLabel(jur)}`,
      `POST /api/v1/entities/${this.id}/kyc/jurisdictions/${jur}/approve`, {}, tokens => {
      this.kycService.approveJurisdiction(this.id, jur, undefined, tokens).subscribe({
        next: () => this.done('Jurisdiction KYC approved.'),
        error: (err) => this.fail('Failed to approve jurisdiction KYC.', err),
      });
    });
  }

  rejectJurisdiction(jur: Jurisdiction): void {
    this.dialog.open(KycRejectDialogComponent, {
      width: '520px', maxWidth: '95vw',
      data: { title: `Reject KYC for ${this.jurisdictionLabel(jur)}`, reasonVisibleToCustomer: true },
    }).afterClosed().subscribe((decision: KycRejectDialogResult | undefined) => {
      if (!decision) return;
      this.dual('KYC_JURISDICTION_REJECT', `Reject KYC for ${this.jurisdictionLabel(jur)}`,
        `POST /api/v1/entities/${this.id}/kyc/jurisdictions/${jur}/reject`,
        { reason: decision.reason, customerReasonCode: decision.customerReasonCode }, tokens => {
        this.kycService.rejectJurisdiction(this.id, jur, decision.reason, decision.customerReasonCode, tokens).subscribe({
          next: () => this.done('Jurisdiction KYC rejected.'),
          error: (err) => this.fail('Failed to reject jurisdiction KYC.', err),
        });
      });
    });
  }

  /** Picks one of the entity's stored documents by number; verification links the owner to it. */
  verifyOwner(o: KycReviewBeneficialOwner): void {
    const docs = this.review?.documents ?? [];
    if (docs.length === 0) {
      this.snackBar.open('No document on file to verify against.', 'Dismiss', { duration: 5000 });
      return;
    }
    const name = `${o.owner.givenName} ${o.owner.familyName}`;
    const list = docs.map((d, i) => `${i + 1}) ${d.fileName} (${d.documentType})`).join('\n');
    const doc = docs[Number(prompt(`Verify ${name} against which document?\n${list}`, '1')) - 1];
    if (!doc) return;
    this.beneficialOwnerService.verify(this.id, o.owner.id, doc.id).subscribe({
      next: () => this.done('Beneficial owner verified.'),
      error: (err) => this.fail('Failed to verify the beneficial owner.', err),
    });
  }

  ceaseOwner(o: KycReviewBeneficialOwner): void {
    const name = `${o.owner.givenName} ${o.owner.familyName}`;
    const reason = prompt(`Reason for ceasing ${name} as beneficial owner (required, audited):`);
    if (!reason || !reason.trim()) return;
    this.dual('BENEFICIAL_OWNER_CEASE', `Cease beneficial owner ${name}`,
      `DELETE /api/v1/entities/${this.id}/beneficial-owners/${o.owner.id}`, { reason: reason.trim() }, tokens => {
      this.beneficialOwnerService.cease(this.id, o.owner.id, { reason: reason.trim() }, tokens).subscribe({
        next: () => this.done('Beneficial owner ceased.'),
        error: (err) => this.fail('Failed to cease the beneficial owner.', err),
      });
    });
  }

  approveEdd(o: KycReviewBeneficialOwner): void {
    const name = `${o.owner.givenName} ${o.owner.familyName}`;
    const note = prompt(`Enhanced due diligence note for ${name} (required, audited):`);
    if (!note || !note.trim()) return;
    this.dual('PEP_EDD_APPROVE', `Approve EDD for ${name}`,
      `POST /api/v1/entities/${this.id}/beneficial-owners/${o.owner.id}/edd-approvals`, { note: note.trim() }, tokens => {
      this.beneficialOwnerService.approveEdd(this.id, o.owner.id, { note: note.trim() }, tokens).subscribe({
        next: () => this.done('EDD approved; review due within 6 months.'),
        error: (err) => this.fail('Failed to approve EDD.', err),
      });
    });
  }

  private dual(action: string, reason: string, target: string, targetBody: unknown,
               then: Parameters<typeof withDualControl>[2]): void {
    withDualControl(this.dialog, { action, reason, target, targetBody }, then);
  }

  private done(message: string): void {
    this.snackBar.open(message, 'Dismiss', { duration: 5000 });
    this.load();
  }

  private fail(fallback: string, error: unknown): void {
    showActionError(this.snackBar, fallback, error);
    this.cdr.markForCheck();
  }
}
