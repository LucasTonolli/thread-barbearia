# Tabela Q4 — Monoprocessador vs. Multicore

Dados reais gerados por `scripts/rodar-experimento.ps1` em 2026-10-10 21:27. Cada célula é a **média de 5
execuções ± desvio-padrão amostral**. As execuções de A e B foram intercaladas (A1, B1, A2, B2, ...).
55 clientes chegam em cada execução; quantos foram atendidos está em "Dados de controle" (mínimo exigido: 50).

| Métrica | Cenário A — Monoprocessador (1 núcleo) | Cenário B — Multicore livre (8 núcleos lógicos) |
| --- | --- | --- |
| Tempo total para atender 55 clientes (ms) | 1.415,0 ± 53,4 | 953,2 ± 23,0 |
| Tempo médio de espera em pé (ms) | 214,7 ± 28,3 | 81,7 ± 8,6 |
| Tempo médio de espera no sofá (ms) | 94,3 ± 4,7 | 59,4 ± 1,8 |
| Tempo médio de espera na fila da POS (ms) | 8,33 ± 4,84 | 2,48 ± 0,75 |
| Utilização do barbeiro B1 (%) | 80,0 ± 12,5 | 92,5 ± 1,6 |
| Utilização do barbeiro B2 (%) | 82,0 ± 14,1 | 88,5 ± 1,8 |
| Utilização do barbeiro B3 (%) | 86,7 ± 0,8 | 89,1 ± 3,3 |

## Dados de controle

| Item | Cenário A | Cenário B |
| --- | --- | --- |
| Processadores visíveis para a JVM (`Runtime.availableProcessors()`) | 1 | 8 |
| Clientes atendidos por execução | 54–55 de 55 | 55 de 55 |
| Desistências por lotação / sala cheia (total) | 2 / 0 | 0 / 0 |
| Clientes que passaram pela fila em pé (média) | 47,4 | 44,6 |

## Definições das métricas

- **Tempo total:** do início das threads dos barbeiros até o `join` do último barbeiro (inclui o período de
  chegadas).
- **Espera em pé:** entrada no sofá − entrada na fila em pé, só entre os atendidos que ficaram em pé.
- **Espera no sofá:** chamada pelo barbeiro − entrada no sofá, sobre todos os atendidos.
- **Espera na fila da POS:** tempo bloqueado em `PosTerminal.acquire` (posse − pedido).
- **Utilização:** (corte + pagamento medidos com `System.nanoTime`) / tempo total, por barbeiro. Não inclui
  a espera pela POS.

## Ambiente e parâmetros

- SO: Microsoft Windows 11 Home Single Language (versão 10.0.26200)
- CPU: Intel(R) Core(TM) i5-8265U CPU @ 1.60GHz — 4 núcleos físicos, 8 processadores lógicos
- JDK: openjdk version "17.0.14" 2025-01-21 / OpenJDK Runtime Environment Temurin-17.0.14+7 (build 17.0.14+7) / OpenJDK 64-Bit Server VM Temurin-17.0.14+7 (build 17.0.14+7, mixed mode, sharing)
- Parâmetros: 55 clientes, 3 barbeiros, lotação 20 (sofá 4 + em pé 13 + 3 cadeiras), corte 30 ms,
  pagamento 15 ms, intervalo entre chegadas sorteado em [0, 25] ms com semente 42
  (mesma sequência de chegadas em todas as execuções).
- Execuções: 5 por cenário (10 no total).
- Cenário A: `cmd /v:on /c "start "" /b /wait /affinity 1 java ..."` (máscara 1 = só o processador lógico 0,
  aplicada antes de a JVM iniciar). Cenário B: `java ...` sem restrição.
- Comando: `.\scripts\rodar-experimento.ps1 -Clientes 55 -Repeticoes 5 -Seed 42 -IntervaloMaxMs 25 -CorteMs 30 -PagamentoMs 15`

## Execuções individuais

| Cenário | Execução | Proc. | Atendidos | Tempo total (ms) | Em pé (ms) | Sofá (ms) | POS (ms) | B1 (%) | B2 (%) | B3 (%) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 1 | 1 | 55 | 1.336,4 | 168,7 | 87,7 | 5,39 | 86,4 | 88,1 | 85,9 |
| A | 2 | 1 | 54 | 1.417,6 | 229,2 | 96,1 | 12,81 | 58,8 | 85,9 | 86,3 |
| A | 3 | 1 | 55 | 1.442,9 | 217,8 | 95,1 | 5,10 | 87,2 | 90,4 | 86,3 |
| A | 4 | 1 | 54 | 1.479,4 | 244,0 | 100,3 | 14,34 | 78,5 | 57,0 | 87,1 |
| A | 5 | 1 | 55 | 1.398,5 | 213,8 | 92,3 | 4,02 | 89,1 | 88,5 | 88,0 |
| B | 1 | 8 | 55 | 954,9 | 88,9 | 59,9 | 2,34 | 92,5 | 89,2 | 88,0 |
| B | 2 | 8 | 55 | 988,9 | 90,1 | 62,2 | 3,78 | 92,3 | 85,6 | 85,1 |
| B | 3 | 8 | 55 | 934,2 | 70,0 | 57,6 | 1,89 | 90,4 | 90,4 | 94,1 |
| B | 4 | 8 | 55 | 931,4 | 76,2 | 58,2 | 2,11 | 94,9 | 89,4 | 89,7 |
| B | 5 | 8 | 55 | 956,8 | 83,5 | 59,3 | 2,30 | 92,4 | 88,1 | 88,9 |

CSVs de cada execução: `resultados/resultados-<cenário>-<k>.csv` (não versionados). Logs completos:
`logs/execucao-<cenário>-<k>.log`.