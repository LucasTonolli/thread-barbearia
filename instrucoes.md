# Como usar `Client` e `WaitingRoom`

> Documento de integração: o contrato público das duas classes da frente Cliente/Espera, quem deve chamar
> o quê e **o que quebra se algum lado não seguir o contrato**. Reflete a base final (branch
> `refactor/integracao-final`). Na simulação completa, quem monta tudo é a `Simulation`; este documento
> serve para quem for usar as classes isoladamente (testes, extensões).

## Visão geral

- **`WaitingRoom`** é um recurso compartilhado (não é thread). Guarda o sofá (4 lugares) e a fila em pé
  (13 lugares), com toda a lógica de FIFO e promoção, num **monitor único** (`synchronized`/`wait`/`notifyAll`).
  Quem usa não precisa pegar lock manualmente.
- **`Client`** é uma thread (`Runnable`) que representa o ciclo de vida completo de um cliente: reservar uma
  vaga na lotação, esperar, ser chamado, ser atendido, devolver a vaga.

A lotação total (20) é da `Capacity`, o pagamento é da `PosTerminal` (usada pelo `Barber`), e os logs são do
`AuditLogger`. As três são injetadas pelo construtor.

---

## `WaitingRoom`

### Construtores

- `new WaitingRoom(AuditLogger logger)`: registra `ESPERA`, `ATENDIMENTO` e `PROMOÇÃO` nesse logger.
- `new WaitingRoom()`: logger silencioso (testes).

### `boolean enter(Client client)`

Chamado pelo próprio `Client` (dentro de `run()`, via `enterWaitingRoom()`). **Bloqueia a thread do cliente
até ele ser chamado por um barbeiro.**

- Retorna `false` imediatamente se a sala estiver cheia (17 = 4 + 13). Com a `Capacity` limitando a 20, isso
  só acontece se houver cadeira vazia com a sala cheia (ver `CapacityVsRoomTest`).
- Retorna `false` se a thread for interrompida **antes** de ser chamada: o cliente sai da fila e, se estava no
  sofá, o primeiro em pé é promovido.
- Retorna `true` quando o cliente foi chamado. Se a interrupção chegar **depois** de o barbeiro já tê-lo
  chamado, ele também retorna `true` (o barbeiro já o atende) e a flag de interrupção fica ligada.
- Usa `wait()`/`notifyAll()` no monitor da própria `WaitingRoom`.

**Quem chama:** só o `Client`.

### `Client getNext(String barberName, int chairId)` / `Client getNext()`

Chamado pelo `Barber` quando fica livre, sob o monitor da sala.

- Retira o cliente há mais tempo no sofá, promove o primeiro da fila em pé para a vaga e marca o cliente
  chamado (`iAmTheNext`), tudo atomicamente; registra `ATENDIMENTO` (com o barbeiro e a cadeira) e
  `PROMOÇÃO`. A versão sem argumentos não registra `ATENDIMENTO` (usada por testes que fazem papel de barbeiro).
- Retorna `null` se o sofá estiver vazio.
- Já chama `notifyAll()`. **O barbeiro não precisa (e não deve) chamar `notify` em nada.**

⚠️ **`getNext()` só libera o cliente da fila.** A thread do cliente continua bloqueada até
`completeAttendance()` (ver abaixo).

### Consultas

`isEmpty()`, `getSeatedCount()`, `getStandingCount()`, `getTotalWaiting()`, `getPeakSeated()`,
`getPeakStanding()`, `getRejectedCount()` (recusas por sala cheia), `getInterruptedAfterCallCount()` e
`describe()` (retrato das filas como texto; substitui o antigo `printWaitingRoomStatus()`, sem `println`).

---

## `Client`

### Construtores

- `new Client(name, waitingRoom, capacity, logger, metrics)`: o usado pela `Simulation`.
- `new Client(name, waitingRoom, capacity, logger)`: métricas descartáveis.
- `new Client(name, waitingRoom)`: para testes isolados da sala (lotação própria, logger silencioso).

