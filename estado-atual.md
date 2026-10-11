# Estado atual do projeto — Barbearia de Hilzer

> Foto da branch `refactor/integracao-final`, criada a partir da `main` (`d07a7b1`). Verificado compilando e
> executando (JDK 17.0.14, Windows 11, 8 processadores lógicos). Substitui a foto anterior (da `main` em
> `d07a7b1`), que mapeava os 18 gaps da seção 4.
> A branch `feat/integration` (plano B, `fb350a0`) serviu só de doadora de código e ficou obsoleta.

## 1. Resumo

| Frente | Estado |
| --- | --- |
| Barbeiro (`Barber`, `BarberChair`, `BarberState`) | Funcional e testada; `finally` destrava o cliente em qualquer falha |
| Cliente e Espera (`Client`, `WaitingRoom`) | Funcional e testada; FIFO em dois níveis verificada sob concorrência; interrupção tratada antes e depois da chamada |
| Integração (`PosTerminal`, `Capacity`, `AuditLogger`, `Metrics`, `Simulation`, `Main`) | Completa: simulação parametrizável, logs no formato do enunciado, métricas, detecção de deadlock, experimento A/B com dados reais |
| Testes | 10 testes, runner único (`scripts/run-tests.{sh,ps1}`), todos passando |
| Relatório técnico | Não iniciado; insumos em `notas_decisoes.md` e `resultados/tabela-q4.md` |

Execução padrão (`java -cp out barbearia.Main`): 50 clientes com chegadas escalonadas (0–25 ms, seed 42),
todos atendidos, validação final ok, código de saída 0.

## 2. Arquivos (`src/main/java/barbearia/`)

- **`Main`**: argumentos (`--clientes`, `--cenario`, `--seed`, `--intervalo-max-ms`, `--corte-ms`,
  `--pagamento-ms`, `--timeout-s`, `--execucao`, `--silencioso`), registro do ambiente, resumo, CSV, código 2 em falha.
- **`Simulation`**: monta e executa; chegadas escalonadas com semente; encerramento join clientes →
  `stopWhenEmpty` → join barbeiros → métricas → invariantes; `UncaughtExceptionHandler`, timeout, dump de
  threads e `findDeadlockedThreads`; `checkInvariants` com mensagens específicas.
- **barbeiro/** `Barber` (dorme com `wait()`, mede corte/pagamento, `finally` em `serve`), `BarberChair`, `BarberState`.
- **cliente/** `Client` (lotação, espera, timestamps e ordens), `WaitingRoom` (monitor único, FIFO, promoção atômica).
- **integracao/** `PosTerminal` (`ReentrantLock(true)`), `Capacity` (`Semaphore(20, true)`, `tryAcquire`),
  `AuditLogger` (injetável, console/arquivo/memória), `Metrics` (thread-safe, CSV).

## 3. Testes (`tests/`, pacote `tests`)

`BarberManualTest`, `WaitingRoomManualTest`, `FifoIntegrationTest`, `RobustnessTest`, `FailureDetectionTest`,
`CapacityVsRoomTest`, `WaitingRoomStressTest`, `BarberStressTest`, `IntegrationStressTest`,
`IntegrationInvariantsTest` (30 rodadas, 907 verificações). Cobertura e resultados em `tests/tests.md`.

## 4. Gaps da foto anterior e situação

| # | Gap | Situação |
| --- | --- | --- |
| 1 | Sem simulação realista / experimento A/B | **Resolvido**: `Simulation` com chegadas escalonadas e semente; scripts de afinidade; `resultados/tabela-q4.md` com dados reais |
| 2 | Sem métricas | **Resolvido**: `Metrics` com tempos medidos (não configurados), CSV por execução |
| 3 | Logs fora do padrão | **Resolvido**: só os 6 eventos (`enum`), ator lógico, `SAÍDA` com o barbeiro, arquivo `logs/`, sem `println` |
| 4 | Sem detecção de deadlock | **Resolvido**: prazo, dump, `findDeadlockedThreads`, `UncaughtExceptionHandler`, saída 2 |
| 5 | README desatualizado | **Resolvido** (integrantes continuam "(preencher)") |
| 6 | Cliente órfão em `Barber.serve` | **Resolvido**: `finally`; coberto pelo `RobustnessTest` |
| 7 | Interrupção depois de chamado | **Resolvido**: cliente segue para o atendimento; teste reproduz o caso 200/200 vezes |
| 8 | `stop()` abortivo | **Resolvido**: `stopWhenEmpty()` no encerramento normal |
| 9 | `Logger` estático | **Resolvido**: `AuditLogger` com instância |
| 10 | Lotação fora de ordem no log | **Resolvido**: contador alterado dentro do lock do log; teste confere a sequência |
| 11 | Alias e nomes em português | **Resolvido**: `PosTerminal`, `Capacity`, `AuditLogger`; `Barbeiro` removido |
| 12 | Sem teste de invariantes completas | **Resolvido**: `IntegrationInvariantsTest` |
| 13 | FIFO só em cenário controlado | **Resolvido**: FIFO de dois níveis sob carga concorrente |
| 14 | Teste devolvia só `boolean` | **Resolvido**: mensagens com a invariante e os valores |
| 15 | Sem runner único; pacotes errados | **Resolvido** |
| 16 | Thundering herd | **Não refatorado de propósito** (trade-off registrado em `notas_decisoes.md`) |
| 17 | Capacidade vs. sala | **Resolvido**: `CapacityVsRoomTest` mostra que o caminho da sala é defensivo |
| 18 | Sem `.gitignore` para saídas | **Resolvido**: `logs/`, `resultados/*.csv` |

## 5. Pendências que dependem do grupo

- Preencher os integrantes no `README.md`.
- Escrever o relatório técnico (insumos: `notas_decisoes.md`, `notas_relatorio_*.md`, `tests/tests.md`,
  `resultados/tabela-q4.md`).
- Conferir o formato da tabela da Q4 com o enunciado original (o texto do enunciado não está no repositório).
- Opcional: rodar `scripts/rodar-experimento.sh` numa máquina Linux (só a versão PowerShell rodou com afinidade real).
- Decidir o destino das branches `feat/integration` (obsoleta) e `refactor/integracao-final` (PR para a `main`).
