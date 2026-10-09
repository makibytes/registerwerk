#!/usr/bin/env node

import assert from "node:assert/strict";
import { EXEMPTIONS, MAX_INITCODE, MAX_RUNTIME, deployStateGas, evaluate } from "./check-contract-sizes.mjs";

// forge's own margin fields still measure against EIP-170 / EIP-3860; the script must ignore them.
const fits = (runtime, init = runtime + 25) => ({
  runtime_size: runtime,
  init_size: init,
  runtime_margin: 24576 - runtime,
  init_margin: 49152 - init,
});
// The recorded sizes of a (hypothetical) contract that is over the EIP-7954 limits.
const exemptions = { Big: { runtime: 70000, init: 70100, reason: "test" } };

// The Glamsterdam limits (EIP-7954), not the pre-Glamsterdam ones.
assert.equal(MAX_RUNTIME, 65536);
assert.equal(MAX_INITCODE, 131072);

// Within the limits and not exempted: fine — including a contract that EIP-170 would have refused.
assert.deepEqual(evaluate({ Small: fits(MAX_RUNTIME) }, {}), []);
assert.deepEqual(evaluate({ WasOversize: fits(27451, 27972) }, {}), []);
assert.deepEqual(evaluate({ BigInit: fits(100, MAX_INITCODE) }, {}), []);

// Over the runtime limit, or only over the initcode limit, without an exemption: rejected.
assert.equal(evaluate({ Huge: fits(MAX_RUNTIME + 1) }, {}).length, 1);
assert.equal(evaluate({ Deep: fits(100, MAX_INITCODE + 1) }, {}).length, 1);

// An exempted contract may stay at or below its recorded sizes while still oversize...
assert.deepEqual(evaluate({ Big: fits(70000, 70100) }, exemptions), []);
assert.deepEqual(evaluate({ Big: fits(66000, 66100) }, exemptions), []);
// ...but not grow, in either dimension.
assert.match(evaluate({ Big: fits(70001, 70100) }, exemptions)[0], /grew beyond/);
assert.match(evaluate({ Big: fits(70000, 70101) }, exemptions)[0], /grew beyond/);

// A stale exemption (back within the limits, or no longer built) fails so the list cannot rot.
assert.match(evaluate({ Big: fits(60000) }, exemptions)[0], /remove it from EXEMPTIONS/);
assert.match(evaluate({}, exemptions)[0], /remove it from EXEMPTIONS/);

// One exemption does not cover other contracts.
assert.equal(evaluate({ Big: fits(70000, 70100), Other: fits(MAX_RUNTIME + 1) }, exemptions).length, 1);

// The recorded exemptions must describe themselves.
for (const [name, exemption] of Object.entries(EXEMPTIONS)) {
  assert.ok(exemption.reason?.length > 40, `${name}: exemption needs a reason`);
  assert.ok(exemption.runtime > MAX_RUNTIME || exemption.init > MAX_INITCODE, `${name}: exemption is not oversize any more`);
}

// EIP-8037: (120 account bytes + code bytes) x 1,530 gas per state byte.
assert.equal(deployStateGas(0), 183_600);
assert.equal(deployStateGas(10_000), 15_483_600);

console.log("check-contract-sizes tests passed.");
