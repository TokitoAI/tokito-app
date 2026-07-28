import tseslint from "typescript-eslint";

/** Flat ESLint config — TypeScript sources only under src/. */
export default tseslint.config(
  { ignores: ["node_modules/**", "scripts/**"] },
  ...tseslint.configs.recommended,
  {
    files: ["src/**/*.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/consistent-type-imports": "error",
    },
  },
);
