# Registro de decisões

Por que o código é como é. Cada item traz a decisão, o motivo e, quando existe, a medição ou o teste que a
sustenta. Para o funcionamento geral, ver [visao-geral.md](visao-geral.md) e
[diagramas-sincronizacao.md](diagramas-sincronizacao.md).

## 1. Convenções

- Código (classes, métodos, variáveis) em inglês; logs, comentários e documentação em português.
- Os pacotes `barbeiro/`, `cliente/` e `integracao/` continuam em português porque espelham a divisão de
  frentes do grupo. `Main` e `Simulation` ficam na raiz `barbearia/`.
- Todos os testes ficam no pacote `tests`, sem JUnit: cada um é um `main` que sai com código ≠ 0 em falha.
- Textos acentuados no código de produção são escritos como escapes `\uXXXX`. Assim o código compila igual
  com ou sem `-encoding UTF-8` (o padrão do JDK 17 no Windows é Cp1252).
- Códigos de saída do `Main`: 0 sucesso; 2 qualquer falha da simulação; 64 argumento inválido (convenção
  `EX_USAGE`).

## 2. Responsabilidades das classes

- **O `Client` controla a própria vaga** (`tryEnter` e `leave` dentro de `run`), em vez de o `Main` envolvê-lo
  num lambda. Assim `CHEGADA` e `SAÍDA` saem com o ator certo, a vaga volta num `finally` do próprio cliente
  e as desistências são contadas no mesmo lugar.
- **`ATENDIMENTO` é registrado dentro de `WaitingRoom.getNext(barberName, chairId)`**, sob o monitor da sala.
  É o único jeito de a linha sair antes da `PROMOÇÃO` correspondente, como no exemplo do enunciado.
- **`Capacity` e `WaitingRoom` são separadas.** A lotação vale do início ao fim da visita (até depois do
  pagamento) e recusa sem esperar; a sala só cuida da fila e bloqueia o cliente até ser chamado. Juntar as
  duas aumentaria a disputa no monitor da sala, que já é o mais concorrido. Custo aceito: entre passar pela
  `Capacity` e entrar na sala, um cliente pode ultrapassar outro (ver §8).
- **`Simulation` separada do `Main`**: o `Main` e os testes de integração (`IntegrationInvariantsTest`,
  `IntegrationStressTest`, `CapacityVsRoomTest`, `FailureDetectionTest`) executam a mesma orquestração.

## 3. Sincronização

- **Sem busy-wait:** o barbeiro dorme com `waitingRoom.wait()`; o cliente espera com `wait()` na sala e
  depois no próprio monitor (`waitUntilAttended`). `Thread.sleep` só simula corte, pagamento e intervalo de
  chegada.
- **Sem lost wake-up:** o barbeiro testa `getNext()` e chama `wait()` dentro de `synchronized (waitingRoom)`,
  o mesmo monitor em que `enter()` enfileira o cliente e faz `notifyAll()`. Nenhum cliente consegue entrar
  entre o teste e o `wait()`. Do lado do cliente, `completeAttendance()` grava a flag e notifica sob o monitor
  do cliente, onde `waitUntilAttended()` testa a mesma flag num `while`.
- **Promoção atômica:** em `getNext()`, retirar do sofá, marcar a chamada, promover o primeiro em pé, gravar
  ordens e tempos e registrar `ATENDIMENTO`/`PROMOÇÃO` acontecem sob o monitor da sala.
- **Invariante da sala:** fila em pé não vazia ⇒ sofá cheio (a promoção preenche a vaga na hora, inclusive
  quando um cliente do sofá desiste). Com as duas filas FIFO, a ordem de chamada é a ordem de entrada na sala;
  o `IntegrationInvariantsTest` confere isso em 30 rodadas.
- **POS sem deadlock:** um único lock (`ReentrantLock` da `PosTerminal`). Quem o segura não pede outro lock
  bloqueante: dentro dele só há `sleep` e o log. O barbeiro não segura o monitor da sala nem a cadeira ao pedir
  a POS (a cadeira é um `synchronized` curto, liberado antes).
- **Ordem global de locks:** monitor da sala → lock do log; lock da POS → lock do log; lock do log → nada (o
  `Supplier` avaliado dentro dele só mexe em atômicos e em `Semaphore.tryAcquire`/`release`, que não
  bloqueiam). O lock do log é sempre o mais interno, então não há ciclo.
