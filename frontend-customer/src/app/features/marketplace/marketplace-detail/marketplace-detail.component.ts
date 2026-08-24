import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarketplaceService } from '../../../core/api/marketplace.service';
import { CatalogDetailView } from '../../../core/models';

interface ManifestImage {
  name: string;
  role: string;
  ref: string;
}

@Component({
  selector: 'app-marketplace-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTabsModule,
    MatTooltipModule,
  ],
  styles: [`
    .detail-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
      margin-bottom: 6px;

      .detail-title { display: flex; align-items: center; gap: 12px; }
      .detail-title mat-icon { color: var(--rw-accent); font-size: 32px; width: 32px; height: 32px; }
      h1 { margin: 0; }
      .detail-publisher { margin: 2px 0 0; color: var(--rw-text-muted); font-size: 13px; }
    }

    .tab-body { padding: 20px 4px; }

    .field-label {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--rw-text-muted);
      margin: 0 0 4px;
    }

    .field-value {
      font-size: 13px;
      margin: 0 0 16px;
      word-break: break-all;

      &.mono { font-family: 'IBM Plex Mono', monospace; }
    }

    .integrity-box {
      background: var(--rw-code-bg);
      color: var(--rw-code-fg);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      padding: 14px;
      border-radius: 8px;
      white-space: pre-wrap;
      word-break: break-all;
      position: relative;
    }

    .permission-rationale { color: var(--rw-text-secondary); font-size: 12px; }

    .payment-methods { display: flex; flex-direction: column; gap: 10px; }

    .payment-card {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 14px 16px;
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius);
      background: var(--rw-surface);

      &.custom { border-color: var(--rw-accent); }
    }

    .payment-card-header { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 14px; }
    .payment-card-meta { font-size: 12px; color: var(--rw-text-secondary); }
    .emt-badge {
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--rw-approved-fg);
      background: var(--rw-approved-bg);
      border-radius: var(--rw-radius-sm);
      padding: 2px 6px;
    }
    .custom-badge {
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--rw-accent-contrast);
      background: var(--rw-accent);
      border-radius: var(--rw-radius-sm);
      padding: 2px 6px;
    }

    .manifest-pre {
      background: var(--rw-code-bg);
      color: var(--rw-code-fg);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      padding: 14px;
      border-radius: 8px;
      overflow-x: auto;
      max-height: 480px;
    }

    .empty-state { text-align: center; padding: 60px 16px; color: var(--rw-text-secondary); }
    .table-wrap { overflow-x: auto; }
  `],
  template: `
    <div class="page-container">
      @if (loading) {
        <div class="empty-state"><mat-spinner diameter="36" style="margin:0 auto"></mat-spinner></div>
      } @else if (detail; as d) {
        <div class="detail-header">
          <div class="detail-title">
            <mat-icon>widgets</mat-icon>
            <div>
              <h1>{{ d.listing.name }}</h1>
              <p class="detail-publisher">
                by {{ d.listing.publisherName ?? 'Unknown publisher' }}
                · v{{ d.version.version }} · {{ d.listing.category }}
                · {{ d.listing.chainIdentifier }}
              </p>
            </div>
          </div>
          <button type="button" mat-stroked-button routerLink="/marketplace">
            <mat-icon>arrow_back</mat-icon>
            Marketplace
          </button>
        </div>

        <mat-tab-group>
          <mat-tab label="Overview">
            <div class="tab-body">
              <p class="field-label">Description</p>
              <p class="field-value" style="white-space:pre-wrap">{{ description }}</p>
              @if (d.listing.pricingNote) {
                <p class="field-label">Pricing / licensing</p>
                <p class="field-value">{{ d.listing.pricingNote }}</p>
              }
              <p class="field-label">Published</p>
              <p class="field-value">{{ d.listing.updatedAt | date: 'medium' }}</p>
            </div>
          </mat-tab>

          <mat-tab label="Permissions & Claims">
            <div class="tab-body">
              <p style="font-size:13px;color:var(--rw-text-secondary);margin:0 0 16px">
                This dApp gates its actions through the Registerwerk PermissionOracle.
                Your organization needs these grants from the registry operator before use:
              </p>
              @if (d.requiredPermissions.length === 0) {
                <p class="field-value">This dApp declares no gated permissions.</p>
              } @else {
                <div class="table-wrap">
                <table mat-table [dataSource]="d.requiredPermissions" style="width:100%">
                  <ng-container matColumnDef="permissionCode">
                    <th mat-header-cell *matHeaderCellDef>Permission</th>
                    <td mat-cell *matCellDef="let p" style="font-family:'IBM Plex Mono',monospace;font-size:12px">
                      {{ p.permissionCode }}
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="rationale">
                    <th mat-header-cell *matHeaderCellDef>Why the dApp needs it</th>
                    <td mat-cell *matCellDef="let p" class="permission-rationale">{{ p.rationale ?? '—' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="claimTopics">
                    <th mat-header-cell *matHeaderCellDef>Required claims</th>
                    <td mat-cell *matCellDef="let p">{{ claimTopicNames(p.claimTopics) }}</td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="permissionColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: permissionColumns"></tr>
                </table>
                </div>
              }
            </div>
          </mat-tab>

          <mat-tab [label]="'Payments (' + d.paymentMethods.length + ')'">
            <div class="tab-body payment-methods">
              @if (d.paymentMethods.length === 0) {
                <p class="field-value">This dApp declares no payment methods — check the docs below for how the publisher settles payments.</p>
              }
              @for (method of d.paymentMethods; track $index) {
                <div class="payment-card" [class.custom]="method.methodType === 'CUSTOM'">
                  @if (method.methodType === 'RAIL') {
                    <div class="payment-card-header">
                      <mat-icon style="color:var(--rw-accent)">payments</mat-icon>
                      {{ method.displayName }}
                      @if (method.emtFlag) {
                        <span class="emt-badge">MiCAR EMT</span>
                      }
                    </div>
                    <p class="payment-card-meta">{{ method.railType }} · {{ method.currency }}</p>
                    @if (method.issuerName) {
                      <p class="payment-card-meta">
                        Issued by {{ method.issuerName }}
                        @if (method.issuerLei) { (LEI {{ method.issuerLei }}) }
                      </p>
                    }
                    @if (method.redemptionAtPar || method.whitePaperUrl) {
                      <p class="payment-card-meta">
                        @if (method.redemptionAtPar) { Redeemable at par at any time. }
                        @if (externalUrl(method.whitePaperUrl); as whitePaperUrl) {
                          <a [href]="whitePaperUrl" target="_blank" rel="noopener noreferrer">Read the white paper</a>
                        }
                      </p>
                    }
                  } @else {
                    <div class="payment-card-header">
                      <mat-icon style="color:var(--rw-accent)">build</mat-icon>
                      {{ method.customName }}
                      <span class="custom-badge">publisher-implemented</span>
                    </div>
                    <p class="payment-card-meta">{{ method.customDescription }}</p>
                    @if (method.currency) {
                      <p class="payment-card-meta">{{ method.currency }}</p>
                    }
                  }
                  @if (method.note) {
                    <p class="permission-rationale">{{ method.note }}</p>
                  }
                </div>
              }
            </div>
          </mat-tab>

          <mat-tab label="Versions & Integrity">
            <div class="tab-body">
              <p class="field-label">Container images (pinned by digest)</p>
              @for (image of images; track image.ref) {
                <p class="field-value mono">{{ image.role }}: {{ image.ref }}</p>
              }

              <p class="field-label">Manifest keccak256 (anchored onchain)</p>
              <p class="field-value mono">{{ d.version.manifestHash }}</p>

              <p class="field-label">Signed by publisher wallet</p>
              <p class="field-value mono">{{ d.version.signerWallet }}</p>

              <p class="field-label">Onchain registration tx</p>
              <p class="field-value mono">{{ d.version.onchainTx ?? '—' }}</p>

              <p class="field-label">Verify yourself</p>
              <div class="integrity-box">{{ verificationSnippet }}</div>
              <button type="button" mat-stroked-button style="margin-top:10px" (click)="copyVerification()">
                <mat-icon>content_copy</mat-icon>
                Copy verification snippet
              </button>

              <p class="field-label" style="margin-top:24px">Raw manifest</p>
              <pre class="manifest-pre">{{ d.manifestRaw }}</pre>
            </div>
          </mat-tab>

          <mat-tab label="Docs & Contact">
            <div class="tab-body">
              @if (externalUrl(d.listing.docsUrl); as docsUrl) {
                <p class="field-label">Documentation</p>
                <p class="field-value"><a [href]="docsUrl" target="_blank" rel="noopener noreferrer">{{ docsUrl }}</a></p>
              }
              @if (d.listing.contactEmail) {
                <p class="field-label">Contact the publisher</p>
                <p class="field-value">
                  <a [href]="'mailto:' + d.listing.contactEmail + '?subject=Registerwerk dApp: ' + d.listing.name">
                    {{ d.listing.contactEmail }}
                  </a>
                </p>
              }
              <p style="font-size:12px;color:var(--rw-text-muted)">
                Deployment is self-hosted: pull the digest-pinned images above and follow the
                publisher's documentation. Licensing and pricing are agreed directly with the
                publisher.
              </p>
            </div>
          </mat-tab>
        </mat-tab-group>
      } @else {
        <div class="empty-state">
          <h1 class="sr-only">Marketplace listing details</h1>
          <mat-icon>search_off</mat-icon>
          <p>{{ error || 'dApp not found.' }}</p>
          @if (error) {
            <button mat-stroked-button type="button" (click)="load()">Retry</button>
          }
        </div>
      }
    </div>
  `,
})
export class MarketplaceDetailComponent implements OnInit {
  private readonly marketplaceService = inject(MarketplaceService);
  private readonly route = inject(ActivatedRoute);
  private readonly clipboard = inject(Clipboard);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly permissionColumns = ['permissionCode', 'rationale', 'claimTopics'];

