# Notas de decisões (insumo para o relatório)

Decisões de implementação e observações técnicas da branch `refactor/integracao-final`, em tópicos. Não é o
relatório. Cada item aponta onde a decisão aparece no código ou qual medição a sustenta.

## 1. Nomenclatura e base

- Base oficial: `main` (`d07a7b1`). A `feat/integration` (plano B, `fb350a0`) foi usada só como doadora de
  código; não houve merge (nomes divergentes criariam duplicatas).
- Código em inglês; logs, comentários e documentação em português.
- Renomeações com `git mv` num commit isolado: `CaixaPOS` → `PosTerminal`, `CapacityControl` → `Capacity`,
  `Logger` → `AuditLogger`; alias `Barbeiro` removido; constantes do `Main` em inglês.
- **Pacotes mantidos** (`barbeiro/`, `cliente/`, `integracao/`), mesmo em português: a divisão de frentes do
  README depende deles. `Main` e `Simulation` ficam na raiz `barbearia/`.
- Todos os testes no pacote `tests` (antes havia `barbearia.cliente` e `main.java.barbearia.cliente`).
- Textos acentuados no código de produção estão como escapes `\uXXXX`: compila igual com ou sem
  `-encoding UTF-8` (o padrão do JDK 17 no Windows é Cp1252).

## 2. O que veio do plano B e onde a `main` venceu

| Item | Decisão |
| --- | --- |
| `Simulation`, `Metrics`, `AuditLogger` (instância, ator lógico, memória para testes) | Aproveitados e adaptados aos nomes da `main` |
| `Client` controla a própria vaga (`tryEnter`/`leave` dentro de `run`) | **Plano B adotado**: na `main` isso ficava num lambda do `Main`. Com o cliente dono do ciclo, `CHEGADA`/`SAÍDA` saem com o ator certo, a vaga volta num `finally` do próprio cliente e as métricas de desistência ficam no mesmo lugar |
| `getNext(barberName, chairId)` registrando `ATENDIMENTO` dentro do monitor | **Plano B adotado**: é o único jeito de `ATENDIMENTO` sair antes da `PROMOÇÃO` correspondente, como no exemplo do enunciado |
| `IntegrationInvariantsTest`, `WaitingRoomManualTest` reescrito, scripts de cenário | Aproveitados e ampliados (FIFO em dois níveis, auditoria do log linha a linha, mensagens com valores) |
| Contadores da `main` (`PosTerminal` com `AtomicInteger`, `Capacity` com admitidos/rejeitados) | **`main` mantida** |
| Logs ASCII sem acento (plano B) | **Divergência**: o enunciado usa `PROMOÇÃO`, `SAÍDA`, `Lotação`; mantidos com acento, arquivo em UTF-8 |
| Eventos extras do plano B (`EXPEDIENTE`, `DORMINDO`, `ACORDOU`, `AVISO`) | Removidos: vocabulário restrito aos 6 eventos (`enum AuditLogger.Event`) |
| Arquivo de log via `Files.newBufferedWriter` (plano B) | **Trocado por `FileOutputStream`**: o canal NIO é `InterruptibleChannel` e seria fechado se uma thread com a flag de interrupção ligada escrevesse no log (o gap 7 deixa essa flag ligada de propósito) |
| `rodar-cenario-a.ps1` do plano B | **Corrigido**: `cmd /c "start /wait ..."` devolvia 0 mesmo com a JVM saindo com 2 (verificado). Agora `cmd /v:on /c "... & exit !errorlevel!"` |
| Gap 7 | O plano B não tratava (só promovia o próximo quando o interrompido estava no sofá) |

## 3. Sincronização

- **Sem busy-wait:** barbeiro dorme com `waitingRoom.wait()`; cliente espera com `wait()` na sala e depois no
  próprio monitor (`waitUntilAttended`). `Thread.sleep` só simula corte, pagamento e intervalo de chegada.
