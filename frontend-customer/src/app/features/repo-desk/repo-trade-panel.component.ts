import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, computed, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { RepoLeg, RepoPhase, RepoDeskService } from '../../core/api/repo-desk.service';
import {
  RepoCollateral, RepoDefaultGround, RepoSftrFields, RepoSubstitution, RepoTrade,
} from '../../core/models';
import { eventLabel, groundLabel, humanize, settlementLabel } from './repo-desk.labels';

interface LegRow {
  phase: RepoPhase; leg: RepoLeg; title: string; payer: string; recipient: string; payerIsBorrower: boolean;
  iAmPayer: boolean; iAmRecipient: boolean; declaredAt: string | null; confirmed: boolean;
}

const DISPUTABLE = ['PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE'];
/** Default grace period after a default notice; the server's configured value is authoritative. */
const DEFAULT_GRACE_HOURS = 24;
const MIN_CURE_HOURS = 24;

/**
 * Body of one repo trade: settlement declarations / confirmations, margin, default notice, substitution,
 * dispute, SFTR fields and the lifecycle timeline. Every call goes through {@link RepoDeskService}; the backend
 * remains the authority on who may do what, this component only hides actions the caller's role cannot take.
 */
@Component({
  selector: 'app-repo-trade-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatSelectModule, MatSnackBarModule],
  template: `
    @let t = trade();

    <div class="terms-grid">
      <div><small>Repo rate</small><strong>{{ t.repoRate | number:'1.2-4' }}%</strong><span>ACT/{{ t.dayCountBasis }}</span></div>
      <div><small>Repurchase amount</small><strong>{{ t.repurchaseAmount | number:'1.2-2' }} {{ t.cashCurrency }}</strong><span>{{ t.endDate | date:'mediumDate' }}</span></div>
      <div><small>Collateral / haircut</small><strong>{{ t.collateralQuantity | number:'1.0-4' }}</strong><span>{{ t.haircutBps / 100 | number:'1.2-2' }}%</span></div>
      <div><small>My role</small><strong>{{ t.borrower ? 'Cash borrower' : 'Cash lender' }}</strong><span>{{ settlement() }}</span></div>
    </div>
    <p class="fine-print">
      <mat-icon>info</mat-icon>
      Each settlement leg is declared sent by the payer and confirmed received by the recipient. Registerwerk does not
      move cash or securities and does not verify these statements.
    </p>

    @if (partyFlagged()) {
      <div class="strip warn" role="alert">
        <mat-icon>gpp_maybe</mat-icon>
        <div>
          <strong>A party no longer meets the repo desk eligibility conditions</strong>
          <span>The trade was flagged (see the timeline). Settlement confirmations stay possible so the trade can be
            unwound; new margin calls, default notices, defaults and substitutions by an ineligible party are blocked.</span>
        </div>
      </div>
    }

    @if (t.status === 'DISPUTED') {
      <div class="strip warn" role="alert">
        <mat-icon>gavel</mat-icon>
        <div>
          <strong>Disputed since {{ t.disputedAt | date:'short' }}</strong>
          <span>{{ t.disputeReason }}</span>
          <span>The trade is frozen: no margin call, default, closing or substitution until the operator records an outcome
            (resume{{ t.preDisputeStatus ? ' at ' + humanize(t.preDisputeStatus) : '' }}, close or cancel). You can still add evidence notes.</span>
        </div>
      </div>
    }
    @if (t.status === 'DEFAULTED') {
      <div class="strip warn" role="alert">
        <mat-icon>report</mat-icon>
        <div>
          <strong>Default declared: {{ groundLabel(t.defaultGround) }}</strong>
          <span>Defaulting party: {{ t.defaultingPartyEntityId === myEntityId() ? 'you' : 'your counterparty' }}.
            A declaration is a record between the parties; it does not move any register entry.</span>
        </div>
      </div>
    }

    <!-- settlement legs -->
    @if (legRows().length > 0) {
      <h3 class="section">{{ t.status === 'PENDING_OPEN_SETTLEMENT' ? 'Opening settlement' : 'Closing settlement' }}</h3>
      @if (t.status === 'PENDING_OPEN_SETTLEMENT' && !startReached()) {
        <p class="hint">Start date is {{ t.startDate | date:'mediumDate' }}. Receipt can be confirmed from that date.</p>
      }
      @for (row of legRows(); track row.leg) {
        <div class="strip">
          <div>
            <strong>{{ row.title }}</strong>
            <span>{{ row.payer }} sends, {{ row.recipient }} confirms receipt.</span>
            <span class="state">
              <mat-icon>{{ row.declaredAt ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>
              {{ row.declaredAt ? 'Declared sent ' + (row.declaredAt | date:'short') : 'Not declared sent yet' }}
              <mat-icon>{{ row.confirmed ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>
              {{ row.confirmed ? 'Receipt confirmed' : 'Receipt not confirmed' }}
            </span>
          </div>
          @if ((row.iAmPayer && !row.declaredAt && !row.confirmed) || (row.iAmRecipient && !row.confirmed)) {
            <mat-form-field appearance="outline">
              <mat-label>Settlement reference</mat-label>
              <input matInput maxlength="200" [ngModel]="text(row.phase + row.leg)" (ngModelChange)="setText(row.phase + row.leg, $event)">
            </mat-form-field>
          }
          @if (row.iAmPayer && !row.declaredAt && !row.confirmed) {
            <button type="button" mat-stroked-button [disabled]="busy" (click)="declareLeg(row)">Declare sent</button>
          }
          @if (row.iAmRecipient && !row.confirmed) {
            <button type="button" mat-flat-button color="primary"
                    [disabled]="busy || (row.phase === 'open' && !startReached())" (click)="confirmLeg(row)">Confirm received</button>
          }
        </div>
      }
    }

    <!-- margin call -->
    @if (t.status === 'MARGIN_CALL') {
      <h3 class="section">Margin call</h3>
      <div class="strip warn">
        <mat-icon>warning</mat-icon>
        <div>
          <strong>{{ t.marginCallAmount | number:'1.2-2' }} {{ t.cashCurrency }} due {{ t.marginCallDueAt | date:'short' }}</strong>
          <span>Valuation: {{ t.marginValuationAmount | number:'1.2-2' }} {{ t.cashCurrency }} at haircut
            {{ (t.marginHaircutBps ?? t.haircutBps) / 100 | number:'1.2-2' }}%, reference {{ t.marginValuationReference }}</span>
          @if (t.marginDeliveredAt) {
            <span class="state"><mat-icon>check_circle</mat-icon>Borrower declared the top-up sent {{ t.marginDeliveredAt | date:'short' }}{{ marginLate() ? ' (after the deadline)' : '' }}.
              The call stays open until the lender confirms receipt.</span>
          }
        </div>
      </div>
      @if (t.borrower && !t.marginDeliveredAt) {
        <div class="strip">
          <div><strong>Declare top-up sent</strong><span>This does not clear the call. The lender must confirm receipt.</span></div>
          <mat-form-field appearance="outline"><mat-label>Transfer reference</mat-label>
            <input matInput maxlength="200" [ngModel]="text('margin')" (ngModelChange)="setText('margin', $event)"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Note (optional)</mat-label>
            <input matInput maxlength="1000" [ngModel]="text('marginNote')" (ngModelChange)="setText('marginNote', $event)"></mat-form-field>
          <button type="button" mat-stroked-button [disabled]="busy" (click)="marginDelivered()">Declare sent</button>
        </div>
      }
      @if (!t.borrower) {
        <div class="strip">
          <div><strong>Confirm receipt of the top-up</strong><span>Only your confirmation clears the margin call.</span></div>
          <mat-form-field appearance="outline"><mat-label>Receipt reference</mat-label>
            <input matInput maxlength="200" [ngModel]="text('margin')" (ngModelChange)="setText('margin', $event)"></mat-form-field>
          <button type="button" mat-flat-button color="primary" [disabled]="busy" (click)="marginConfirm()">Confirm receipt</button>
        </div>
      }
    }
    @if (t.status === 'OPEN' && !t.borrower) {
      <h3 class="section">Issue margin call</h3>
      <div class="strip stack">
        <p class="hint">Base the call on a current valuation of the collateral. The amount may not exceed the shortfall
          after the agreed haircut, and the borrower must get at least {{ minCureHours }} hours to cure.</p>
        <div class="grid">
          <mat-form-field appearance="outline"><mat-label>Valuation reference</mat-label>
            <input matInput maxlength="200" [ngModel]="text('valRef')" (ngModelChange)="setText('valRef', $event)"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Collateral value</mat-label>
            <input matInput type="number" min="0" [ngModel]="num('valAmount')" (ngModelChange)="setNum('valAmount', $event)">
            <span matTextSuffix>{{ t.cashCurrency }}</span></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Margin amount</mat-label>
            <input matInput type="number" min="0" [ngModel]="num('marginAmount')" (ngModelChange)="setNum('marginAmount', $event)">
            <span matTextSuffix>{{ t.cashCurrency }}</span>
            @if (shortfall() !== null) {<mat-hint>{{ shortfall()! > 0 ? 'Shortfall at this valuation: ' + (shortfall()! | number:'1.2-2') : 'No shortfall at this valuation' }}</mat-hint>}
          </mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Cure deadline</mat-label>
            <input matInput type="datetime-local" [ngModel]="text('marginDue') || defaultDue()" (ngModelChange)="setText('marginDue', $event)">
            <mat-hint>At least {{ minCureHours }} hours from now</mat-hint></mat-form-field>
          <mat-form-field appearance="outline" class="wide"><mat-label>Note (optional)</mat-label>
            <input matInput maxlength="1000" [ngModel]="text('callNote')" (ngModelChange)="setText('callNote', $event)"></mat-form-field>
        </div>
        <div class="actions"><button type="button" mat-stroked-button [disabled]="busy" (click)="issueMargin()">Issue margin call</button></div>
      </div>
    }

    <!-- default notice -> declaration -->
    @if (defaultGround(); as ground) {
      @if (amCreditor(ground) || noticeFor(ground)) {
        <h3 class="section">Default: {{ groundLabel(ground) }}</h3>
        <div class="strip stack">
          <ol class="steps">
            <li class="done"><mat-icon>check_circle</mat-icon><span>Obligation overdue</span></li>
            <li [class.done]="noticeFor(ground)"><mat-icon>{{ noticeFor(ground) ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>
              <span>Default notice {{ noticeFor(ground) ? 'served ' + (t.defaultNoticeAt | date:'short') : 'not served yet' }}</span></li>
            <li [class.done]="graceOver(ground)"><mat-icon>{{ graceOver(ground) ? 'check_circle' : 'schedule' }}</mat-icon>
              <span>{{ noticeFor(ground) ? 'Grace period ' + (graceOver(ground) ? 'over' : 'runs until about ' + (graceEnd() | date:'short')) : 'Grace period (' + graceHours + ' h by default) starts with the notice' }}</span></li>
            <li><mat-icon>radio_button_unchecked</mat-icon><span>Default declaration, only while the obligation is still unmet</span></li>
          </ol>
          @if (covered(ground)) {
            <p class="hint">The counterparty declared that it performed. A default cannot rest on an unrebutted declaration:
              confirm receipt if it arrived, otherwise raise a dispute.</p>
          } @else if (amCreditor(ground)) {
            <mat-form-field appearance="outline" class="wide"><mat-label>Note{{ noticeFor(ground) ? ' (required for the declaration)' : ' (optional)' }}</mat-label>
              <input matInput maxlength="1000" [ngModel]="text('defaultNote')" (ngModelChange)="setText('defaultNote', $event)"></mat-form-field>
            <div class="actions">
              @if (!noticeFor(ground)) {
                <button type="button" mat-stroked-button color="warn" [disabled]="busy" (click)="serveNotice()">
                  {{ ground === 'COLLATERAL_RETURN_FAILURE' ? 'Declare collateral return failure (serve notice)' : 'Serve default notice' }}</button>
              } @else {
                <button type="button" mat-flat-button color="warn" [disabled]="busy || !graceOver(ground) || !text('defaultNote').trim()" (click)="declareDefault()">Declare default</button>
              }
            </div>
          } @else {
            <p class="hint">Your counterparty served a default notice. If you performed, raise a dispute or make sure
              the obligation is confirmed as received before the grace period ends.</p>
          }
        </div>
      }
    }

    <!-- substitution -->
    @if (t.status === 'OPEN' && t.borrower && !liveSubstitution()) {
      <h3 class="section">Collateral substitution</h3>
      <div class="strip stack">
        <p class="hint">Replacement collateral must stay within its life until {{ t.endDate | date:'mediumDate' }} and be
          available in your holdings. The lender decides; a substitution is only possible while the trade is open.</p>
        <div class="grid">
          <mat-form-field appearance="outline"><mat-label>Replacement collateral</mat-label>
            <mat-select [ngModel]="text('subAsset')" (ngModelChange)="setText('subAsset', $event)">
              @for (asset of replacementOptions(); track asset.id) {
                <mat-option [value]="asset.id">{{ asset.name }} ({{ asset.availableQuantity | number:'1.0-4' }} available)</mat-option>
              }
            </mat-select></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Quantity</mat-label>
            <input matInput type="number" min="0" [ngModel]="num('subQty')" (ngModelChange)="setNum('subQty', $event)"></mat-form-field>
          <mat-form-field appearance="outline" class="wide"><mat-label>Note (optional)</mat-label>
            <input matInput maxlength="1000" [ngModel]="text('subNote')" (ngModelChange)="setText('subNote', $event)"></mat-form-field>
        </div>
        <div class="actions"><button type="button" mat-stroked-button [disabled]="busy" (click)="requestSubstitution()">Request substitution</button></div>
      </div>
    }
    @if (liveSubstitution(); as sub) {
      <h3 class="section">Collateral substitution</h3>
      <div class="strip stack">
        <div>
          <strong>{{ sub.status === 'PENDING' ? 'Requested' : 'Approved' }}: {{ sub.quantity | number:'1.0-4' }} units of {{ assetName(sub.assetId) }}</strong>
          @if (sub.note) {<span>{{ sub.note }}</span>}
        </div>
        @if (sub.status === 'PENDING') {
          @if (!t.borrower) {
            <mat-form-field appearance="outline" class="wide"><mat-label>Note (optional)</mat-label>
              <input matInput maxlength="1000" [ngModel]="text('subDecision')" (ngModelChange)="setText('subDecision', $event)"></mat-form-field>
            <div class="actions">
              <button type="button" mat-button color="warn" [disabled]="busy" (click)="decideSubstitution(sub, false)">Reject</button>
              <button type="button" mat-flat-button color="primary" [disabled]="busy" (click)="decideSubstitution(sub, true)">Approve</button>
            </div>
          } @else {
            <div class="actions"><button type="button" mat-button [disabled]="busy" (click)="withdrawSubstitution()">Withdraw request</button></div>
          }
        } @else {
          <span class="state">
            <mat-icon>{{ sub.replacementReceivedAt ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>Replacement received by lender
            <mat-icon>{{ sub.originalReturnedAt ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>Original returned to borrower
          </span>
          <mat-form-field appearance="outline"><mat-label>Reference</mat-label>
            <input matInput maxlength="200" [ngModel]="text('subRef')" (ngModelChange)="setText('subRef', $event)"></mat-form-field>
          <div class="actions">
            @if (!t.borrower && !sub.replacementReceivedAt) {
              <button type="button" mat-flat-button color="primary" [disabled]="busy" (click)="confirmSubstitution('REPLACEMENT_IN')">Confirm replacement received</button>
            }
            @if (t.borrower && !sub.originalReturnedAt) {
              <button type="button" mat-flat-button color="primary" [disabled]="busy" (click)="confirmSubstitution('ORIGINAL_OUT')">Confirm original collateral returned</button>
            }
            @if (!sub.replacementReceivedAt && !sub.originalReturnedAt) {
              <button type="button" mat-button [disabled]="busy" (click)="withdrawSubstitution()">Cancel substitution</button>
            }
          </div>
        }
      </div>
    }
    @if (pastSubstitutions().length > 0) {
      <p class="hint">
        <span>Earlier substitution requests:</span>
        @for (s of pastSubstitutions(); track s.id) {
          <span>{{ humanize(s.status) }} ({{ s.quantity | number:'1.0-4' }} units)</span>
        }
      </p>
    }

    <!-- close / dispute / notes -->
    @if (t.status === 'OPEN' && canClose()) {
      <div class="actions"><button type="button" mat-flat-button color="primary" [disabled]="busy" (click)="initiateClose()">Start closing settlement</button></div>
    }
    @if (t.status === 'OPEN' && !canClose()) {
      <p class="hint">Closing settlement can start from the end date ({{ t.endDate | date:'mediumDate' }}). Early termination needs a separately agreed amendment.</p>
    }

    @if (showFreeText()) {
      <div class="strip stack">
        <mat-form-field appearance="outline" class="wide">
          <mat-label>{{ freeTextMode === 'dispute' ? 'Reason for the dispute' : 'Evidence note' }}</mat-label>
          <textarea matInput rows="3" maxlength="1000" [ngModel]="text('free')" (ngModelChange)="setText('free', $event)"></textarea>
        </mat-form-field>
        <div class="actions">
          <button type="button" mat-button (click)="freeTextMode = null">Cancel</button>
          <button type="button" mat-flat-button [color]="freeTextMode === 'dispute' ? 'warn' : 'primary'"
                  [disabled]="busy || !text('free').trim()" (click)="submitFreeText()">
            {{ freeTextMode === 'dispute' ? 'Open dispute' : 'Add note' }}</button>
        </div>
      </div>
    }
    <div class="actions left">
      <button type="button" mat-button (click)="freeTextMode = 'note'"><mat-icon>note_add</mat-icon>Add evidence note</button>
      @if (disputable()) {
        <button type="button" mat-button color="warn" (click)="freeTextMode = 'dispute'"><mat-icon>gavel</mat-icon>Raise dispute</button>
      }
      <button type="button" mat-button (click)="toggleSftr()"><mat-icon>description</mat-icon>{{ sftr || sftrLoading ? 'Hide' : 'Show' }} SFTR fields</button>
    </div>

    @if (sftrLoading) {<p class="hint">Loading SFTR fields...</p>}
    @if (sftr; as s) {
      <div class="sftr">
        <h3 class="section">SFTR record-keeping fields (Art. 4)</h3>
        <dl>
          <dt>UTI</dt><dd class="mono">{{ s.uti || 'not assigned' }}</dd>
          <dt>Trading venue</dt><dd>{{ s.venue || 'n/a' }}</dd>
          <dt>Lender LEI</dt><dd class="mono">{{ s.lenderLei || 'missing' }}</dd>
          <dt>Borrower LEI</dt><dd class="mono">{{ s.borrowerLei || 'missing' }}</dd>
          <dt>Principal</dt><dd>{{ s.principalAmount | number:'1.2-2' }} {{ s.currency }}</dd>
          <dt>Repo rate / day count</dt><dd>{{ s.repoRatePercent | number:'1.2-4' }}% ACT/{{ s.dayCountBasis }}</dd>
          <dt>Start / maturity</dt><dd>{{ s.startDate | date:'mediumDate' }} / {{ s.maturityDate | date:'mediumDate' }}</dd>
          <dt>Repurchase amount</dt><dd>{{ s.repurchaseAmount | number:'1.2-2' }} {{ s.currency }}</dd>
          <dt>Collateral</dt><dd><span class="mono">{{ s.collateralIsin || 'no ISIN' }}</span>, {{ s.collateralQuantity | number:'1.0-4' }} units</dd>
          <dt>Haircut</dt><dd>{{ s.haircutBps / 100 | number:'1.2-2' }}%</dd>
          <dt>Collateral reuse consent</dt><dd>{{ s.collateralReuseConsent ? 'Yes' : 'No' }}</dd>
          <dt>Terms hash</dt><dd class="mono">{{ s.termsHash }}</dd>
          <dt>Status</dt><dd>{{ humanize(s.status) }}</dd>
        </dl>
        <p class="hint">{{ s.notice }}</p>
      </div>
    }

    <h3 class="section">Lifecycle</h3>
    <div class="timeline">
      @for (event of events(); track event.id) {
        <div class="event" [class.flag]="event.type === 'PARTY_FLAGGED'">
          <span class="dot"></span>
          <div>
            <strong>{{ eventLabel(event.type) }}</strong>
            <small>{{ event.actorName || 'System / operator' }} · {{ event.createdAt | date:'short' }}</small>
            @if (event.reference) {<span>Reference: {{ event.reference }}</span>}
            @if (event.note) {<span>{{ event.note }}</span>}
          </div>
        </div>
      }
    </div>
    @if (t.termsHash) {<p class="hint mono">Terms hash {{ t.termsHash }}@if (t.uti) { · UTI {{ t.uti }}}</p>}
  `,
  styles: [`
    :host{display:block}
    .terms-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:8px 0 12px}
    .terms-grid>div{display:flex;flex-direction:column;gap:4px;padding:14px;border-radius:12px;background:var(--mat-sys-surface-container-low)}
    .terms-grid small{color:var(--mat-sys-on-surface-variant);text-transform:uppercase;letter-spacing:.08em;font-size:.68rem}
    .terms-grid span{font-size:.78rem;color:var(--mat-sys-on-surface-variant)}
    .fine-print,.hint{display:flex;gap:8px;align-items:flex-start;margin:4px 0 12px;font-size:.8rem;color:var(--mat-sys-on-surface-variant)}
    .fine-print mat-icon{font-size:18px;width:18px;height:18px;flex:none}
    .section{margin:22px 0 8px;font-size:1rem}
    .strip{display:flex;align-items:center;gap:14px;padding:16px;margin:8px 0;background:var(--rw-surface-soft);border:1px solid var(--rw-border);border-radius:14px}
    .strip>div:first-of-type{display:flex;flex-direction:column;gap:3px;flex:1;min-width:0}
    .strip span{font-size:.82rem;color:var(--rw-text-secondary)}
    .strip.warn{background:var(--rw-pending-bg);color:var(--rw-pending-fg);border-color:var(--rw-pending-fg)}
    .strip.warn span{color:inherit}
    .strip.stack{flex-direction:column;align-items:stretch}
    .strip mat-form-field{min-width:200px;margin-bottom:-20px}
    .state{display:flex!important;flex-direction:row;align-items:center;gap:5px;flex-wrap:wrap}
    .state mat-icon{font-size:18px;width:18px;height:18px;color:var(--rw-accent)}
    .grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 16px}
    .grid .wide,.wide{grid-column:1/-1;width:100%}
    .actions{display:flex;justify-content:flex-end;gap:10px;align-items:center;margin-top:6px}
    .actions.left{justify-content:flex-start;flex-wrap:wrap;margin-top:14px}
    .steps{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:6px}
    .steps li{display:flex;gap:8px;align-items:center;color:var(--rw-text-secondary)}
    .steps li.done{color:var(--rw-text-primary)}
    .steps mat-icon{font-size:18px;width:18px;height:18px;color:var(--rw-text-muted)}
    .steps li.done mat-icon{color:var(--rw-accent)}
    .sftr dl{display:grid;grid-template-columns:minmax(150px,220px) 1fr;gap:6px 16px;margin:0}
    .sftr dt{color:var(--rw-text-secondary);font-size:.8rem}
    .sftr dd{margin:0;overflow-wrap:anywhere}
    .mono{font-family:var(--font-mono,monospace);font-size:.78rem;overflow-wrap:anywhere}
    .timeline{padding:4px 0 4px 9px}
    .event{display:grid;grid-template-columns:16px 1fr;gap:10px;position:relative;padding-bottom:18px}
    .event:before{content:'';position:absolute;left:4px;top:12px;bottom:-2px;width:1px;background:var(--rw-border)}
    .event:last-child:before{display:none}
    .dot{width:9px;height:9px;border-radius:50%;background:var(--rw-accent);margin-top:5px;z-index:1}
    .event.flag .dot{background:var(--rw-text-warning)}
    .event>div{display:flex;flex-direction:column;gap:2px}
    .event small,.event span{font-size:.78rem;color:var(--rw-text-secondary)}
    @media(max-width:900px){.terms-grid{grid-template-columns:1fr 1fr}.strip{flex-direction:column;align-items:stretch}.strip mat-form-field{width:100%}}
    @media(max-width:600px){.terms-grid,.grid{grid-template-columns:1fr}.sftr dl{grid-template-columns:1fr}}
  `],
})
export class RepoTradePanelComponent {
  private readonly api = inject(RepoDeskService);
  private readonly snack = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly trade = input.required<RepoTrade>();
  readonly collateral = input<RepoCollateral[]>([]);
  readonly updated = output<RepoTrade>();