- **Onde o fairness atua:**
  - `ReentrantLock(true)` da POS: os barbeiros bloqueiam em `lockInterruptibly()` e são atendidos na ordem da
    fila FIFO do AQS. É o único lugar em que a justiça tem efeito real.
  - `Semaphore(20, true)` da `Capacity`: **na prática, sem efeito**. `tryAcquire()` ignora a política justa
    ("barging", segundo a documentação do Java), e ninguém fica na fila do semáforo, porque o cliente desiste
    em vez de esperar. Mantido por clareza de intenção.
  - Monitor da sala (`synchronized`): **não é justo**. A FIFO de clientes vem das duas `ArrayDeque`, não do
    lock. Qual barbeiro pega o próximo cliente depende do escalonador; na prática a divisão fica equilibrada
    (ex.: 18/18/18 e 18/17/20 atendimentos).
- **`volatile` só em flags e timestamps** com um único escritor (`iAmNext`, `attendedBy`, tempos do `Client`,
  `running`). Invariantes compostas (filas, contador + permissão da lotação, lotação + linha de log) ficam sob
  `synchronized` ou lock.
- **Thundering herd (trade-off aceito):** com monitor único, cada `enter()` e cada `getNext()` fazem
  `notifyAll()`, acordando todos os clientes (até 17) e barbeiros (3) que esperam na sala; só um prossegue e
  os demais voltam a dormir. Custo O(n) por evento, aceitável nessa escala, e a correção fica simples de
  argumentar. Alternativas, se a escala crescer: `Condition` separada para barbeiros e para clientes, ou um
  semáforo por cliente.

## 4. Robustez

- **O cliente nunca fica preso:** `Barber.serve` libera a cadeira e chama `completeAttendance()` num `finally`
  (aninhado, para o segundo rodar mesmo se o primeiro falhar). Uma exceção inesperada continua propagando até
  o `UncaughtExceptionHandler`, e o cliente sai como "não concluído".
- **Interrupção depois de chamado:** se o `wait()` lança `InterruptedException` mas o cliente já está marcado
  (`isTheNext()`), ele segue para o atendimento (`enter` devolve `true`) e a flag de interrupção é restaurada.
  Sem isso, o cliente iria embora enquanto o barbeiro o atende (atendimento "fantasma"). Alternativa
  descartada: devolvê-lo à fila, o que exigiria desfazer a promoção já feita. O contador
  `getInterruptedAfterCallCount()` permite auditar o caso.
- **`waitUntilAttended()` adia a interrupção:** depois de chamado, o cliente está na cadeira; sair antes
  devolveria a vaga com o corte em andamento. A espera sempre termina, porque o `finally` do barbeiro chama
  `completeAttendance()`.
- **Encerramento:** normal com `stopWhenEmpty()`; `stop()` + `interrupt()` só no caminho de emergência.
- **Detecção de falhas:** a thread principal espera num monitor (`Simulation.Tracker`) que acorda quando todos
  os clientes terminam **ou** quando alguma thread morre, sem esperar o prazo inteiro. Em falha:
  `findDeadlockedThreads` + dump de todas as threads (lock esperado, dono, monitores retidos) e saída 2. As
  threads da simulação são daemon, para que uma simulação travada não segure a JVM depois do diagnóstico.
- **Log em arquivo via `FileOutputStream`**, não `Files.newBufferedWriter`: o canal NIO é `InterruptibleChannel`
  e seria fechado se uma thread com a flag de interrupção ligada (caso acima) escrevesse no log.

## 5. Logs

- Formato `[mm:ss.SSS] [Thread-Ator] EVENTO: mensagem`, com o timestamp calculado **dentro** do lock de
  escrita: as linhas saem em ordem cronológica (conferido em todas as rodadas do teste de invariantes).
- **Vocabulário fechado:** só os 6 eventos do enunciado (`enum AuditLogger.Event`). Eventos de ciclo de vida do
  barbeiro (início, fim, dormir) não são registrados.
- **Ator lógico:** `ATENDIMENTO` é do barbeiro; `CHEGADA`, `ESPERA`, `PROMOÇÃO`, `PAGAMENTO` e `SAÍDA` são do
  cliente, mesmo quando a linha é escrita pela thread do barbeiro (`PAGAMENTO`) ou da sala (`PROMOÇÃO`).
- **Desistências** usam o mesmo vocabulário:
  - lotação cheia: `CHEGADA: Barbearia lotada (Lotação: 20/20). Desistiu.`;
  - sala cheia: `ESPERA: Sala de espera cheia (...)` + `SAÍDA: Sala de espera cheia. Desistiu. Saiu (...)`;
  - interrompido: `SAÍDA: Desistiu da espera (interrompido). Saiu (...)`;
  - falha do barbeiro: `SAÍDA: Saiu sem concluir o atendimento. Saiu (...)`.