- **Sem lost wake-up:** o barbeiro testa `getNext()` e chama `wait()` dentro de `synchronized (waitingRoom)`,
  o mesmo monitor em que `enter()` enfileira o cliente e faz `notifyAll()`. Um cliente não consegue entrar
  entre o teste e o `wait()` do barbeiro. Do lado do cliente, `completeAttendance()` grava a flag e notifica
  sob o monitor do cliente, e `waitUntilAttended()` testa a flag num `while` sob o mesmo monitor.
- **Promoção atômica:** em `getNext()`, retirar do sofá, marcar a chamada, promover o primeiro em pé, gravar
  ordens/timestamps e registrar `ATENDIMENTO`/`PROMOÇÃO` acontecem sob o monitor da sala.
- **Invariante da sala:** fila em pé não vazia ⇒ sofá cheio (a promoção preenche a vaga na hora, inclusive
  quando um cliente do sofá desiste). Com as duas filas FIFO, ordem de chamada == ordem de entrada na sala;
  verificado em 30 rodadas do `IntegrationInvariantsTest`.
- **POS sem deadlock:** um único lock (`ReentrantLock` da `PosTerminal`). Quem o segura não tenta pegar outro
  lock bloqueante: dentro dele só há `sleep` e o log. O barbeiro não segura o monitor da sala nem a cadeira
  ao pedir a POS (a cadeira é um `synchronized` curto, liberado antes). Sem espera circular.
- **Ordem global de locks:** monitor da sala → lock do log; lock da POS → lock do log; lock do log → nada (o
  `Supplier` avaliado dentro dele só mexe em atômicos e em `Semaphore.tryAcquire`/`release`, que não
  bloqueiam). O lock do log é sempre o mais interno, então não há ciclo.
- **Onde o fairness atua:**
  - `ReentrantLock(true)` da POS: os barbeiros bloqueiam em `lockInterruptibly()` e são atendidos na ordem da
    fila FIFO do AQS. É aqui que a justiça tem efeito real.
  - `Semaphore(20, true)` da `Capacity`: **na prática, sem efeito**. Só usamos `tryAcquire()`, que segundo a
    documentação do Java ignora a política justa ("barging"), e ninguém fica na fila do semáforo, porque o
    cliente desiste em vez de esperar. Mantido por clareza de intenção.
  - Monitor da sala (`synchronized`): **não é justo**. A FIFO de clientes não vem do lock, e sim das duas
    `ArrayDeque` com ordem explícita. Qual barbeiro pega o próximo cliente depende do escalonador. Observado:
    distribuição equilibrada (ex.: 18/18/18 atendimentos em A2 e A4; 18/17/20 em A1).
- **`volatile` só em flags e timestamps** com um único escritor (`iAmNext`, `attendedBy`, tempos do `Client`,
  `running`). Invariantes compostas (filas, contador + permissão da lotação, lotação + linha de log) ficam sob
  `synchronized` ou lock.
- **Thundering herd (gap 16, trade-off):** com monitor único, cada `enter()` e cada `getNext()` fazem
  `notifyAll()`, acordando todos os clientes (até 17) e barbeiros (3) que esperam na sala; só um prossegue e
  os demais reavaliam a condição e voltam a dormir. Custo O(n) por evento, aceitável nessa escala, e a
  correção é simples de argumentar. Alternativas: `Condition` separada para barbeiros e para clientes, ou um
  semáforo por cliente. Não refatorado de propósito.

## 4. Robustez

- **Gap 6:** `Barber.serve` libera a cadeira e chama `completeAttendance()` num `finally` (aninhado, para o
  segundo rodar mesmo se o primeiro falhar). Uma exceção inesperada continua propagando até o
  `UncaughtExceptionHandler`; o cliente sai como "não concluído".
- **Gap 7:** se o `wait()` lança `InterruptedException` mas o cliente já está marcado (`isTheNext()`), ele
  **segue para o atendimento** (`enter` devolve `true`) e a flag de interrupção é restaurada. Alternativa
  descartada: devolver o cliente à fila, o que exigiria desfazer a promoção já feita. Contador
  `getInterruptedAfterCallCount()` para auditoria.