  protected readonly minCureHours = MIN_CURE_HOURS;
  protected readonly graceHours = DEFAULT_GRACE_HOURS;
  protected readonly humanize = humanize;
  protected readonly eventLabel = eventLabel;
  protected readonly groundLabel = groundLabel;

  busy = false;
  freeTextMode: 'dispute' | 'note' | null = null;
  sftr: RepoSftrFields | null = null;
  sftrLoading = false;
  private readonly texts: Record<string, string> = {};
  private readonly nums: Record<string, number | null> = {};

  protected readonly settlement = computed(() => settlementLabel(this.trade().settlementMethod));
  protected readonly events = computed(() => [...this.trade().events].reverse());
  protected readonly partyFlagged = computed(() => this.trade().events.some(e => e.type === 'PARTY_FLAGGED'));
  protected readonly liveSubstitution = computed<RepoSubstitution | null>(() =>
    this.trade().substitutions.find(s => s.status === 'PENDING' || s.status === 'APPROVED') ?? null);
  protected readonly pastSubstitutions = computed(() =>
    this.trade().substitutions.filter(s => s.status !== 'PENDING' && s.status !== 'APPROVED'));

  protected readonly legRows = computed<LegRow[]>(() => {
    const t = this.trade();
    const phase: RepoPhase | null = t.status === 'PENDING_OPEN_SETTLEMENT' ? 'open' : t.status === 'PENDING_CLOSE' ? 'close' : null;
    if (!phase) return [];
    const open = phase === 'open';
    // open: lender pays cash, borrower delivers collateral; close: borrower repays cash, lender returns collateral
    const cashPayerIsBorrower = !open;
    const row = (leg: RepoLeg, payerIsBorrower: boolean, declaredAt: string | null, confirmed: boolean): LegRow => ({
      phase, leg, payerIsBorrower,
      title: `${open ? 'Opening' : 'Closing'} ${leg === 'CASH' ? (open ? 'cash' : 'repurchase cash') : (open ? 'collateral' : 'collateral return')}`,
      payer: payerIsBorrower ? t.cashBorrowerName : t.cashLenderName,
      recipient: payerIsBorrower ? t.cashLenderName : t.cashBorrowerName,
      iAmPayer: payerIsBorrower === t.borrower, iAmRecipient: payerIsBorrower !== t.borrower,
      declaredAt, confirmed,
    });
    return open
      ? [row('CASH', cashPayerIsBorrower, t.openCashDeclaredAt, t.openCashConfirmed),
         row('COLLATERAL', !cashPayerIsBorrower, t.openCollateralDeclaredAt, t.openCollateralConfirmed)]
      : [row('CASH', cashPayerIsBorrower, t.closeCashDeclaredAt, t.closeCashConfirmed),
         row('COLLATERAL', !cashPayerIsBorrower, t.closeCollateralDeclaredAt, t.closeCollateralConfirmed)];
  });

