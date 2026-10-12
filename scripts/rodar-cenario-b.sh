#!/usr/bin/env bash
# Cenario B: multicore livre. Uso: rodar-cenario-b.sh [args do Main...]
set -euo pipefail
cd "$(dirname "$0")/.."
java -cp out barbearia.Main --cenario B "$@"
