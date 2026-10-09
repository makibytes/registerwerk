#!/usr/bin/env node
// Points frontend-shared/node_modules at the node_modules of the app that is being built.
//
// frontend-shared is compiled as part of whichever app imports it (`@registerwerk/ui`), so its
// bare imports (`@angular/core`, `rxjs`, ...) have to resolve to THAT app's single copy. A
// committed symlink to one fixed app breaks the other: the customer build either found a second
// Angular (operator's) and crashed at runtime inside shared components, or found nothing (CI
// installs only one app) and failed with NG2012/TS18046. The apps call this from `postinstall`
// and from the `pre*` hooks of their build/start/test/lint scripts, so the link is always
// re-pointed before anything compiles. The Dockerfiles do the same with `ln -sfn`.
//
// Run with the app directory as cwd (npm does that for package scripts):
//   node ../frontend-shared/link-node-modules.mjs
import { existsSync, lstatSync, readlinkSync, rmSync, symlinkSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const sharedDir = dirname(fileURLToPath(import.meta.url));
const appModules = resolve(process.cwd(), 'node_modules');
const link = join(sharedDir, 'node_modules');

if (!existsSync(join(appModules, '@angular', 'core', 'package.json'))) {
  console.error(
    `link-node-modules: ${appModules} has no @angular/core. Run "npm ci" in ${process.cwd()} first.`,
  );
  process.exit(1);
}

const target = relative(sharedDir, appModules);
let current = null;
try {
  const stat = lstatSync(link);
  if (!stat.isSymbolicLink()) {
    console.error(`link-node-modules: ${link} is a real directory; refusing to replace it.`);
    process.exit(1);
  }
  current = readlinkSync(link);
} catch {
  // no link yet
}

if (current !== target) {
  if (current !== null) rmSync(link);
  symlinkSync(target, link, 'dir');
  console.log(`link-node-modules: frontend-shared/node_modules -> ${target}`);
}
