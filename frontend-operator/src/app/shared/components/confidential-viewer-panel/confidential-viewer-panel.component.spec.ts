import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER } from 'rxjs';
import { ConfidentialService } from '../../../core/api/confidential.service';
import { TransactionService } from '../../../core/api/transaction.service';
import { FheClientService } from '../../../core/fhe/fhe-client.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import { stepUpDialogSpy, TOKENS } from '../step-up/step-up-test-helper';
import { ConfidentialViewerPanelComponent } from './confidential-viewer-panel.component';

describe('ConfidentialViewerPanelComponent - viewer revocation', () => {
  const service = { removeViewer: vi.fn() };

  beforeEach(() => {
    vi.resetAllMocks();
    service.removeViewer.mockReturnValue(NEVER);
  });

  it('binds POST .../admin/confidential-remove-viewer and the exact { viewerAddress } body', () => {
    const spy = stepUpDialogSpy();
    TestBed.configureTestingModule({
      imports: [ConfidentialViewerPanelComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ConfidentialService, useValue: service },
        { provide: TransactionService, useValue: {} },
        { provide: WalletService, useValue: {} },
        { provide: FheClientService, useValue: {} },
      ],
    });
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, { useValue: spy.dialog });
    const component = TestBed.createComponent(ConfidentialViewerPanelComponent).componentInstance;
    component.assetId = 'a1';
    component.deploymentId = 'd1';
    component.viewerAddress = '0xabc';

    component.removeViewer();

    expect(spy.stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'CONFIDENTIAL_VIEWER_REVOKE',
      target: 'POST /api/v1/assets/a1/deployments/d1/admin/confidential-remove-viewer',
      targetBody: { viewerAddress: '0xabc' },
    });
    expect(service.removeViewer).toHaveBeenCalledWith('a1', 'd1', '0xabc', TOKENS);
  });
});