  /** Mirrors the backend's overdue test; the server re-checks every action. */
  protected readonly defaultGround = computed<RepoDefaultGround | null>(() => {
    const t = this.trade();
    if (t.status === 'MARGIN_CALL' && t.marginCallDueAt && new Date(t.marginCallDueAt).getTime() <= Date.now()) return 'MARGIN_NOT_MET';
    if (t.status === 'PENDING_CLOSE' && new Date().toISOString().slice(0, 10) > t.endDate) {
      if (!t.closeCashConfirmed) return 'REPURCHASE_UNPAID';
      if (!t.closeCollateralConfirmed) return 'COLLATERAL_RETURN_FAILURE';
    }
    return null;
  });

  protected readonly replacementOptions = computed(() => {
    const t = this.trade();
    return this.collateral().filter(a => a.id !== t.collateralAssetId && a.availableQuantity > 0
      && (!a.maturityDate || a.maturityDate >= t.endDate));
  });

  protected myEntityId(): string { const t = this.trade(); return t.borrower ? t.cashBorrowerEntityId : t.cashLenderEntityId; }
  protected text(key: string): string { return this.texts[key] ?? ''; }
  protected setText(key: string, value: string): void { this.texts[key] = value ?? ''; }
  protected num(key: string): number | null { return this.nums[key] ?? null; }
  protected setNum(key: string, value: number | null): void { this.nums[key] = value; }

