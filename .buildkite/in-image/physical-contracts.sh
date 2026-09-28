#!/usr/bin/env bash
set -euo pipefail
cd physical-runtime
python3 -m venv /tmp/tokito-contracts-venv
/tmp/tokito-contracts-venv/bin/pip install --disable-pip-version-check -r tools/requirements.txt
/tmp/tokito-contracts-venv/bin/python -m unittest discover -s tools -p 'test_*.py'
cargo test --workspace --locked
