# Problema da Barbearia de Hilzer — Simulação Concorrente em Java

Implementação da versão clássica do **Problema da Barbearia de Ralph Hilzer**, conforme apresentado por Stallings (2012), utilizando Threads em Java e mecanismos rigorosos de sincronização.

---

## Integrantes e Frentes de Trabalho

| Nome          | Frente de Trabalho | Branch original      |
| ------------- | ------------------ | -------------------- |
| _(preencher)_ | Barbeiro           | `feature/barbeiro`   |
| _(preencher)_ | Cliente e Espera   | `feat/cliente`       |
| _(preencher)_ | Integração         | `feat/integracao`    |

---

## Pré-requisitos

- **JDK 17** (desenvolvido e testado com OpenJDK Temurin 17.0.14; `java -version`). Usa `switch` com `->`, que exige Java 14+.
- Sem ferramenta de build nem dependências externas: compila com `javac` puro. Os testes também não usam JUnit.
- Scripts: PowerShell 5.1+ (Windows) ou Bash (Linux; o cenário A do experimento precisa de `taskset`).

---

## 1. Visão Geral do Problema

Uma barbearia possui:

- **3 barbeiros**, cada um com sua própria cadeira de trabalho;
- **3 cadeiras de barbeiro** (uma por barbeiro);
- **1 sofá com 4 lugares** na sala de espera;
- **Capacidade total do recinto: 20 clientes** (contando os que estão sentados no sofá, em pé e sendo atendidos);
- **1 única máquina de pagamento (POS)**, compartilhada pelos três barbeiros.

Barbeiros e clientes são implementados como **Threads independentes**, competindo e cooperando pelo acesso aos recursos compartilhados (sofá, cadeiras, POS, vagas no recinto).

### Fluxo de um cliente

1. O cliente tenta entrar na barbearia. Se a capacidade máxima (20) estiver cheia, ele **não entra** (desiste).
2. Se entrou e há lugar no sofá, ele se senta. Caso contrário, ele **espera em pé**.
3. Quando um barbeiro fica livre, ele chama o cliente **há mais tempo no sofá**. Nesse momento, o cliente **há mais tempo em pé** é promovido e ocupa o lugar que vagou no sofá.
4. O barbeiro corta o cabelo do cliente.
5. Ao final do corte, o cliente disputa a POS para pagar (só um pagamento por vez, entre todos os barbeiros).
6. O cliente sai, liberando uma vaga na capacidade total (20).

---

## 2. Glossário de Termos Técnicos

Termos usados neste projeto e nas discussões de arquitetura, para todo mundo do grupo falar a mesma língua:

