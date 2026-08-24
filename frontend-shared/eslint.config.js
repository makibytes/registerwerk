// @ts-check
// Standalone lint config for the shared UI library — consumed by both frontend-operator and
// frontend-customer via a TS path alias (@registerwerk/ui), so it has no angular.json/project
// of its own and was previously outside both apps' `ng lint` scope (their lintFilePatterns are
// scoped to each app's own src/). node_modules here is a symlink to frontend-operator's, so
// this reuses the exact angular-eslint/eslint/typescript-eslint versions already installed
// there rather than duplicating the dependency.
//
// No app/component-selector-prefix rule here (unlike frontend-operator's/frontend-customer's
// configs): the library's existing components mix prefixes ("rw-data-table",
// "app-page-header") with no single convention to enforce — flagging that is a separate,
// pre-existing naming-consistency question, not something to invent as a side effect of adding
// lint coverage.
const eslint = require("@eslint/js");
const { defineConfig } = require("eslint/config");
const tseslint = require("typescript-eslint");
const angular = require("angular-eslint");

module.exports = defineConfig([
  {
    files: ["projects/ui/src/**/*.ts"],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
  },
  {
    files: ["projects/ui/src/**/*.html"],
    extends: [
      angular.configs.templateRecommended,
      angular.configs.templateAccessibility,
    ],
    rules: {
      // Same escape hatch as frontend-operator's/frontend-customer's configs — `!= null` /
      // `== null` is the idiomatic nullish check used throughout this codebase.
      "@angular-eslint/template/eqeqeq": ["error", { allowNullOrUndefined: true }],
    },
  },
]);
