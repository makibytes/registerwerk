const OBJECT_URL_LIFETIME_MS = 10_000;

/**
 * Starts a browser download and keeps the object URL alive long enough for browsers that
 * consume synthetic anchor clicks asynchronously (notably Safari).
 */
export function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName.trim() || 'download';
  link.style.display = 'none';
  document.body.appendChild(link);

  try {
    link.click();
  } finally {
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), OBJECT_URL_LIFETIME_MS);
  }
}