| Termo                                         | Significado                                                                                                                                                                          |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Thread**                                    | Unidade de execução independente dentro do mesmo processo. Cada barbeiro e cada cliente é uma thread.                                                                                |
| **Runnable**                                  | Interface Java usada para definir o comportamento de uma thread sem precisar herdar de `Thread` (preferida aqui pela ausência de herança múltipla em Java).                          |
| **Pseudoparalelismo**                         | Ilusão de paralelismo criada pelo escalonador da CPU/JVM ao alternar rapidamente entre threads em um único núcleo (fatiamento de tempo / _time slicing_).                            |
| **Paralelismo real**                          | Execução simultânea genuína de múltiplas threads, possível apenas com múltiplos núcleos físicos.                                                                                     |
| **Busy-wait (espera ocupada)**                | Anti-padrão em que uma thread fica em um laço `while` checando repetidamente uma condição, consumindo CPU sem necessidade. **Proibido neste projeto.**                               |
| **Exclusão mútua (mutex)**                    | Garantia de que apenas uma thread por vez acessa um recurso crítico (ex.: a POS).                                                                                                    |
| **Race condition**                            | Bug que ocorre quando o resultado de um programa depende da ordem de execução não controlada de threads concorrentes.                                                                |
| **Deadlock**                                  | Situação em que duas ou mais threads ficam bloqueadas indefinidamente, cada uma esperando um recurso que a outra segura.                                                             |
| **Starvation (inanição)**                     | Situação em que uma thread nunca consegue acessar um recurso porque outras são sempre priorizadas.                                                                                   |
| **Lost wake-up**                              | Bug de sincronização em que uma thread é "avisada" (notificada) para acordar antes de estar de fato esperando, perdendo o sinal e dormindo para sempre.                              |
| **FIFO (First In, First Out)**                | Disciplina de fila em que o primeiro a chegar é o primeiro a ser atendido. Exigida tanto no sofá quanto na promoção em pé → sofá.                                                    |
| **Fairness (justiça)**                        | Propriedade de um mecanismo de sincronização que garante que threads sejam atendidas na ordem em que solicitaram o recurso, evitando starvation.                                     |
| **Semaphore**                                 | Primitiva de sincronização que controla o acesso a um recurso com N unidades disponíveis (ex.: `Semaphore(20)` para a capacidade total). Pode ser configurado com `fairness = true`. |
| **ReentrantLock / Condition**                 | Alternativa mais flexível ao `synchronized`, permitindo múltiplas filas de espera (`Condition`) associadas a um mesmo lock.                                                          |
| **Monitor (synchronized / wait / notifyAll)** | Mecanismo nativo do Java para exclusão mútua e comunicação entre threads via um único lock implícito do objeto.                                                                      |
| **CPU-bound**                                 | Fase de execução em que a thread está de fato usando a CPU para processar algo.                                                                                                      |
| **I/O-bound**                                 | Fase de execução em que a thread está bloqueada aguardando algum evento externo (ex.: aguardando cliente, `Thread.sleep`), liberando a CPU para outras threads.                      |
| **Afinidade de CPU**                          | Restringir a execução de um processo/JVM a um subconjunto específico de núcleos lógicos da máquina.                                                                                  |
| **Estados de uma Thread**                     | `Running` (executando na CPU), `Ready` (pronta, aguardando ser escalonada) e `Blocked` (bloqueada, aguardando um evento ou recurso).                                                 |

---

## 3. Requisitos Funcionais

- [x] 3 cadeiras de barbeiro (`BarberChair.java`).
- [x] 3 barbeiros implementados como threads trabalhadoras (`Barber.java`).
- [x] Sala de espera com sofá de 4 lugares e fila em pé de 13 (`WaitingRoom.java`).
- [x] Capacidade total do recinto rigorosamente igual a 20 clientes (`Capacity.java`, `Semaphore(20, true)`).
- [x] Nenhum cliente entra se a capacidade máxima estiver satisfeita (`tryAcquire` não bloqueante: o cliente desiste na hora).
- [x] Cliente senta no sofá se houver vaga; caso contrário, espera em pé.
- [x] Ao liberar um barbeiro: chama o cliente há mais tempo no sofá **e** promove o cliente há mais tempo em pé para o lugar vago no sofá (FIFO em dois níveis, atômico em `WaitingRoom.getNext`).
- [x] Qualquer barbeiro pode receber pagamento, mas apenas um cliente paga por vez (POS única, `PosTerminal.java`, `ReentrantLock(true)`).
- [x] Barbeiros dividem tempo entre: cortar cabelo, receber pagamento e dormir cooperativamente enquanto aguardam clientes (`wait()` no monitor da sala, sem consumir CPU).
- [x] Logs de auditoria no formato do enunciado, em console e arquivo (`AuditLogger.java`).
- [x] Experimento de afinidade de CPU com ≥ 50 clientes atendidos e tabela da Q4 com dados reais (`resultados/tabela-q4.md`).

---

## 4. Restrições Rígidas (não-negociáveis)

