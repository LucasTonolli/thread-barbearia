#!/usr/bin/env bash
# Cenario A: JVM restrita a 1 nucleo logico (Linux, taskset). Uso: rodar-cenario-a.sh [args do Main...]
set -euo pipefail
cd "$(dirname "$0")/.."
command -v taskset >/dev/null || { echo "taskset nao encontrado: cenario A indisponivel neste sistema" >&2; exit 1; }
taskset -c 0 java -cp out barbearia.Main --cenario A "$@"
