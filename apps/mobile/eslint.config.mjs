import tseslint from "typescript-eslint";

/** Flat ESLint config — TypeScript sources only under src/. */
export default tseslint.config(
  { ignores: ["node_modules/**", "scripts/**", "android/**", "ios/**", "babel.config.js"] },
  ...tseslint.configs.recommended,
  {
    files: ["src/**/*.{ts,tsx}", "index.ts"],
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/consistent-type-imports": "error",
    },
  },
);