1. **Proibido busy-wait** — nenhuma thread pode ficar em laço `while` checando uma condição sem bloquear.
2. **Zero deadlocks e zero starvation**, comprovados via logs e/ou testes de carga.
3. **Disciplina FIFO explícita** no sofá e na promoção em pé → sofá.
4. **Capacidade total (20) rigorosamente respeitada** em todos os momentos, mesmo sob concorrência.
5. **Pagamento serializado** na POS (um de cada vez, entre os 3 barbeiros).
6. **Logs suficientes** para auditar todos os eventos: entrada, espera em pé, sentar no sofá, ser chamado, pagamento, saída.

Como cada uma é verificada: ver [tests/tests.md](tests/tests.md).

---

## 5. Arquitetura e Divisão de Responsabilidades

Código (classes, métodos, variáveis) em inglês; logs, documentação e comentários em português. Os pacotes
`barbeiro/`, `cliente/` e `integracao/` preservam a divisão de frentes do grupo.

### 5.1 Barbeiro — `barbeiro/`

**Classes:** `Barber`, `BarberChair`, `BarberState`

- `Barber` implementa `Runnable`. Dorme com `wait()` no monitor da `WaitingRoom` quando o sofá está vazio (sem busy-wait) e acorda com o `notifyAll()` de `enter()`.
- Chama `WaitingRoom.getNext(nome, cadeira)`, ocupa a cadeira, simula o corte (`Thread.sleep`), disputa a `PosTerminal` e simula o pagamento.
- Em `serve`, a cadeira é liberada e `client.completeAttendance()` é chamado num `finally`: nenhum caminho (nem exceção inesperada) deixa o cliente preso.
- Mede corte e pagamento com `System.nanoTime` e repassa à `Metrics`.
- Encerramento normal com `stopWhenEmpty()`; `stop()` só no caminho de emergência.

### 5.2 Cliente e Espera — `cliente/`

**Classes:** `Client`, `WaitingRoom`

- `Client` implementa `Runnable` e controla o próprio ciclo de vida: reserva a vaga na `Capacity` (ou desiste) → entra na sala → espera ser chamado → espera o barbeiro concluir → devolve a vaga num `finally`. Guarda os timestamps e as ordens de fila usados pelas métricas e pelos testes de FIFO.
- `WaitingRoom` é um monitor único (`synchronized`/`wait`/`notifyAll`) com sofá (4) e fila em pé (13) em `ArrayDeque`. `getNext` retira o primeiro do sofá, promove o primeiro em pé e marca o chamado (`iAmTheNext`) atomicamente. Um cliente interrompido antes de ser chamado sai da fila (e o próximo em pé é promovido); interrompido depois de chamado, segue para o atendimento.

### 5.3 Integração — `integracao/` e raiz

**Classes:** `PosTerminal`, `Capacity`, `AuditLogger`, `Metrics`, `Simulation`, `Main`

- **`PosTerminal`**: `ReentrantLock(true)`; um pagamento por vez, conta transações e o pico de pagamentos simultâneos.
- **`Capacity`**: `Semaphore(20, true)` com `tryAcquire` não bloqueante; altera permissão e contador dentro do lock do log, então a lotação impressa em cada linha está em ordem.
- **`AuditLogger`**: instância injetada (sem estado global); timestamp calculado dentro do lock; ator lógico; console, arquivo e memória.
- **`Metrics`**: tempo total, esperas médias (em pé, sofá, fila da POS), utilização por barbeiro, atendidos e desistentes por motivo; grava CSV.
- **`Simulation`**: monta e roda uma execução (usada pelo `Main` e pelos testes); chegadas escalonadas com semente; detecção de deadlock/timeout e de exceções não capturadas; `checkInvariants`.
- **`Main`**: lê os argumentos, registra o ambiente (processadores visíveis para a JVM), imprime o resumo, grava CSV e sai com código 2 em qualquer falha.

---

## 6. Estrutura de Diretórios

