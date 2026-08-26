/*
 * Language links are generated at build time by mkdocs-static-i18n. They are normally
 * root-relative (for example `/de/`), which is usually enough, but some proxy/browser paths
 * normalise them to the host's default HTTP(S) port. Make their origin explicit at runtime.
 *
 * `window.location.origin` intentionally includes the active scheme, host and non-default port,
 * so this works for `http://nibbler:48003`, TLS deployments, and a reverse-proxy host alike.
 */
(() => {
  const languageLinkSelector = '.md-select__link[hreflang]';

  const preserveLinkOrigin = (link) => {
    const href = link.getAttribute('href');
    if (!href) return;

    const destination = new URL(href, window.location.href);
    link.href = new URL(
      `${destination.pathname}${destination.search}${destination.hash}`,
      window.location.origin,
    ).href;
  };

  const preserveCurrentOrigin = () => {
    document.querySelectorAll(languageLinkSelector).forEach(preserveLinkOrigin);
  };

  // Reapply synchronously at interaction time as well. This closes the small window between
  // Material replacing the language menu and MutationObserver delivering its callback.
  const preserveInteractedLinkOrigin = (event) => {
    const link = event.target?.closest?.(languageLinkSelector);
    if (link) preserveLinkOrigin(link);
  };

  document.addEventListener('pointerdown', preserveInteractedLinkOrigin, true);
  document.addEventListener('click', preserveInteractedLinkOrigin, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', preserveCurrentOrigin, { once: true });
  } else {
    preserveCurrentOrigin();
  }

  // Material may replace header content after navigation or a colour-scheme change. Reapply to
  // newly rendered selector links instead of relying on a single DOMContentLoaded pass.
  new MutationObserver(preserveCurrentOrigin).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
})();
