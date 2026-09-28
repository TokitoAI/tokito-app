#!/usr/bin/env bash
set -euo pipefail
cd apps/mobile
npm ci
npm run release:validate
npx expo install --check
npx expo-doctor
npm run audit:test
npm run audit
npm run forbid-js
npm run typecheck
npm run lint
npm test
