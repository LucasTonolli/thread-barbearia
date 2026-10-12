# Testes

Todos os testes ficam no pacote `tests`, sem dependência externa (sem JUnit): cada um é uma classe com
`main` que imprime o que verificou e **sai com código diferente de zero se algo falhar**.

## Como rodar

```bash
scripts/run-tests.sh        # Linux / Git Bash
.\scripts\run-tests.ps1     # Windows PowerShell
```

Os dois compilam `src/` e `tests/` (`javac -encoding UTF-8 -Xlint:all`), rodam os 10 testes em sequência e
terminam com `TODOS OS 10 TESTES PASSARAM` (código 0) ou com a lista dos que falharam (código 1). Leva
cerca de 50 s.

Para rodar um teste isolado: `java -cp out tests.<Nome>`.

## O que cada teste cobre

| Teste | Tipo | Cobertura |
| --- | --- | --- |
| `BarberManualTest` | unitário/integração | 1 barbeiro e 1 cliente ponta a ponta; 3 barbeiros e 4 clientes; exclusão mútua da POS; barbeiro dorme (`SLEEPING`) e acorda com a chegada do cliente |
| `WaitingRoomManualTest` | unitário | promoção em pé → sofá; sofá sem fila em pé; sofá vazio; 18º cliente recusado (sala com 17); FIFO de 17 clientes; cliente do sofá interrompido promove o primeiro em pé |
| `FifoIntegrationTest` | FIFO | 6 clientes entrando um a um (caso controlado) **e** 17 clientes liberados juntos por `CountDownLatch` (caso concorrente): ordem de chamada == ordem de entrada e promoção na ordem da fila em pé. Espera no monitor da sala, sem polling |
| `RobustnessTest` | falhas | gap 6: exceção inesperada em `chair.occupy` não deixa o cliente preso; gap 7: interrupção depois de `iAmNext` (200 tentativas, caminho exercitado e contado); desistência antes de ser chamado; interrupção durante o corte |
| `FailureDetectionTest` | diagnóstico | deadlock sintético (dois locks em ordem cruzada) reconhecido por `ThreadMXBean.findDeadlockedThreads`; timeout da `Simulation` com dump; `Main` sai com 2 (timeout), 0 (normal) e 64 (argumento inválido) |
| `CapacityVsRoomTest` | gap 17 | com 3 clientes nas cadeiras e 17 na sala, o 21º é barrado pela `Capacity`, não pela sala; com chegadas escalonadas a sala nunca recusa; em rajada recusa muito menos que a `Capacity` |
| `WaitingRoomStressTest` | estresse | 20 rodadas, 40 clientes contra um "barbeiro falso": sem exceção, sem atendimento duplicado, sem cliente preso |
| `BarberStressTest` | estresse | 10 rodadas, 50 clientes, 3 barbeiros: sem deadlock, pagamentos == atendimentos |
| `IntegrationStressTest` | estresse | 20 rodadas de 100 clientes em rajada pela `Simulation`; cada falha lista a invariante violada e os valores |
| `IntegrationInvariantsTest` | invariantes | 30 rodadas da `Simulation` (rajada, chegadas rápidas, moderadas e a configuração calibrada), ver abaixo |

## Invariantes verificadas por rodada (`IntegrationInvariantsTest`)

Cada verificação que falha imprime **qual invariante quebrou e com quais valores** (ex.:
`atendidos + desistentes == N (49 + 0 != 50)`).

- **Recursos:** lotação ≤ 20; sofá ≤ 4; em pé ≤ 13; cadeiras ocupadas ≤ 3; no máximo 1 pagamento simultâneo
  na POS.
- **Contas:** atendidos + desistentes = N; pagamentos = atendimentos; soma dos barbeiros = métricas;
  desistências por lotação e por sala batem com os contadores da `Capacity` e da `WaitingRoom`.
- **Estado final:** vagas todas devolvidas (lotação 0, 20 permissões); sala vazia; sem timeout; nenhuma
  exceção não tratada (`UncaughtExceptionHandler`).
- **FIFO sob concorrência real:** ordem de chamada == ordem de entrada na sala; ordem de chamada == ordem de
  chegada ao sofá; promoção em pé → sofá na ordem da fila em pé; todo atendido foi chamado do sofá.
- **Log:** todas as linhas no formato `[mm:ss.SSS] [Thread-Cliente-NN|Thread-Barbeiro-NN] EVENTO: mensagem`
  com os seis eventos do enunciado; timestamps em ordem cronológica; ator lógico (`ATENDIMENTO` = barbeiro,
  demais = cliente); lotação no log variando de 1 em 1, nunca acima de 20 e voltando a 0; 1 `CHEGADA` por
  cliente; 1 `SAÍDA` por admitido; 2 `PAGAMENTO` por atendido; a `SAÍDA` de cada atendido cita o barbeiro
  que recebeu o pagamento.

Além disso, um teste unitário da `Capacity` (20 entradas, 21ª recusada sem bloquear, vaga devolvida e
reutilizada).

## Última execução (Windows 11, 8 processadores lógicos, JDK 17.0.14)

```
BarberManualTest            13 passaram, 0 falharam
WaitingRoomManualTest       18 passaram, 0 falharam
FifoIntegrationTest         OK (controlado e concorrente)
RobustnessTest              26 passaram, 0 falharam (gap 7 exercitado em 200/200 tentativas)
FailureDetectionTest        10 passaram, 0 falharam
CapacityVsRoomTest          36 passaram, 0 falharam
WaitingRoomStressTest       0 de 20 rodadas com problema
BarberStressTest            0 de 10 rodadas com problema
IntegrationStressTest       20/20 rodadas aprovadas
IntegrationInvariantsTest   907 verificações passaram, 0 falharam (30 rodadas, 1750 clientes)
TODOS OS 10 TESTES PASSARAM
```

## Provas negativas feitas durante a refatoração

Para garantir que os testes detectam os defeitos que dizem detectar, cada correção foi revertida
temporariamente e o teste correspondente foi rodado:

- sem a correção do gap 7 em `WaitingRoom.enter`, o `RobustnessTest` acusa 200 atendimentos "fantasma" em
  200 tentativas;
- com o contador de lotação incrementado fora do lock do log (gap 10), o `IntegrationInvariantsTest` acusa
  `lotacao varia de 1 em 1` já nas primeiras rodadas.