### `void run()`

```
1. capacity.tryEnter(nome): se lotado, registra "CHEGADA: Barbearia lotada (...). Desistiu." e termina.
2. enterWaitingRoom(): bloqueia até ser chamado (ou desiste: sala cheia / interrompido).
3. waitUntilAttended(): bloqueia até o barbeiro chamar completeAttendance().
4. finally: capacity.leave(...) devolve a vaga e registra a SAÍDA (com o barbeiro, se atendido).
```

### `void setAttendedBy(String barberName)` e `void completeAttendance()`

**Métodos que o `Barber` precisa chamar** depois do corte e do pagamento:

```java
client.setAttendedBy(name);     // só no sucesso: marca quem recebeu o pagamento
...
client.completeAttendance();    // SEMPRE, num finally: destrava a thread do cliente
```

⚠️ **Se ninguém chamar `completeAttendance()`, a thread daquele `Client` fica bloqueada para sempre**, e a
vaga na lotação nunca é devolvida. Por isso o `Barber.serve` o chama num `finally`, inclusive quando
`chair.occupy`, `pos.acquire` ou `pos.release` lançam exceção.

### `void waitUntilAttended()`

Chamado internamente pelo `run()`. Depois de chamado, o cliente está na cadeira: uma interrupção nessa fase é
guardada e restaurada no fim, mas não o faz sair antes de o barbeiro encerrar (senão a vaga seria devolvida
com o corte em andamento).

### `boolean isTheNext()` / `void iAmTheNext()`

Ponte interna entre `WaitingRoom.getNext()` e `WaitingRoom.enter()`. Não devem ser chamados pelo `Barber`
nem pelo `Main`.

### Dados para métricas e testes

`wasServed()`, `getAttendedBy()`, as ordens `getEnqueueOrder()`, `getStandingOrder()`, `getSofaOrder()`,
`getCallOrder()` e os timestamps `get*Nanos()` (chegada, em pé, sofá, chamada, pedido de POS, posse da POS,
fim do pagamento). As ordens e os tempos da sala são gravados sob o monitor da `WaitingRoom`; os do pagamento,
pelo barbeiro que atende o cliente.

---

## Contrato resumido — quem chama o quê

| Método | Quem chama | Quando |
| --- | --- | --- |
| `client.run()` (via `thread.start()`) | `Simulation` | Uma vez, ao criar cada cliente |
| `waitingRoom.enter(client)` | `Client` (internamente) | Automático, dentro do `run()` |
| `waitingRoom.getNext(nome, cadeira)` | `Barber` | Sempre que o barbeiro fica livre, sob o monitor da sala |
| `client.setAttendedBy(nome)` | `Barber` | Depois do pagamento, só se deu certo |
| `client.completeAttendance()` | `Barber` | **Sempre**, num `finally`, ao encerrar o atendimento |

---

## Exemplo mínimo (fora da `Simulation`, só ilustrativo)

```java
WaitingRoom waitingRoom = new WaitingRoom();

Client client = new Client("Cliente-01", waitingRoom);
Thread clientThread = new Thread(client);
clientThread.start();

// ... do lado do barbeiro:
Client called = waitingRoom.getNext();
if (called != null) {
    try {
        // corte e pagamento (Thread.sleep) aqui
        called.setAttendedBy("Barbeiro-01");
    } finally {
        called.completeAttendance(); // sem isso, a thread do cliente nunca termina
    }
}

clientThread.join();
```

---

## Como testar

```bash
scripts/run-tests.sh                              # todos os testes
java -cp out tests.WaitingRoomManualTest          # casos da sala, um a um
java -cp out tests.WaitingRoomStressTest          # 20 rodadas, 40 clientes, barbeiro falso
java -cp out tests.FifoIntegrationTest            # FIFO controlado e concorrente
java -cp out tests.RobustnessTest                 # interrupções antes/depois de ser chamado
```

Resultado esperado: todos terminam com código 0 e sem travar. Detalhes em [tests/tests.md](tests/tests.md).
