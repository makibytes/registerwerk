import { beforeEach, describe, expect, it } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  DataStatePillComponent,
  beginAsyncSection,
  createAsyncSection,
  failAsyncSection,
  resolveAsyncSection,
} from '@registerwerk/ui';

describe('async section helpers', () => {
  it('first failure is an error, a failure after a successful load is stale and keeps the data', () => {
    const initial = createAsyncSection<string[]>([]);
    expect(failAsyncSection(beginAsyncSection(initial)).status).toBe('error');

    const loaded = resolveAsyncSection(initial, ['a', 'b']);
    expect(loaded.status).toBe('ready');
    expect(loaded.lastLoadedAt).toBeTypeOf('number');

    const refreshing = beginAsyncSection(loaded);
    expect(refreshing.status).toBe('updating');
    const failed = failAsyncSection(refreshing);
    expect(failed.status).toBe('stale');
    expect(failed.data).toEqual(['a', 'b']);
    expect(failed.lastLoadedAt).toBe(loaded.lastLoadedAt);
  });
});

describe('DataStatePillComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DataStatePillComponent],
      providers: [provideZonelessChangeDetection()],
    });
  });

  it('labels the stale state and shows the last load time', async () => {
    const fixture = TestBed.createComponent(DataStatePillComponent);
    fixture.componentRef.setInput('status', 'stale');
    fixture.componentRef.setInput('lastLoadedAt', Date.now());
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Stale - refresh failed');
    expect(text).toContain('as of');
  });

  it('renders nothing when ready', async () => {
    const fixture = TestBed.createComponent(DataStatePillComponent);
    fixture.componentRef.setInput('status', 'ready');
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('.data-state-pill')).toBeNull();
  });
});
