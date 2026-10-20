import { Observable, of } from 'rxjs';
import { expand, map, reduce } from 'rxjs/operators';

export interface PageLike<T> {
  content: T[];
  totalElements: number;
  last?: boolean;
}

export interface AllPages<T> {
  items: T[];
  /** Server-reported total; may exceed `items.length` when `truncated`. */
  totalElements: number;
  /** True when the page cap was hit before the last page: callers must label the data partial. */
  truncated: boolean;
}

/**
 * Fetches successive pages until `last` (or an empty/short page), capped at `maxPages`. The backend
 * silently clamps `size` to its max page size (200), so a single request must never be presumed to be
 * the whole list (8A-03).
 */
export function fetchAllPages<T>(
  fetchPage: (page: number, size: number) => Observable<PageLike<T>>,
  opts: { pageSize?: number; maxPages?: number } = {},
): Observable<AllPages<T>> {
  const pageSize = opts.pageSize ?? 200;
  const maxPages = opts.maxPages ?? 25;
  return fetchPage(0, pageSize).pipe(
    expand((res, index) => {
      const short = res.last === undefined && res.content.length < pageSize;
      const done = res.last === true || res.content.length === 0 || short || index + 1 >= maxPages;
      return done ? of() : fetchPage(index + 1, pageSize);
    }),
    reduce(
      (acc, res, index) => ({
        items: acc.items.concat(res.content),
        totalElements: res.totalElements,
        pagesRead: index + 1,
        lastSeen: res.last === true || res.content.length === 0 || (res.last === undefined && res.content.length < pageSize),
      }),
      { items: [] as T[], totalElements: 0, pagesRead: 0, lastSeen: false },
    ),
    map(acc => ({
      items: acc.items,
      totalElements: Math.max(acc.totalElements, acc.items.length),
      truncated: !acc.lastSeen && acc.items.length < acc.totalElements,
    })),
  );
}
