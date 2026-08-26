import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const links = [
  fakeLink('/customer/dashboard/'),
  fakeLink('http://nibbler.local/de/customer/dashboard/'),
  fakeLink('/fr/customer/dashboard/?tab=kyc#documents'),
];
let observerCallback;
const documentListeners = new Map();

const location = new URL('http://nibbler.local:48003/de/customer/dashboard/');
const context = {
  URL,
  window: { location },
  document: {
    readyState: 'complete',
    documentElement: {},
    querySelectorAll: (selector) => {
      assert.equal(selector, '.md-select__link[hreflang]');
      return links;
    },
    addEventListener: (type, callback, capture) => {
      assert.equal(capture, true);
      documentListeners.set(type, callback);
    },
  },
  MutationObserver: class {
    constructor(callback) {
      observerCallback = callback;
    }

    observe(target, options) {
      assert.equal(target, context.document.documentElement);
      assert.equal(options.childList, true);
      assert.equal(options.subtree, true);
    }
  },
};

const script = readFileSync(new URL('../docs/assets/language-switcher-origin.js', import.meta.url), 'utf8');
vm.runInNewContext(script, context, { filename: 'language-switcher-origin.js' });

assert.equal(links[0].href, 'http://nibbler.local:48003/customer/dashboard/');
assert.equal(links[1].href, 'http://nibbler.local:48003/de/customer/dashboard/');
assert.equal(links[2].href, 'http://nibbler.local:48003/fr/customer/dashboard/?tab=kyc#documents');

const replacement = fakeLink('/it/customer/dashboard/');
links.push(replacement);
observerCallback();
assert.equal(replacement.href, 'http://nibbler.local:48003/it/customer/dashboard/');

const lastMomentReplacement = fakeLink('http://nibbler.local/es/customer/dashboard/');
documentListeners.get('click')({ target: { closest: () => lastMomentReplacement } });
assert.equal(lastMomentReplacement.href, 'http://nibbler.local:48003/es/customer/dashboard/');

console.log('Docs language links preserve the active scheme, host, and port.');

function fakeLink(rawHref) {
  return {
    href: '',
    getAttribute: (name) => name === 'href' ? rawHref : null,
  };
}