- **`waitUntilAttended()` adia a interrupção:** depois de chamado, o cliente está na cadeira; sair antes
  devolveria a vaga com o corte em andamento e a lotação contaria um cliente a menos. Termina sempre, porque
  o `finally` do barbeiro chama `completeAttendance()`.
- **Gap 8:** encerramento normal com `stopWhenEmpty()`; `stop()` + `interrupt()` só no caminho de emergência.
- **Detecção de falhas:** a thread principal espera num monitor (`Simulation.Tracker`) que acorda quando
  todos os clientes terminam **ou** quando alguma thread morre (handler), sem esperar o timeout inteiro. Em
  falha: `findDeadlockedThreads` + dump de todas as threads (lock esperado, dono, monitores retidos) e saída 2.
  As threads da simulação são daemon, para uma simulação travada não segurar a JVM depois do diagnóstico.

## 5. Logs

- Formato `[mm:ss.SSS] [Thread-Ator] EVENTO: mensagem`, com timestamp calculado **dentro** do lock de escrita:
  as linhas saem em ordem cronológica (verificado em todas as rodadas do teste de invariantes).
- **Ator lógico:** `ATENDIMENTO` é do barbeiro; `CHEGADA`, `ESPERA`, `PROMOÇÃO`, `PAGAMENTO` e `SAÍDA` são do
  cliente, mesmo quando a linha é escrita pela thread do barbeiro (`PAGAMENTO`) ou da sala (`PROMOÇÃO`).
- Mapeamento dos eventos antigos:

  | Antigo | Novo |
  | --- | --- |
  | `DESISTENCIA` (lotação) | `CHEGADA: Barbearia lotada (Lotação: 20/20). Desistiu.` |
  | `DESISTENCIA` (sala cheia) | `ESPERA: Sala de espera cheia (...)` + `SAÍDA: Sala de espera cheia. Desistiu. Saiu (...)` |
  | `CHAMADA` | `ATENDIMENTO: Chamou Cliente-NN do sofá para a Cadeira N.` (barbeiro) |
  | `CORTE_CONCLUIDO` | `PAGAMENTO: Corte concluído. Solicitando máquina POS.` |
  | `PAGAMENTO` (início, ator barbeiro) | `PAGAMENTO: Máquina POS liberada. Pagando com Barbeiro-NN.` (ator cliente) |
  | `PAGAMENTO_CONCLUIDO`, `CONCLUSAO`, `SAIDA` | `SAÍDA: Pagamento concluído com Barbeiro-NN. Saiu (Lotação: N/20).` |
  | `SAIDA_SEM_ATENDIMENTO`, `INTERRUPCAO` | `SAÍDA: Desistiu da espera (interrompido). Saiu (...)` ou `SAÍDA: Saiu sem concluir o atendimento. (...)` |
  | `INICIO`, `FINALIZADO` (barbeiro) | removidos (fora do vocabulário) |

- **Ambiguidade:** o exemplo do enunciado tem um só `PAGAMENTO` por cliente. Mantivemos dois (pedido da POS e
  posse da POS), ambos com o evento `PAGAMENTO`, porque o segundo mostra no log a espera na fila da POS e qual
  barbeiro a obteve.
- **Gap 10:** a `Capacity` faz `tryAcquire` + contador **dentro** do `Supplier` avaliado sob o lock do log;
  na saída, decrementa e devolve a permissão também lá dentro. A lotação impressa varia de 1 em 1 na ordem
  das linhas (verificado; com o incremento fora do lock, o teste acusa a falha já nas primeiras rodadas).
  Trade-off: a atomicidade da lotação passa a depender do lock do logger (acoplamento documentado na classe).
- O ambiente da execução (processadores visíveis, Java, parâmetros) e o diagnóstico de falha vão em linhas
  `# ...`, fora do fluxo de eventos.
- Console via `System.out` (segue a codificação do terminal); arquivo sempre em UTF-8.

## 6. Métricas (`Metrics`)

- Tempo total: do início das threads dos barbeiros até o `join` do último barbeiro (inclui o período de
  chegadas).