  detail: CatalogDetailView | null = null;
  loading = true;
  error = '';
  description = '';
  images: ManifestImage[] = [];
  private slug = '';

  ngOnInit(): void {
    this.slug = this.route.snapshot.paramMap.get('slug')?.trim() ?? '';
    if (!this.slug) {
      this.error = 'The dApp address is incomplete.';
      this.loading = false;
      return;
    }
    this.load();
  }

  load(): void {
    if (!this.slug) return;
    this.loading = true;
    this.error = '';
    this.detail = null;
    this.marketplaceService.detail(this.slug).subscribe({
      next: (detail) => {
        this.detail = detail;
        this.parseManifest(detail.manifestRaw);
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = err?.error?.message ?? 'Failed to load dApp.';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  private parseManifest(raw: string): void {
    try {
      const manifest = JSON.parse(raw);
      this.description = manifest.description ?? '';
      this.images = manifest.images ?? [];
    } catch {
      this.description = '';
      this.images = [];
    }
  }

  claimTopicNames(topics: number[]): string {
    const names: Record<number, string> = { 1: 'KYC', 2: 'AML', 3: 'Accreditation' };
    return topics.length ? topics.map((t) => names[t] ?? `Topic ${t}`).join(', ') : '—';
  }

  get verificationSnippet(): string {
    const d = this.detail;
    if (!d) return '';
    return [
      '# Verify the manifest against its onchain anchor:',
      `# 1. keccak256 of the raw manifest must equal ${d.version.manifestHash}`,
      `cast keccak "$(cat manifest.json)"`,
      `# 2. Compare with DappRegistry.getDapp(${d.listing.dappIdHash}).manifestHash on ${d.listing.chainIdentifier}`,
      `# 3. Pull images only by the digests listed in the manifest`,
    ].join('\n');
  }

  copyVerification(): void {
    const copied = this.clipboard.copy(this.verificationSnippet);
    this.snackBar.open(
      copied ? 'Verification snippet copied.' : 'Could not copy the verification snippet.',
      'Dismiss',
      { duration: copied ? 3000 : 6000, panelClass: copied ? undefined : 'snack-error' },
    );
  }

  externalUrl(value: string | null | undefined): string | null {
    if (!value) return null;
    try {
      const url = new URL(value);
      return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : null;
    } catch {
      return null;
    }
  }
}
