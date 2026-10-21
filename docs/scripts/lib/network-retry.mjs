/*
 * Retry policy for the docs browser check (docs/scripts/verify-browser.mjs).
 *
 * Chromium watches the kernel's network events and aborts requests that are in flight with
 * `net::ERR_NETWORK_CHANGED` when an interface or address changes. On a CI runner that happens when Docker starts
 * a container (a new veth, `docker0` coming up, IPv6 address assignment a moment later), through no fault of the
 * page under test. Such an attempt tells us nothing about the docs, so it is run again from scratch on a fresh
 * browser. Everything else is a real result and is never retried:
 *
 *  - a console or page error that is not a network change fails the check at once;
 *  - an exception from an assertion or a timeout fails the check at once, unless the browser also reported a network
 *    change during that attempt (the exception is then most likely a consequence of the aborted request, and a real
 *    defect still fails on the last attempt, so nothing is hidden by the extra run).
 */

const NETWORK_CHANGED = 'net::ERR_NETWORK_CHANGED';

export function isNetworkChange(text) {
  return String(text ?? '').includes(NETWORK_CHANGED);
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Runs `run()` (one complete browser check) up to `attempts` times.
 *
 * `run` resolves to `{ errors, failedRequests }`: the console and page errors of the attempt and a description of each
 * failed request. It may also throw; set `networkChangeSeen = true` on the error when the browser reported a network
 * change before the exception, as verify-browser.mjs does.
 *
 * Resolves with the result of the last attempt (the caller asserts that `errors` is empty) or rethrows the exception
 * of the last attempt.
 */
export async function withNetworkChangeRetry(run, { attempts = 3, pauseMs = 3000, log = console.warn, sleep = wait } = {}) {
  for (let attempt = 1; ; attempt++) {
    let result;
    try {
      result = await run();
    } catch (error) {
      const environmental = isNetworkChange(error?.message) || error?.networkChangeSeen === true;
      if (!environmental || attempt >= attempts) throw error;
      log(`::warning::Docs browser check attempt ${attempt}/${attempts} was disturbed by a network change `
        + `(${String(error?.message).split('\n')[0]}); running it again`);
      await sleep(pauseMs);
      continue;
    }

    const { errors, failedRequests = [] } = result;
    if (errors.length > 0 && errors.every(isNetworkChange) && attempt < attempts) {
      log(`::warning::Docs browser check attempt ${attempt}/${attempts} was disturbed by a network change `
        + `(${failedRequests.join(', ') || 'request not recorded'}); running it again`);
      await sleep(pauseMs);
      continue;
    }
    return result;
  }
}