- **Dois `PAGAMENTO` por cliente**, embora o exemplo do enunciado tenha um: o pedido da POS e a posse da POS.
  O segundo mostra no log a espera na fila da POS e qual barbeiro a obteve.
- **Lotação em ordem:** a `Capacity` faz `tryAcquire` + contador **dentro** do `Supplier` avaliado sob o lock
  do log, e na saída decrementa e devolve a permissão também lá dentro. A lotação impressa varia de 1 em 1 na
  ordem das linhas (com o incremento fora do lock, o teste acusa a falha já nas primeiras rodadas). Custo: a
  atomicidade da lotação depende do lock do logger (acoplamento documentado na classe).
- Acentos mantidos (`PROMOÇÃO`, `SAÍDA`, `Lotação`), como no enunciado; o arquivo é sempre UTF-8 e o console
  segue a codificação do terminal.
- Ambiente da execução e diagnóstico de falha vão em linhas `# ...`, fora do fluxo de eventos.

## 6. Métricas (`Metrics`)

- **Tempo total:** do início das threads dos barbeiros até o `join` do último barbeiro (inclui o período de
  chegadas).
- **Espera em pé:** entrada no sofá − entrada em pé, só entre os atendidos que ficaram em pé (o CSV informa
  quantos). **Espera no sofá:** chamada − entrada no sofá, sobre todos os atendidos. **Espera na POS:** só o
  tempo bloqueado em `PosTerminal.acquire`.
- **Utilização** = (corte + pagamento **medidos** com `nanoTime`) / tempo total, por barbeiro. Não inclui a
  espera pela POS e não usa a duração configurada.
- Desistências contadas por motivo (lotação, sala cheia, interrompido, não concluído); desistentes não entram
  nas médias de espera.

## 7. Parâmetros e experimento

### Intervalo de chegada: [0, 25] ms

Com corte de 30 ms e pagamento de 15 ms, a POS atende no máximo 1 cliente a cada 15 ms. Medições:

| Chegadas | Resultado |
| --- | --- |
| [0, 20] ms | desistências em 8 de 10 execuções (A e B, sementes 1–5), algumas com menos de 50 atendidos |
| **[0, 25] ms** | **52 execuções (26 sementes × A e B) com os 50 atendidos**; 32 (B) a 38 (A) clientes passam pela fila em pé, em média |
| [0, 27] e [0, 28] ms | todos atendidos, mas a espera em pé no cenário B cai para 14–16 ms (contra 72 ms em [0, 25]): a contenção praticamente some |

`--intervalo-max-ms 0` continua disponível para simular chegadas simultâneas (com 50 clientes, numa execução
medida: 18 atendidos, 30 desistências por lotação e 2 por sala cheia).

### 55 clientes no experimento

Com 50 clientes e semente 42, o cenário A atendeu 49 em 2 de 5 execuções (o último cliente encontrou a lotação
cheia). Em vez de afrouxar as chegadas, o que eliminaria a contenção, o experimento usa 55 clientes: nas 20
execuções de calibração e nas 10 do experimento foram atendidos 54–55. O padrão do `Main` continua 50.

### Ambiente e método

- Windows 11 Home Single Language (10.0.26200), Intel Core i5-8265U (4 núcleos físicos, 8 lógicos), OpenJDK
  Temurin 17.0.14.
- 5 execuções por cenário, intercaladas (A1, B1, A2, ...), semente 42 em todas (mesma sequência de chegadas).
- Cenário A com `start /affinity 1` aplicado antes de a JVM iniciar (o `Start-Process` do PowerShell 5.1 não
  tem `-Affinity`). `Runtime.availableProcessors()` foi 1 em todas as execuções de A; o script aborta se não for.
- `--cenario A|B` só nomeia log e CSV: a afinidade é aplicada de fora, pelos scripts.
- `cmd /c "start /wait ..."` devolve 0 mesmo quando a JVM sai com 2. Por isso o script usa
  `cmd /v:on /c "... & exit !errorlevel!"`.
- `scripts/rodar-experimento.sh` (Linux/`taskset`) **não rodou com afinidade real**: a lógica (salvaguardas,
  médias, tabela) foi testada no Git Bash com um `taskset` simulado.
- Resultados em [resultados/tabela-q4.md](resultados/tabela-q4.md): tempo total A 1415 ± 53 ms, B 953 ± 23 ms
  (55 clientes). Com 50 clientes e as mesmas chegadas: A ≈ 1315, B ≈ 874 ms.

### Por que o cenário A é mais lento

