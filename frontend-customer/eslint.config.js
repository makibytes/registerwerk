// @ts-check
const eslint = require("@eslint/js");
const { defineConfig } = require("eslint/config");
const tseslint = require("typescript-eslint");
const angular = require("angular-eslint");

module.exports = defineConfig([
  {
    files: ["**/*.ts"],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          // Matches `this.cdr.detectChanges()` and an inject()ed local `cdr.detectChanges()` —
          // scoped to the `cdr` receiver specifically so `fixture.detectChanges()` (TestBed's own
          // trigger, unrelated to this zoneless anti-pattern) is never flagged and specs don't
          // need a blanket rule override.
          selector:
            "CallExpression[callee.property.name='detectChanges'][callee.object.property.name='cdr'], " +
            "CallExpression[callee.property.name='detectChanges'][callee.object.name='cdr']",
          message: "Use cdr.markForCheck() in zoneless Angular components instead of detectChanges().",
        },
      ],
      // This codebase's convention (see TokenSource / MsalTokenSource / CookieTokenSource) is to
      // keep unused parameters in an implemented interface signature, prefixed with `_`, so the
      // shape stays self-documenting at call sites even when one implementation ignores an arg.
      // Recognize that prefix instead of forcing every such signature to `eslint-disable`.
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
      "@angular-eslint/directive-selector": [
        "error",
        {
          type: "attribute",
          prefix: "app",
          style: "camelCase",
        },
      ],
      "@angular-eslint/component-selector": [
        "error",
        {
          type: "element",
          prefix: "app",
          style: "kebab-case",
        },
      ],
    },
  },
  {
    files: ["**/*.html"],
    extends: [
      angular.configs.templateRecommended,
      angular.configs.templateAccessibility,
    ],
    rules: {},
  },
]);
