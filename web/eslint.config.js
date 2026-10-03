import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'coverage'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  {
    // Service Worker: свои глобальные объекты (self, clients), DOM нет.
    files: ['src/push/serviceWorker.ts'],
    languageOptions: { globals: globals.serviceworker },
  },
  {
    // Скрипты, которые запускает Node.js (например, scripts/generate-icons.mjs).
    files: ['scripts/**/*.mjs'],
    languageOptions: { globals: globals.node },
  },
)