- Espera em pé: entrada no sofá − entrada em pé, só entre os atendidos que ficaram em pé (o CSV informa
  quantos). Espera no sofá: chamada − entrada no sofá, sobre todos os atendidos. Espera na POS: só o tempo
  bloqueado em `PosTerminal.acquire`.
- Utilização = (corte + pagamento **medidos** com `nanoTime`) / tempo total, por barbeiro (B1/B2/B3). Não
  inclui a espera pela POS. Antes, o `Barber` somava a duração configurada.
- Desistências por motivo: lotação, sala cheia, interrompido, atendimento não concluído. Desistentes não
  entram nas médias de espera.

## 7. Calibração e experimento

- **Calibração** (corte 30 ms, pagamento 15 ms; a POS atende no máximo 1 cliente a cada 15 ms):
  - chegadas em [0, 20] ms (média 10 ms): desistências em 8 de 10 execuções (A e B, sementes 1–5), algumas
    com menos de 50 atendidos;
  - [0, 25] ms: 52 execuções (26 sementes × A e B) com os 50 atendidos; nas sementes 1–5, em média 32 (B) a
    38 (A) clientes passaram pela fila em pé;
  - [0, 27] e [0, 28] ms: todos atendidos, mas a espera em pé no cenário B cai para 14–16 ms (contra 72 ms em
    [0, 25]), ou seja, a contenção praticamente some.
  - Padrão escolhido: **[0, 25] ms**. Rajada (`--intervalo-max-ms 0`) continua disponível e reproduz o
    comportamento antigo (com 50 clientes: 18 atendidos, 30 desistências por lotação e 2 por sala cheia, numa
    execução medida).
- **55 clientes no experimento:** ao rodar 5× com semente 42, o cenário A atendeu 49 em 2 de 5 execuções (o
  último cliente encontrou a lotação cheia). Seguindo a regra de aumentar N em vez de matar a contenção:
  com 55 clientes, 20 execuções de calibração (10 A + 10 B) e as 10 do experimento atenderam 54–55. O padrão
  do `Main` continua 50.
- **Ambiente:** Windows 11 Home Single Language (10.0.26200), Intel Core i5-8265U (4 núcleos físicos,
  8 lógicos), OpenJDK Temurin 17.0.14. 5 execuções por cenário, intercaladas, semente 42 em todas (mesma
  sequência de chegadas). Detalhes e números em `resultados/tabela-q4.md`.
- **Prova do cenário A:** `Runtime.availableProcessors()` = 1 em todas as execuções de A (1ª linha do log e
  coluna `processadores` do CSV); o script aborta se for diferente.
- **Resultado (média ± desvio, 55 clientes):** tempo total A 1415 ± 53 ms, B 953 ± 23 ms. Coerente com a
  referência do plano B (A ≈ 1268, B ≈ 855 ms com 50 clientes e chegadas em [0, 20] ms); com 50 clientes e
  [0, 25] ms medimos A ≈ 1315 e B ≈ 874 ms.
- **Investigação da diferença A × B** (feita antes de gravar a tabela):
  - todas as execuções de A têm **uma pausa global** de 180–470 ms perto de 0,7 s (nenhuma linha de log de
    nenhuma thread nesse intervalo); nenhuma execução de B tem pausa acima de 45 ms;
  - não é GC: com `-Xlog:gc` a única coleta levou 2,3 ms (SerialGC, escolhido pela JVM ao ver 1 CPU);
  - é o **JIT C2**: com `-XX:TieredStopAtLevel=1` (só C1) ou `-Xint` a pausa some e o tempo total de A cai
    para ~990–1030 ms (1 execução cada);
  - com `-XX:CompilerThreadPriority=5` (prioridade normal) as pausas caem para no máximo 77 ms e o total para
    ~1160–1290 ms (3 execuções), contra 114–341 ms e ~1290–1365 ms com o padrão. Isso é **consistente com** a
    thread do compilador rodando com prioridade elevada no Windows e monopolizando o único núcleo;
  - controle: com `-XX:ActiveProcessorCount=1` mas sem afinidade (a JVM "acha" que tem 1 CPU, porém roda em 8),
    o tempo foi ~955 ms, igual a B (2 execuções, via `taskset` simulado no teste do `.sh`). A lentidão vem da
    restrição física do núcleo, não das escolhas ergonômicas da JVM.
  - Conclusão para a Q4: o trabalho "útil" é quase todo `Thread.sleep` (I/O simulado), então o paralelismo
    real pouco importa para as próprias threads; a diferença vem de a JVM (JIT, principalmente) disputar o
    único núcleo com a aplicação.
