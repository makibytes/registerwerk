import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { fetchAllPages, PageLike } from './paging.util';

function server(total: number, maxSize = 200) {
  const calls: number[] = [];
  const fetch = (page: number, size: number) => {
    calls.push(page);
    const s = Math.min(size, maxSize);
    const from = page * s;
    const content = Array.from({ length: Math.max(0, Math.min(s, total - from)) }, (_, i) => from + i);
    return of<PageLike<number>>({ content, totalElements: total, last: from + s >= total });
  };
  return { fetch, calls };
}

describe('fetchAllPages', () => {
  it('collects all 450 rows from 200/200/50 pages', () => {
    const { fetch, calls } = server(450);
    let result: unknown;
    fetchAllPages(fetch).subscribe(r => (result = r));
    const r = result as { items: number[]; totalElements: number; truncated: boolean };
    expect(r.items.length).toBe(450);
    expect(new Set(r.items).size).toBe(450);
    expect(r.truncated).toBe(false);
    expect(calls).toEqual([0, 1, 2]);
  });

  it('flags truncation when the page cap is hit before the last page', () => {
    const { fetch } = server(1000);
    let result: unknown;
    fetchAllPages(fetch, { pageSize: 200, maxPages: 2 }).subscribe(r => (result = r));
    const r = result as { items: number[]; totalElements: number; truncated: boolean };
    expect(r.items.length).toBe(400);
    expect(r.totalElements).toBe(1000);
    expect(r.truncated).toBe(true);
  });

  it('single short page is complete', () => {
    const { fetch } = server(3);
    let result: unknown;
    fetchAllPages(fetch).subscribe(r => (result = r));
    expect((result as { truncated: boolean }).truncated).toBe(false);
  });
});
