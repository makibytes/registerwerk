export type AsyncSectionStatus = 'pending' | 'updating' | 'ready' | 'error';

export interface AsyncSection<T> {
  data: T;
  status: AsyncSectionStatus;
  hasLoaded: boolean;
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
  };
}

export function failAsyncSection<T>(section: AsyncSection<T>): AsyncSection<T> {
  return {
    ...section,
    status: section.hasLoaded ? 'updating' : 'error',
  };
}