- **Cuidado ao ler a utilização em A:** ela é medida em tempo de parede do `sleep`. Se a pausa do JIT acontece
  enquanto um barbeiro está em `sleep`, o tempo parado conta como "ocupado". Por isso as execuções A2 e A4 têm
  um barbeiro com 57–59% e os outros com 78–87%, apesar de os três terem feito 18 atendimentos cada.
- Os cenários atenderam quase o mesmo número de clientes (A: 54–55; B: 55), o que deve ser mencionado ao
  comparar tempos totais.
- `scripts/rodar-experimento.sh` (Linux/`taskset`) **não rodou com afinidade real**: a lógica (salvaguardas,
  médias, tabela) foi testada no Git Bash com um `taskset` simulado.

## 8. Testes

- 10 testes, sem JUnit, cada um com código de saída ≠ 0 em falha; runner `scripts/run-tests.{sh,ps1}`.
- Nenhum teste sincroniza com `sleep`: as esperas são em `CountDownLatch` ou no monitor da própria sala
  (`enter()` notifica a cada cliente enfileirado). Exceções conscientes:
  - no teste do gap 7, um `sleep(1)` com o monitor da sala retido **alarga a janela** para a interrupção chegar
    antes da notificação; as verificações valem em qualquer intercalação, e o contador prova que o caminho
    foi exercitado (200/200);
  - `join(timeout)` é usado para afirmar que uma thread **continua** bloqueada (não há como observar isso sem
    um prazo).
- Provas negativas feitas: sem a correção do gap 7, o `RobustnessTest` acusa 200 atendimentos fantasma; com o
  contador da lotação fora do lock do log, o `IntegrationInvariantsTest` acusa a sequência quebrada.
- **Gap 17:** o caminho "sala fisicamente cheia" só dispara se houver cadeira vazia com 17 na sala (3 + 17 = 20 =
  lotação). Com chegadas escalonadas: 0 recusas em 10 execuções. Em rajada é possível (vimos 2 numa execução de
  50 clientes com o `Main`), mas o `CapacityVsRoomTest` mediu 0 recusas da sala contra 1600 da `Capacity` em
  20 rajadas de 100 clientes.

## 9. Outras ambiguidades resolvidas

- **Formato da tabela da Q4:** o texto do enunciado não está no repositório. A tabela segue a descrição da
  tarefa: 5 métricas (tempo total, esperas em pé / sofá / POS, utilização por barbeiro B1/B2/B3) × 2 cenários,
  mais dados de controle e ambiente. Conferir com o enunciado.
- **"Ordem de chegada" na FIFO:** medida na entrada da sala (sob o monitor), não na `CHEGADA`. Entre passar
  pela `Capacity` e entrar na sala há duas seções críticas distintas; um cliente pode ultrapassar outro ali
  (visto no log: `CHEGADA` 11, 12, 13 e `ESPERA` 11, 13, 12). O enunciado exige FIFO no sofá e na promoção,
  que é o que se garante e testa.
- **Cenário como rótulo:** `--cenario A|B` só nomeia log/CSV; a afinidade é aplicada de fora, pelos scripts,
  antes de a JVM iniciar.
- **`Start-Process -Affinity`** citado no README antigo não existe no PowerShell 5.1; usamos `start /affinity`
  do `cmd`.
- Código de saída 64 para argumento inválido (convenção `EX_USAGE`); 2 para qualquer falha da simulação.
