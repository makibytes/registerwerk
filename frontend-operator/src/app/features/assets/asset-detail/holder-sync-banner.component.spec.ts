import { describe, expect, it } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HolderSyncBannerComponent } from './holder-sync-banner.component';
import { Asset } from '../../../core/models';

describe('HolderSyncBannerComponent (P4-01 / P4-04 reasons)', () => {
  function render(reason: string | null, wallets: string[] = []): HTMLElement {
    TestBed.configureTestingModule({
      imports: [HolderSyncBannerComponent],
      providers: [provideZonelessChangeDetection(), provideHttpClient()],
    });
    const fixture = TestBed.createComponent(HolderSyncBannerComponent);
    fixture.componentInstance.asset = {
      holderSyncStatus: 'BLOCKED', holderSyncBlockedReason: reason, holderSyncUnmappedWallets: wallets,
    } as Asset;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('names the not-indexed deployment and guides towards the indexer', () => {
    const el = render('deployment abc on Sepolia is not indexed: no Graph Node configured');
    expect(el.querySelector('[data-testid="holder-sync-reason"]')?.textContent)
      .toContain('deployment abc on Sepolia is not indexed');
    expect(el.textContent).toContain('Configure or repair the indexer');
  });

  it('shows a negative-net-balance reason without inventing unmapped wallets', () => {
    const el = render('indexed history incomplete: negative net balance for wallet(s) [0xabc]');
    expect(el.querySelector('[data-testid="holder-sync-reason"]')?.textContent).toContain('negative net balance');
    expect(el.textContent).not.toContain('Unmapped wallets hold finalized balances');
  });

  it('keeps the unmapped-wallet text for the classic reason', () => {
    const el = render('unmapped wallets', ['0xabc']);
    expect(el.textContent).toContain('Unmapped wallets hold finalized balances');
  });
});
