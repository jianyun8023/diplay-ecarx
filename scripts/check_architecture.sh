#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
npx --yes --package=@ast-grep/cli@0.51.2 -c '
  ast-grep test --skip-snapshot-tests &&
  node --test scripts/test_architecture_paths.mjs &&
  ast-grep scan shared/src common/src mobile/src samples/maphost/src
'
