// Single implementation lives in the shared UI library; this path is kept for existing imports.
export {
  type AsyncSection,
  type AsyncSectionStatus,
  createAsyncSection,
  beginAsyncSection,
  resolveAsyncSection,
  failAsyncSection,
} from '@registerwerk/ui';