  protected startReached(): boolean { return this.trade().startDate <= new Date().toISOString().slice(0, 10); }
  protected canClose(): boolean { return this.trade().endDate <= new Date().toISOString().slice(0, 10); }
  protected disputable(): boolean { return DISPUTABLE.includes(this.trade().status); }
  protected showFreeText(): boolean { return this.freeTextMode !== null; }
  protected marginLate(): boolean {
    const t = this.trade();
    return !!(t.marginDeliveredAt && t.marginCallDueAt && t.marginDeliveredAt > t.marginCallDueAt);
  }
  protected defaultDue(): string {
    const d = new Date(Date.now() + (MIN_CURE_HOURS + 1) * 3_600_000);
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }
  protected shortfall(): number | null {
    const t = this.trade();
    const valuation = this.num('valAmount');
    if (valuation == null || valuation <= 0) return null;
    return t.repurchaseAmount - valuation * (10_000 - t.haircutBps) / 10_000;
  }

  // creditor: lender, except for a collateral return failure where the borrower is owed
  protected amCreditor(ground: RepoDefaultGround): boolean { return this.trade().borrower === (ground === 'COLLATERAL_RETURN_FAILURE'); }
  protected noticeFor(ground: RepoDefaultGround): boolean {
    const t = this.trade();
    return !!t.defaultNoticeAt && t.defaultNoticeGround === ground;
  }
  protected graceEnd(): Date { return new Date(new Date(this.trade().defaultNoticeAt ?? Date.now()).getTime() + DEFAULT_GRACE_HOURS * 3_600_000); }
  protected graceOver(ground: RepoDefaultGround): boolean { return this.noticeFor(ground) && this.graceEnd().getTime() <= Date.now(); }
  protected covered(ground: RepoDefaultGround): boolean {
    const t = this.trade();
    switch (ground) {
      case 'MARGIN_NOT_MET': return !!(t.marginDeliveredAt && t.marginCallDueAt && t.marginDeliveredAt <= t.marginCallDueAt);
      case 'REPURCHASE_UNPAID': return !!t.closeCashDeclaredAt;
      default: return !!t.closeCollateralDeclaredAt;
    }
  }
  protected assetName(id: string): string { return this.collateral().find(a => a.id === id)?.name ?? `asset ${id.slice(0, 8)}`; }