```
thread-barbearia/
├── src/main/java/barbearia/
│   ├── Main.java
│   ├── Simulation.java
│   ├── barbeiro/
│   │   ├── Barber.java
│   │   ├── BarberChair.java
│   │   └── BarberState.java
│   ├── cliente/
│   │   ├── Client.java
│   │   └── WaitingRoom.java
│   └── integracao/
│       ├── AuditLogger.java
│       ├── Capacity.java
│       ├── Metrics.java
│       └── PosTerminal.java
├── tests/                      # pacote "tests" (ver tests/tests.md)
├── scripts/
│   ├── run-tests.sh / .ps1     # compila e roda todos os testes
│   ├── rodar-cenario-a.sh/.ps1 # cenário A: 1 núcleo
│   ├── rodar-cenario-b.sh/.ps1 # cenário B: multicore livre
│   └── rodar-experimento.sh/.ps1
├── resultados/tabela-q4.md     # tabela da Q4 (CSVs gerados não são versionados)
├── logs/                       # gerado: logs/execucao-<cenario>.log (não versionado)
├── notas_decisoes.md           # decisões de implementação (insumo do relatório)
├── notas_relatorio_*.md
├── instrucoes.md               # contrato de Client e WaitingRoom
└── README.md
```

---

## 7. Formato de Log para Auditoria

Todo evento é registrado com timestamp relativo ao início, ator lógico e descrição, incluindo a lotação quando
aplicável. Apenas os seis eventos do enunciado existem (`enum AuditLogger.Event`): `CHEGADA`, `ESPERA`,
`ATENDIMENTO`, `PROMOÇÃO`, `PAGAMENTO`, `SAÍDA`. Trecho real de `logs/execucao-B.log` (execução padrão, `java -cp out barbearia.Main`):

```
[00:00.172] [Thread-Cliente-04] PAGAMENTO: Corte concluído. Solicitando máquina POS.
[00:00.172] [Thread-Barbeiro-02] ATENDIMENTO: Chamou Cliente-06 do sofá para a Cadeira 2.
[00:00.172] [Thread-Cliente-01] SAÍDA: Pagamento concluído com Barbeiro-02. Saiu (Lotação: 7/20).
[00:00.172] [Thread-Cliente-04] PAGAMENTO: Máquina POS liberada. Pagando com Barbeiro-03.
[00:00.172] [Thread-Cliente-10] PROMOÇÃO: Assumiu assento no sofá (Posição 4 do sofá).
[00:00.184] [Thread-Cliente-11] CHEGADA: Entrou na barbearia (Lotação: 8/20).
[00:00.184] [Thread-Cliente-12] CHEGADA: Entrou na barbearia (Lotação: 9/20).
[00:00.184] [Thread-Cliente-13] CHEGADA: Entrou na barbearia (Lotação: 10/20).
[00:00.184] [Thread-Cliente-11] ESPERA: Sofá lotado. Aguardando em pé (Posição 1 da fila em pé).
[00:00.184] [Thread-Cliente-13] ESPERA: Sofá lotado. Aguardando em pé (Posição 2 da fila em pé).
[00:00.185] [Thread-Cliente-12] ESPERA: Sofá lotado. Aguardando em pé (Posição 3 da fila em pé).
```

- O ator é o **ator lógico** do evento, não o nome da thread Java: o `PAGAMENTO` é escrito pela thread do
  barbeiro, mas descreve o cliente.
- Desistências usam o mesmo vocabulário: `CHEGADA: Barbearia lotada (Lotação: 20/20). Desistiu.`;
  `SAÍDA: Sala de espera cheia. Desistiu. Saiu (...)`; `SAÍDA: Desistiu da espera (interrompido). Saiu (...)`.
- Linhas iniciadas por `#` são informativas (ambiente da execução, diagnóstico de falha), fora do fluxo de eventos.
- O arquivo de log é UTF-8. No console, a saída segue a codificação do terminal; se os acentos aparecerem
  trocados ao redirecionar a saída, consulte o arquivo em `logs/`.

