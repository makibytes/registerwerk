#!/usr/bin/env node

import assert from "node:assert/strict";
import { EXEMPTIONS, evaluate } from "./check-contract-sizes.mjs";

const fits = (runtime, init = runtime + 25) => ({
  runtime_size: runtime,
  init_size: init,
  runtime_margin: 24576 - runtime,
  init_margin: 49152 - init,
});
const exemptions = { Big: { runtime: 30000, init: 30100, reason: "test" } };

// Within the limits and not exempted: fine.
assert.deepEqual(evaluate({ Small: fits(24576) }, {}), []);

// Over the runtime limit, or only over the initcode limit, without an exemption: rejected.
assert.equal(evaluate({ Huge: fits(24577) }, {}).length, 1);
assert.equal(evaluate({ Deep: fits(100, 49153) }, {}).length, 1);

// An exempted contract may stay at or below its recorded sizes while still oversize...
assert.deepEqual(evaluate({ Big: fits(30000, 30100) }, exemptions), []);
assert.deepEqual(evaluate({ Big: fits(27000, 27100) }, exemptions), []);
// ...but not grow, in either dimension.
assert.match(evaluate({ Big: fits(30001, 30100) }, exemptions)[0], /grew beyond/);
assert.match(evaluate({ Big: fits(30000, 30101) }, exemptions)[0], /grew beyond/);

// A stale exemption (back within the limits, or no longer built) fails so the list cannot rot.
assert.match(evaluate({ Big: fits(24000) }, exemptions)[0], /remove it from EXEMPTIONS/);
assert.match(evaluate({}, exemptions)[0], /remove it from EXEMPTIONS/);

// One exemption does not cover other contracts.
assert.equal(evaluate({ Big: fits(30000, 30100), Other: fits(25000) }, exemptions).length, 1);

// The recorded exemptions must describe themselves.
for (const [name, exemption] of Object.entries(EXEMPTIONS)) {
  assert.ok(exemption.reason?.length > 40, `${name}: exemption needs a reason`);
  assert.ok(exemption.runtime > 24576 || exemption.init > 49152, `${name}: exemption is not oversize any more`);
}

console.log("check-contract-sizes tests passed.");