  // --- actions -----------------------------------------------------------------------------------
  protected declareLeg(row: LegRow): void {
    const ref = this.requireText(row.phase + row.leg, 'Enter the settlement reference first');
    if (ref) this.run(this.api.declareLeg(this.trade().id, row.phase, row.leg, ref), 'Declared as sent');
  }
  protected confirmLeg(row: LegRow): void {
    const ref = this.requireText(row.phase + row.leg, 'Enter the settlement reference first');
    if (ref) this.run(this.api.confirmLeg(this.trade().id, row.phase, row.leg, ref), 'Receipt confirmed');
  }
  protected marginDelivered(): void {
    const ref = this.requireText('margin', 'Enter the transfer reference first');
    if (ref) this.run(this.api.marginDelivered(this.trade().id, ref, this.text('marginNote').trim() || null), 'Declared sent. The call stays open until the lender confirms.');
  }
  protected marginConfirm(): void {
    const ref = this.requireText('margin', 'Enter the receipt reference first');
    if (ref) this.run(this.api.marginConfirm(this.trade().id, ref, null), 'Receipt confirmed, margin call cleared');
  }
  protected issueMargin(): void {
    const amount = this.num('marginAmount'), valuation = this.num('valAmount');
    const ref = this.text('valRef').trim();
    const due = this.text('marginDue') || this.defaultDue();
    if (!amount || !valuation || !ref) { this.warn('Enter the valuation reference, collateral value and margin amount'); return; }
    const dueAt = new Date(due);
    if (Number.isNaN(dueAt.getTime()) || dueAt.getTime() < Date.now() + MIN_CURE_HOURS * 3_600_000) {
      this.warn(`The cure deadline must be at least ${MIN_CURE_HOURS} hours from now`); return;
    }
    this.run(this.api.marginCall(this.trade().id, {
      amount, dueAt: dueAt.toISOString(), valuationReference: ref, valuationAmount: valuation,
      note: this.text('callNote').trim() || null,
    }), 'Margin call issued');
  }
  protected requestSubstitution(): void {
    const asset = this.text('subAsset'), qty = this.num('subQty');
    if (!asset || !qty) { this.warn('Choose replacement collateral and a quantity'); return; }
    this.run(this.api.requestSubstitution(this.trade().id, asset, qty, this.text('subNote').trim() || null), 'Substitution requested');
  }
  protected decideSubstitution(sub: RepoSubstitution, approve: boolean): void {
    this.run(this.api.decideSubstitution(this.trade().id, sub.id, approve, this.text('subDecision').trim() || null),
      approve ? 'Substitution approved. Both legs still need confirming.' : 'Substitution rejected');
  }
  protected withdrawSubstitution(): void { this.run(this.api.withdrawSubstitution(this.trade().id, null), 'Substitution withdrawn'); }
  protected confirmSubstitution(leg: 'REPLACEMENT_IN' | 'ORIGINAL_OUT'): void {
    const ref = this.requireText('subRef', 'Enter a reference first');
    if (ref) this.run(this.api.confirmSubstitutionLeg(this.trade().id, leg, ref), 'Confirmed');
  }
  protected initiateClose(): void { this.run(this.api.initiateClose(this.trade().id), 'Closing settlement started'); }
  protected serveNotice(): void { this.run(this.api.defaultNotice(this.trade().id, this.text('defaultNote').trim() || null), 'Default notice served. The grace period has started.'); }
  protected declareDefault(): void { this.run(this.api.declareDefault(this.trade().id, this.text('defaultNote').trim()), 'Default declared'); }
  protected submitFreeText(): void {
    const note = this.text('free').trim();
    if (!note) return;
    const mode = this.freeTextMode;
    this.run(mode === 'dispute' ? this.api.dispute(this.trade().id, note) : this.api.addNote(this.trade().id, note),
      mode === 'dispute' ? 'Dispute opened. The trade is frozen until the operator records an outcome.' : 'Note added',
      () => { this.freeTextMode = null; this.texts['free'] = ''; });
  }
  protected toggleSftr(): void {
    if (this.sftr || this.sftrLoading) { this.sftr = null; this.sftrLoading = false; return; }
    this.sftrLoading = true;
    this.api.sftrFields(this.trade().id).subscribe({
      next: fields => { this.sftr = fields; this.sftrLoading = false; this.cdr.markForCheck(); },
      error: e => { this.sftrLoading = false; this.fail(e); this.cdr.markForCheck(); },
    });
  }

  private requireText(key: string, message: string): string | null {
    const value = this.text(key).trim();
    if (!value) { this.warn(message); return null; }
    return value;
  }

  private run(request: Observable<RepoTrade>, success: string, after?: () => void): void {
    this.busy = true;
    request.subscribe({
      next: trade => {
        this.busy = false;
        this.sftr = null;
        after?.();
        this.snack.open(success, 'Close', { duration: 4000 });
        this.updated.emit(trade);
        this.cdr.markForCheck();
      },
      error: e => { this.busy = false; this.fail(e); this.cdr.markForCheck(); },
    });
  }

  private warn(message: string): void { this.snack.open(message, 'Close', { duration: 3500 }); }
  private fail(error: unknown): void {
    const response = error as { error?: { message?: string } } | null;
    this.snack.open(response?.error?.message || 'Repo Desk request failed', 'Close', { duration: 7000 });
  }
}
