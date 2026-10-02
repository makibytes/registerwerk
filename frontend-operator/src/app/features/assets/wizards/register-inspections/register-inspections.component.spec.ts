import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { RegisterInspectionsComponent } from './register-inspections.component';
import { RegisterInspectionService } from '../../../../core/api/register-inspection.service';
import { AuthService } from '../../../../core/auth/auth.service';

describe('RegisterInspectionsComponent fulfil (8B-06)', () => {
  let service: { listForAsset: ReturnType<typeof vi.fn>; fulfil: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };
  const request = { id: 'r1', status: 'APPROVED' } as never;

  function create(confirmed: boolean) {
    const fixture = TestBed.createComponent(RegisterInspectionsComponent);
    fixture.componentInstance.assetId = 'a1';
    const dialog = fixture.debugElement.injector.get(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(confirmed) } as never);
    return fixture.componentInstance;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    service = {
      listForAsset: vi.fn().mockReturnValue(of({ content: [], totalElements: 0 })),
      fulfil: vi.fn().mockReturnValue(of(new Blob(['pdf']))),
    };
    snackBar = { open: vi.fn().mockReturnValue({ onAction: () => of() }) };
    TestBed.configureTestingModule({
      imports: [RegisterInspectionsComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: RegisterInspectionService, useValue: service },
        { provide: AuthService, useValue: { getUserId: () => 'u1' } },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
  });

  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('does not call the one-shot fulfil endpoint unless the operator confirms', () => {
    create(false).confirmFulfil(request);
    expect(service.fulfil).not.toHaveBeenCalled();
  });

  it('after confirmation: downloads without revoking the URL synchronously, toast only after the download started', () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockReturnValue(undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockReturnValue(undefined);
    create(true).confirmFulfil(request);
    expect(service.fulfil).toHaveBeenCalledWith('r1');
    expect(click).toHaveBeenCalled();
    expect(revoke).not.toHaveBeenCalled();
    expect(snackBar.open.mock.calls[0][0]).toContain('Download started');
    expect(snackBar.open.mock.calls[0][1]).toBe('Download again');
    vi.advanceTimersByTime(10_000);
    expect(revoke).toHaveBeenCalledWith('blob:x');
  });

  it('a failed download click does not claim success and offers "Download again"', () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => { throw new Error('blocked'); });
    create(true).confirmFulfil(request);
    expect(snackBar.open.mock.calls[0][0]).not.toContain('Download started');
    expect(snackBar.open.mock.calls[0][1]).toBe('Download again');
  });
});
