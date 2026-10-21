import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isNetworkChange, withNetworkChangeRetry } from './network-retry.mjs';

const NETWORK = 'Failed to load resource: net::ERR_NETWORK_CHANGED';
const NOT_FOUND = 'Failed to load resource: the server responded with a status of 404 (Not Found)';

/** A `run` that answers from a script, one entry per call; an Error entry is thrown. */
function scripted(entries) {
  const calls = [];
  const run = async () => {
    calls.push(calls.length + 1);
    const next = entries[Math.min(calls.length - 1, entries.length - 1)];
    if (next instanceof Error) throw next;
    return next;
  };
  return { run, calls };
}

const options = () => {
  const logs = [];
  const pauses = [];
  return { logs, pauses, opts: { attempts: 3, pauseMs: 1, log: (line) => logs.push(line), sleep: async (ms) => { pauses.push(ms); } } };
};

describe('isNetworkChange', () => {
  it('recognises exactly the network-change error', () => {
    assert.equal(isNetworkChange(NETWORK), true);
    assert.equal(isNetworkChange('page.goto: net::ERR_NETWORK_CHANGED at http://127.0.0.1:48003/'), true);
    assert.equal(isNetworkChange(NOT_FOUND), false);
    assert.equal(isNetworkChange('net::ERR_CONNECTION_REFUSED'), false);
    assert.equal(isNetworkChange(undefined), false);
  });
});

describe('withNetworkChangeRetry', () => {
  it('returns a clean first result without logging or waiting', async () => {
    const { run, calls } = scripted([{ errors: [], failedRequests: [] }]);
    const { logs, pauses, opts } = options();
    const result = await withNetworkChangeRetry(run, opts);
    assert.deepEqual(result.errors, []);
    assert.equal(calls.length, 1);
    assert.deepEqual([logs, pauses], [[], []]);
  });

  it('runs again after an attempt whose only errors are network changes, and names the failed request', async () => {
    const { run, calls } = scripted([
      { errors: [NETWORK], failedRequests: ['http://127.0.0.1:48003/assets/x.js (net::ERR_NETWORK_CHANGED)'] },
      { errors: [], failedRequests: [] },
    ]);
    const { logs, pauses, opts } = options();
    const result = await withNetworkChangeRetry(run, opts);
    assert.deepEqual(result.errors, []);
    assert.equal(calls.length, 2);
    assert.equal(pauses.length, 1);
    assert.match(logs[0], /::warning::.*attempt 1\/3.*assets\/x\.js/);
  });

  it('does not retry when a real error is mixed in with the network change', async () => {
    const { run, calls } = scripted([{ errors: [NETWORK, NOT_FOUND], failedRequests: [] }, { errors: [], failedRequests: [] }]);
    const { opts } = options();
    const result = await withNetworkChangeRetry(run, opts);
    assert.deepEqual(result.errors, [NETWORK, NOT_FOUND]);
    assert.equal(calls.length, 1);
  });

  it('does not retry a plain real error', async () => {
    const { run, calls } = scripted([{ errors: [NOT_FOUND], failedRequests: [] }, { errors: [], failedRequests: [] }]);
    const result = await withNetworkChangeRetry(run, options().opts);
    assert.deepEqual(result.errors, [NOT_FOUND]);
    assert.equal(calls.length, 1);
  });

  it('gives up after the last attempt and hands back the network errors for the caller to fail on', async () => {
    const { run, calls } = scripted([{ errors: [NETWORK], failedRequests: ['u (net::ERR_NETWORK_CHANGED)'] }]);
    const { pauses, opts } = options();
    const result = await withNetworkChangeRetry(run, opts);
    assert.deepEqual(result.errors, [NETWORK]);
    assert.equal(calls.length, 3);
    assert.equal(pauses.length, 2);
  });

  it('retries an exception that is itself a network change (page.goto aborted)', async () => {
    const { run, calls } = scripted([
      new Error('page.goto: net::ERR_NETWORK_CHANGED at http://127.0.0.1:48003/\nCall log: ...'),
      { errors: [], failedRequests: [] },
    ]);
    const { logs, opts } = options();
    await withNetworkChangeRetry(run, opts);
    assert.equal(calls.length, 2);
    assert.match(logs[0], /disturbed by a network change \(page\.goto: net::ERR_NETWORK_CHANGED at http:\/\/127\.0\.0\.1:48003\/\)/);
  });

  it('retries another exception only when the browser reported a network change during that attempt', async () => {
    const consequence = Object.assign(new Error('Timeout 30000ms exceeded.'), { networkChangeSeen: true });
    const { run, calls } = scripted([consequence, { errors: [], failedRequests: [] }]);
    await withNetworkChangeRetry(run, options().opts);
    assert.equal(calls.length, 2);
  });

  it('rethrows an unrelated exception at once', async () => {
    const failure = new assert.AssertionError({ message: 'The homepage must render both Mermaid diagrams' });
    const { run, calls } = scripted([failure, { errors: [], failedRequests: [] }]);
    await assert.rejects(withNetworkChangeRetry(run, options().opts), (error) => error === failure);
    assert.equal(calls.length, 1);
  });

  it('rethrows a network-change exception once the attempts are used up', async () => {
    const { run, calls } = scripted([new Error('page.goto: net::ERR_NETWORK_CHANGED')]);
    await assert.rejects(withNetworkChangeRetry(run, options().opts), /ERR_NETWORK_CHANGED/);
    assert.equal(calls.length, 3);
  });
});
