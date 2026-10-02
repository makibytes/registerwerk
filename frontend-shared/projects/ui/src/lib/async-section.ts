/**
 * `stale` = a refresh failed after at least one successful load: the previous data is still shown
 * (templates keyed on `'error'` keep rendering it) but must be marked as possibly out of date.
 */
export type AsyncSectionStatus = 'pending' | 'updating' | 'ready' | 'stale' | 'error';

export interface AsyncSection<T> {
  data: T;
  status: AsyncSectionStatus;
  hasLoaded: boolean;
  /** Epoch millis of the last successful load. */
  lastLoadedAt?: number;
}

export function createAsyncSection<T>(data: T): AsyncSection<T> {
  return { data, status: 'pending', hasLoaded: false };
}

export function beginAsyncSection<T>(section: AsyncSection<T>): AsyncSection<T> {
  return {
    ...section,
    status: section.hasLoaded ? 'updating' : 'pending',
  };
}

export function resolveAsyncSection<T>(section: AsyncSection<T>, data: T): AsyncSection<T> {
  return {
    data,
    status: 'ready',
    hasLoaded: true,
    lastLoadedAt: Date.now(),
  };
}

export function failAsyncSection<T>(section: AsyncSection<T>): AsyncSection<T> {
  return {
    ...section,
    status: section.hasLoaded ? 'stale' : 'error',
  };
}
