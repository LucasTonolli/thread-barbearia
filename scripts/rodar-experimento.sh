#!/usr/bin/env bash
# Experimento de afinidade de CPU (Q4) no Linux: cenario A com taskset (1 nucleo) e cenario B livre,
# N vezes cada, intercalados; grava resultados/tabela-q4.md a partir dos CSVs reais.
# Uso: scripts/rodar-experimento.sh [CLIENTES=55] [REPETICOES=5] [SEED=42] [INTERVALO_MAX_MS=25] [CORTE_MS=30] [PAGAMENTO_MS=15]
# Aborta (sem gravar a tabela) se alguma execucao falhar, atender menos de 50 clientes ou se o cenario A
# nao enxergar exatamente 1 processador.
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-55}; REPS=${2:-5}; SEED=${3:-42}; IV=${4:-25}; CUT=${5:-30}; PAY=${6:-15}

command -v taskset >/dev/null || { echo "taskset nao encontrado: cenario A indisponivel neste sistema" >&2; exit 1; }
rm -rf out && mkdir out
javac -encoding UTF-8 -d out $(find src -name '*.java')
mkdir -p resultados
rm -f resultados/resultados-A-*.csv resultados/resultados-B-*.csv

PARAMS=(--clientes "$N" --seed "$SEED" --intervalo-max-ms "$IV" --corte-ms "$CUT" --pagamento-ms "$PAY")
for k in $(seq 1 "$REPS"); do
  for c in A B; do
    echo "Cenario $c - execucao $k/$REPS"
    if ! "scripts/rodar-cenario-${c,,}.sh" "${PARAMS[@]}" --execucao "$k" --silencioso >/dev/null; then
      echo "execucao $c/$k falhou; ver logs/execucao-$c-$k.log" >&2; exit 1
    fi
  done
done

# Colunas do CSV: 3=processadores 5=atendidos 6=desist_lotacao 7=desist_sala 10=tempo_total_ms
# 11=espera_em_pe_ms 12=clientes_em_pe 13=espera_sofa_ms 14=espera_pos_ms 15..17=util_b1..b3_pct
rows() { for k in $(seq 1 "$REPS"); do tail -n 1 "resultados/resultados-$1-$k.csv"; done; }
cell() { # cenario coluna casas -> "media ± desvio"
  rows "$1" | awk -F, -v c="$2" -v d="$3" '{v[NR]=$c; s+=$c} END {m=s/NR; for(i=1;i<=NR;i++) q+=(v[i]-m)^2;
    sd=(NR>1)?sqrt(q/(NR-1)):0; printf "%.*f ± %.*f", d, m, d, sd}'
}
minmax() { rows "$1" | awk -F, -v c="$2" 'NR==1{a=$c;b=$c} {if($c<a)a=$c; if($c>b)b=$c} END{if(a==b) print a; else print a "–" b}'; }
total() { rows "$1" | awk -F, -v c="$2" '{s+=$c} END{print s}'; }
mean1() { rows "$1" | awk -F, -v c="$2" '{s+=$c} END{printf "%.1f", s/NR}'; }

for c in A B; do
  rows "$c" | awk -F, -v n=50 '$5<n {print "execucao " $1 "/" $2 " atendeu so " $5 " clientes (< 50): aumente CLIENTES"; bad=1} END{exit bad}' >&2
done
rows A | awk -F, '$3!=1 {print "cenario A/" $2 " viu " $3 " processadores: afinidade nao aplicada"; bad=1} END{exit bad}' >&2

LOGICOS=$(nproc --all)
{
echo "# Tabela Q4 — Monoprocessador vs. Multicore"
echo
echo "Dados reais gerados por \`scripts/rodar-experimento.sh\` em $(date '+%Y-%m-%d %H:%M'). Cada célula é a **média de $REPS"
echo "execuções ± desvio-padrão amostral**. As execuções de A e B foram intercaladas (A1, B1, A2, B2, ...)."
echo "$N clientes chegam em cada execução; quantos foram atendidos está em \"Dados de controle\" (mínimo exigido: 50)."
echo
echo "| Métrica | Cenário A — Monoprocessador (1 núcleo) | Cenário B — Multicore livre ($LOGICOS núcleos lógicos) |"
echo "| --- | --- | --- |"
echo "| Tempo total para atender $N clientes (ms) | $(cell A 10 1) | $(cell B 10 1) |"
echo "| Tempo médio de espera em pé (ms) | $(cell A 11 1) | $(cell B 11 1) |"
echo "| Tempo médio de espera no sofá (ms) | $(cell A 13 1) | $(cell B 13 1) |"
echo "| Tempo médio de espera na fila da POS (ms) | $(cell A 14 2) | $(cell B 14 2) |"
echo "| Utilização do barbeiro B1 (%) | $(cell A 15 1) | $(cell B 15 1) |"
echo "| Utilização do barbeiro B2 (%) | $(cell A 16 1) | $(cell B 16 1) |"
echo "| Utilização do barbeiro B3 (%) | $(cell A 17 1) | $(cell B 17 1) |"
echo
echo "## Dados de controle"
echo
echo "| Item | Cenário A | Cenário B |"
echo "| --- | --- | --- |"
echo "| Processadores visíveis para a JVM (\`Runtime.availableProcessors()\`) | $(minmax A 3) | $(minmax B 3) |"
echo "| Clientes atendidos por execução | $(minmax A 5) de $N | $(minmax B 5) de $N |"
echo "| Desistências por lotação / sala cheia (total) | $(total A 6) / $(total A 7) | $(total B 6) / $(total B 7) |"
echo "| Clientes que passaram pela fila em pé (média) | $(mean1 A 12) | $(mean1 B 12) |"
echo
echo "## Ambiente e parâmetros"
echo
echo "- SO: $(uname -srmo 2>/dev/null || uname -a)"
echo "- CPU: $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ //') — $LOGICOS processadores lógicos"
echo "- JDK: $(java -version 2>&1 | paste -sd '/' -)"
echo "- Parâmetros: $N clientes, 3 barbeiros, lotação 20, corte $CUT ms, pagamento $PAY ms, intervalo entre chegadas"
echo "  sorteado em [0, $IV] ms com semente $SEED (mesma sequência em todas as execuções)."
echo "- Execuções: $REPS por cenário ($((2 * REPS)) no total)."
echo "- Cenário A: \`taskset -c 0 java ...\`. Cenário B: \`java ...\` sem restrição."
echo
echo "## Execuções individuais"
echo
echo "| Cenário | Execução | Proc. | Atendidos | Tempo total (ms) | Em pé (ms) | Sofá (ms) | POS (ms) | B1 (%) | B2 (%) | B3 (%) |"
echo "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
for c in A B; do
  rows "$c" | awk -F, '{printf "| %s | %s | %s | %s | %.1f | %.1f | %.1f | %.2f | %.1f | %.1f | %.1f |\n", $1,$2,$3,$5,$10,$11,$13,$14,$15,$16,$17}'
done
} > resultados/tabela-q4.md
echo "Tabela gravada em resultados/tabela-q4.md"
