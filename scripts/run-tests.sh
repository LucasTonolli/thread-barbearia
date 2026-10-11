#!/usr/bin/env bash
# Compila src/ e tests/ e executa todos os testes. Sai com codigo != 0 se algum falhar.
# Uso: scripts/run-tests.sh
set -uo pipefail
cd "$(dirname "$0")/.."

TESTS=(
  BarberManualTest
  WaitingRoomManualTest
  FifoIntegrationTest
  RobustnessTest
  FailureDetectionTest
  CapacityVsRoomTest
  WaitingRoomStressTest
  BarberStressTest
  IntegrationStressTest
  IntegrationInvariantsTest
)

rm -rf out && mkdir -p out
if ! javac -encoding UTF-8 -Xlint:all -d out $(find src tests -name '*.java'); then
  echo "!!! falha na compilacao"
  exit 1
fi

failed=()
for t in "${TESTS[@]}"; do
  echo "################ $t"
  if ! java -cp out "tests.$t"; then
    echo "!!! $t FALHOU"
    failed+=("$t")
  fi
done

echo
if [ ${#failed[@]} -eq 0 ]; then
  echo "TODOS OS ${#TESTS[@]} TESTES PASSARAM"
  exit 0
fi
echo "FALHARAM ${#failed[@]} de ${#TESTS[@]}: ${failed[*]}"
exit 1