---

## 8. Experimento Prático: Afinidade de CPU

- **Cenário A — Monoprocessador:** JVM restrita a 1 núcleo lógico.
  - Windows: `.\scripts\rodar-cenario-a.ps1` (usa `cmd /v:on /c "start "" /b /wait /affinity 1 java ..."`; o `Start-Process` do PowerShell 5.1 não tem `-Affinity`).
  - Linux: `scripts/rodar-cenario-a.sh` (`taskset -c 0 java ...`).
- **Cenário B — Multicore Livre:** `scripts/rodar-cenario-b.{sh,ps1}`.

A primeira linha de cada log registra `processadores visiveis para a JVM: N` (`Runtime.availableProcessors()`),
prova de que o cenário A rodou em 1 núcleo; o mesmo valor vai para a coluna `processadores` do CSV.

Experimento completo (5 execuções por cenário, intercaladas, e geração de `resultados/tabela-q4.md`):

```powershell
.\scripts\rodar-experimento.ps1            # Windows (testado)
```
```bash
scripts/rodar-experimento.sh               # Linux com taskset (lógica testada com taskset simulado)
```

O script aborta sem gravar a tabela se alguma execução falhar, atender menos de 50 clientes ou se o cenário A
não enxergar exatamente 1 processador. O experimento usa 55 clientes por execução: com 50, o cenário A às vezes
perde o último cliente por lotação (ver `notas_decisoes.md`).

---

## 9. Como Compilar e Executar

```bash
# Compilar (Git Bash / Linux)
javac -d out $(find src -name "*.java")

# Compilar (PowerShell)
javac -d out (Get-ChildItem src -Recurse -Filter *.java).FullName

# Executar com os padrões (50 clientes, cenário B, seed 42)
java -cp out barbearia.Main
```

Argumentos do `Main`:

| Argumento | Padrão | Significado |
| --- | --- | --- |
| `--clientes N` | 50 | número de clientes (threads) |
| `--cenario A\|B` | B | rótulo do cenário (a afinidade é aplicada pelos scripts) |
| `--seed S` | 42 | semente dos intervalos de chegada |
| `--intervalo-max-ms MS` | 25 | intervalo entre chegadas sorteado em [0, MS]; 0 = todos chegam juntos |
| `--corte-ms MS` | 30 | duração simulada do corte |
| `--pagamento-ms MS` | 15 | duração simulada do pagamento |
| `--timeout-s S` | 60 | prazo para a simulação terminar; ao estourar, dump de threads e saída 2 |
| `--execucao K` | — | sufixo do log/CSV (`execucao-A-3.log`, `resultados-A-3.csv`) |
| `--silencioso` | — | não imprime os eventos no console (o arquivo de log é gravado igual) |

Saídas: `logs/execucao-<cenario>[-K].log` e `resultados/resultados-<cenario>[-K].csv`.

Códigos de saída: **0** sucesso; **2** timeout/deadlock, exceção não tratada ou invariante violada (com
diagnóstico no stderr e no log); **64** argumento inválido.

---

## 10. Testes

```bash
scripts/run-tests.sh        # ou .\scripts\run-tests.ps1
```

Compila tudo e roda os 10 testes; sai com código ≠ 0 se algum falhar. Detalhes em [tests/tests.md](tests/tests.md).

---

## 11. Referências Bibliográficas

- Stallings, W., & Paul, G. K. (2012). _Operating Systems: Internals and Design Principles_ (Vol. 9). New York: Pearson.
- Silberschatz, A., Galvin, P. B., & Gagne, G. (2018). _Operating System Concepts_ (10th ed.). Hoboken: Wiley.
- Goetz, B., Peierls, T., Bloch, J., Bowbeer, J., Holmes, D., & Lea, D. (2006). _Java Concurrency in Practice_. Boston: Addison-Wesley.
