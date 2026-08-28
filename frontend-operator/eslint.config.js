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
          selector: "CallExpression[callee.property.name='detectChanges']",
          message: "Use cdr.markForCheck() in zoneless Angular components instead of detectChanges().",
        },
      ],
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
    rules: {
      // `!= null` / `== null` is the idiomatic nullish check used throughout this codebase's
      // inline templates (guards against both null and undefined in one comparison). The rule
      // ships this exact escape hatch for that reason; keep strict `===`/`!==` everywhere else.
      "@angular-eslint/template/eqeqeq": ["error", { allowNullOrUndefined: true }],
    },
  },
  {
    // `fixture.detectChanges()` is TestBed's own trigger for change detection in specs —
    // unrelated to the zoneless `cdr.detectChanges()` anti-pattern the rule above targets.
    files: ["**/*.spec.ts"],
    rules: {
      "no-restricted-syntax": "off",
    },
  },
]);
