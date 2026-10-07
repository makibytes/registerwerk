import { describe, expect, it } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ImpersonateDialogComponent } from './impersonate-dialog.component';

function render(productionMode: boolean): HTMLElement {
  TestBed.configureTestingModule({
    imports: [ImpersonateDialogComponent],
    providers: [
      provideZonelessChangeDetection(),
      { provide: MAT_DIALOG_DATA, useValue: { entityName: 'Acme GmbH', productionMode } },
      { provide: MatDialogRef, useValue: { close: () => undefined } },
    ],
  });
  const fixture = TestBed.createComponent(ImpersonateDialogComponent);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('ImpersonateDialogComponent (T6-05)', () => {
  it('offers act-on-behalf in demo mode', () => {
    const el = render(false);
    expect(el.textContent).toContain('Act on behalf of the customer');
  });

  it('never offers act-on-behalf in production mode and says why', () => {
    const el = render(true);
    expect(el.textContent).not.toContain('Act on behalf of the customer');
    expect(el.textContent).toContain('Read-only support session');
    expect(el.textContent).toContain('production');
  });
});
