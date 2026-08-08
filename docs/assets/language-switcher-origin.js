/*
 * Language links are generated at build time by mkdocs-static-i18n. They are normally
 * root-relative (for example `/de/`), which is usually enough, but some proxy/browser paths
 * normalise them to the host's default HTTP(S) port. Make their origin explicit at runtime.
 *
 * `window.location.origin` intentionally includes the active scheme, host and non-default port,
 * so this works for `http://nibbler:8003`, TLS deployments, and a reverse-proxy host alike.
 */
(() => {
  const preserveCurrentOrigin = () => {
    document.querySelectorAll('.md-select__link[hreflang]').forEach((link) => {
      const href = link.getAttribute('href');
      if (!href) return;

      const destination = new URL(href, window.location.href);
      link.href = new URL(
        `${destination.pathname}${destination.search}${destination.hash}`,
        window.location.origin,
      ).href;
    });
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', preserveCurrentOrigin, { once: true });
  } else {
    preserveCurrentOrigin();
  }
})();
