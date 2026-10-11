// @ts-check
const eslint = require('@eslint/js');
const { defineConfig, globalIgnores } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = defineConfig([
  // The generated API client is not ours to style. It is regenerated on every build, so a
  // finding in it cannot be fixed — only re-created — and thousands of them would bury the
  // findings in code somebody actually wrote.
  //
  // This is also why noUnusedLocals is off in tsconfig.json and the equivalent rule is
  // enforced here instead: the compiler cannot be scoped, the linter can.
  globalIgnores(['src/app/core/api/generated/**', 'dist/**', '.angular/**']),

  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'app', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'app', style: 'kebab-case' },
      ],

      // Replaces the compiler's noUnusedLocals/noUnusedParameters, scoped to our source.
      // Leading underscore opts a parameter out, which route guards need: their signature
      // is fixed by Angular and often only the second argument is used.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
      ],

      // `any` defeats the reason this project generates its models. Error, not warning.
      '@typescript-eslint/no-explicit-any': 'error',

      // An un-awaited promise in a component is how a navigation silently does nothing.
      // Angular's Router returns one from navigate(); `void` it deliberately or handle it.
      '@typescript-eslint/no-floating-promises': 'off',
    },
  },

  {
    files: ['**/*.spec.ts'],
    rules: {
      // Tests assert on malformed payloads on purpose — a proxy's HTML error page, a body
      // with no code — and typing those precisely would be asserting they are well-formed.
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },

  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