- Todas as execuções de A têm **uma pausa global** de 180–470 ms perto de 0,7 s (nenhuma linha de log de
  nenhuma thread nesse intervalo); nenhuma execução de B tem pausa acima de 45 ms.
- Não é GC: com `-Xlog:gc`, a única coleta levou 2,3 ms (SerialGC, escolhido pela JVM ao ver 1 CPU).
- É o **JIT C2**: com `-XX:TieredStopAtLevel=1` (só C1) ou `-Xint` a pausa some e o tempo total de A cai para
  ~990–1030 ms (1 execução cada).
- Com `-XX:CompilerThreadPriority=5` (prioridade normal), as pausas caem para no máximo 77 ms e o total para
  ~1160–1290 ms (3 execuções), contra 114–341 ms e ~1290–1365 ms com o padrão. Isso é **consistente com** a
  thread do compilador rodando com prioridade elevada no Windows e monopolizando o único núcleo.
- Controle: com `-XX:ActiveProcessorCount=1` mas sem afinidade (a JVM "acha" que tem 1 CPU, porém roda em 8), o
  tempo foi ~955 ms, igual a B (2 execuções). A lentidão vem da restrição física do núcleo, não das escolhas
  ergonômicas da JVM.
- Em resumo: o trabalho das threads é quase todo `Thread.sleep` (espera simulada), então o paralelismo real
  pouco importa para elas; a diferença vem de a JVM, principalmente o JIT, disputar o único núcleo com a
  aplicação.

### Cuidados ao ler os números

- A utilização em A é medida em tempo de parede do `sleep`. Se a pausa do JIT acontece enquanto um barbeiro
  está em `sleep`, o tempo parado conta como "ocupado". Por isso há execuções de A com um barbeiro em 57–59% e
  os outros em 78–87%, apesar de os três terem feito 18 atendimentos cada.
- Os cenários não atendem exatamente o mesmo número de clientes (A: 54–55; B: 55).

## 8. Interpretações do enunciado

- **FIFO a partir da entrada na sala.** A ordem garantida é a de entrada na sala (sob o monitor), não a de
  `CHEGADA`. Entre a `Capacity` e a sala há duas seções críticas distintas, e um cliente pode ultrapassar outro
  ali (visto no log: `CHEGADA` 11, 12, 13 e `ESPERA` 11, 13, 12). O enunciado exige FIFO no sofá e na promoção
  em pé → sofá, que é o que se garante e testa.
- **Recusa por sala cheia é defensiva.** Com a lotação em 20 e 3 cadeiras, a sala (17) só fica cheia com uma
  cadeira vazia, ou seja, entre um barbeiro terminar e chamar o próximo. Com chegadas escalonadas: 0 recusas
  em 10 execuções. Em rajada acontece (2 numa execução de 50 clientes), mas o `CapacityVsRoomTest` mediu 0
  recusas da sala contra 1600 da `Capacity` em 20 rajadas de 100 clientes.
- **Tabela da Q4:** 5 métricas (tempo total; esperas em pé, no sofá e na POS; utilização por barbeiro B1/B2/B3)
  × 2 cenários, mais dados de controle e ambiente.

## 9. Testes

- Runner único: `scripts/run-tests.{sh,ps1}` (10 testes). Cobertura em [tests/tests.md](tests/tests.md).
- Os testes escritos na refatoração não sincronizam com `sleep`: esperam em `CountDownLatch` ou no monitor da
  própria sala (`enter()` notifica a cada cliente enfileirado). Exceções conscientes:
  - no teste da interrupção depois de chamado, um `sleep(1)` com o monitor da sala retido **alarga a janela**
    para a interrupção chegar antes da notificação. As verificações valem em qualquer intercalação, e o
    contador prova que o caminho foi exercitado (200 de 200 tentativas);
  - `join(timeout)` é usado para afirmar que uma thread **continua** bloqueada (não há como observar isso sem
    um prazo).
- Dois testes anteriores à refatoração ainda usam `sleep` como espera e foram mantidos como estão:
  - `BarberManualTest` (caso 4) espera 100 ms para o barbeiro dormir antes de conferir o estado `SLEEPING`;
    o barbeiro não avisa quando adormece, então a alternativa seria polling do estado;
  - o "barbeiro falso" do `WaitingRoomStressTest` chama `getNext()` 80 vezes com `sleep(2)` entre elas. É o
    gerador de carga do teste, não o código da aplicação; clientes que sobrarem na sala contam como falha.
- Provas negativas: sem a correção da interrupção depois de chamado, o `RobustnessTest` acusa 200
  atendimentos fantasma; com o contador da lotação fora do lock do log, o `IntegrationInvariantsTest` acusa a
  sequência quebrada.
