# Como usar `Client` e `WaitingRoom`

> Documento de integração — escrito pra quem for usar essas duas classes de
> fora (principalmente quem está implementando `Barbeiro`, e quem for montar
> o `Main` final). Descreve o contrato público: o que cada método faz, quem
> deveria chamar o quê, e — mais importante — **o que quebra se algum lado
> não seguir o contrato**.

## Visão geral

- **`WaitingRoom`** é um recurso compartilhado (não é thread). Guarda o sofá
  (4 lugares) e a fila em pé (13 lugares), com toda a lógica de FIFO e
  promoção. Ela é `synchronized` por dentro — quem usa não precisa se
  preocupar com lock manualmente.
- **`Client`** é uma thread (`Runnable`). Representa o ciclo de vida completo
  de um cliente: chegar, esperar, ser chamado, ser atendido, terminar.

Nenhuma das duas classes lida com capacidade total (20), POS, ou logging —
isso é responsabilidade de outras frentes (Integração).

---

## `WaitingRoom`

### `boolean enter(Client client)`

Chamado pelo próprio `Client` (dentro do seu `run()`, já encapsulado em
`enterWaitingRoom()` — ver abaixo). **Essa chamada bloqueia a thread do
cliente até ele ser chamado por um barbeiro**, ou até a sala estar cheia.

- Retorna `false` imediatamente se a sala estiver cheia (17 = 4 sofá + 13 em
  pé) — o cliente não chegou a entrar, e a thread não bloqueia nesse caso.
- Retorna `true` **depois** de o cliente ter sido chamado (ou seja, quando
  esse método retorna `true`, o cliente já está livre do sofá/fila e pronto
  pra ser atendido — não é preciso checar mais nada antes de prosseguir).
- Internamente usa `wait()`/`notifyAll()` no monitor da própria
  `WaitingRoom` — não chama `wait()` em `client` nem em nenhum outro objeto.

**Quem deveria chamar:** só o `Client`. O `Barbeiro` nunca chama `enter()`.

### `Client getNext()`

Chamado pelo `Barbeiro` quando ele fica livre pra atender alguém.

- Retorna o cliente há mais tempo no sofá, já removido da fila.
- Promove automaticamente o próximo da fila em pé pro lugar que abriu no
  sofá (se houver alguém esperando em pé).
- Retorna `null` se não houver ninguém sentado no sofá no momento.
- Já chama `notifyAll()` internamente — isso é o que faz o `Client`
  retornado acordar de dentro do `enter()`. **O barbeiro não precisa (e não
  deveria) chamar `notify` em nada manualmente.**

**Quem deveria chamar:** só o `Barbeiro`.

⚠️ **`getNext()` só libera o cliente da fila — não é o suficiente pra
"destravar" a thread dele até o fim.** Ver seção seguinte.

### `void printWaitingRoomStatus()`

Só para debug/log — imprime o conteúdo atual das duas filas. Não é
thread-safe por acaso, é `synchronized` como os outros métodos.

---

## `Client`

### Construtor: `new Client(String name, WaitingRoom waitingRoom)`

Recebe a `WaitingRoom` compartilhada (a mesma instância usada por todos os
clientes e pelo barbeiro).

### `void run()`

Ciclo de vida completo da thread:

```
1. enterWaitingRoom() — entra na sala, bloqueia até ser chamado (ou desiste, se a sala estiver cheia)
2. Se não entrou: thread termina aqui.
3. waitUntilAttended() — bloqueia até o atendimento (corte + pagamento) ser marcado como concluído
4. thread termina
```

### `boolean enterWaitingRoom()`

Wrapper em volta de `waitingRoom.enter(this)`, com log simples. Retorna
`true`/`false` conforme o cliente conseguiu ou não entrar.

### `void completeAttendance()`

**Método que o `Barbeiro` precisa chamar** depois de terminar o corte E o
pagamento daquele cliente. Isso destrava a thread do `Client`, que está
bloqueada em `waitUntilAttended()`.

```java
// dentro do Barbeiro, depois do corte e do pagamento:
client.completeAttendance();
```

⚠️ **Se ninguém chamar esse método, a thread daquele `Client` fica bloqueada
para sempre.** Não é um travamento passageiro — é permanente, porque
`waitUntilAttended()` só sai do `while` quando essa flag vira `true`. Isso
já aconteceu no nosso teste de estresse (ver `WaitingRoomStressTest.java`):
sem essa chamada, o `join()` das threads nunca retorna.

### `void waitUntilAttended()`

Chamado internamente pelo `run()`. Não precisa ser chamado de fora — está
documentado aqui só pra deixar claro o que acontece por trás.

### `boolean isTheNext()` / `void iAmTheNext()`

Uso interno da `WaitingRoom` (dentro de `getNext()`) pra marcar/checar qual
cliente foi chamado. Não deveria ser chamado por `Barbeiro` nem por `Main` —
só existe como ponte entre `WaitingRoom.getNext()` e `WaitingRoom.enter()`.

---

## Contrato resumido — quem chama o quê

| Método | Quem chama | Quando |
| --- | --- | --- |
| `client.run()` (via `thread.start()`) | `Main` | Uma vez, ao criar cada cliente |
| `waitingRoom.enter(client)` | `Client` (internamente) | Automático, dentro do `run()` |
| `waitingRoom.getNext()` | `Barbeiro` | Sempre que o barbeiro fica livre |
| `client.completeAttendance()` | `Barbeiro` | **Depois** do corte + pagamento daquele cliente específico |

**A linha mais importante da tabela pra quem está implementando `Barbeiro`
é a última** — sem essa chamada, o sistema todo trava silenciosamente (sem
exception, sem log de erro — as threads simplesmente nunca terminam).

---

## Exemplo mínimo de uso (fora de um `Main` de verdade — só ilustrativo)

```java
WaitingRoom waitingRoom = new WaitingRoom();

Client client = new Client("Cliente-01", waitingRoom);
Thread clientThread = new Thread(client);
clientThread.start();

// ... em algum momento, do lado do Barbeiro:
Client atendido = waitingRoom.getNext();
if (atendido != null) {
    // simular corte (Thread.sleep) e pagamento aqui
    atendido.completeAttendance(); // sem isso, a thread do cliente nunca termina
}

clientThread.join();
```

---

## Como testar

O arquivo `WaitingRoomStressTest.java` sobe várias dezenas de clientes
concorrentes, com um "barbeiro falso" chamando `getNext()` +
`completeAttendance()` em loop, repetindo por 20 rodadas e verificando
exceções e travamentos.

```bash
javac -d out barbearia/cliente/*.java
java -cp out barbearia.cliente.WaitingRoomStressTest
```

Resultado esperado: `0 de 20 rodadas tiveram algum problema`, sem o processo
travar (se travar, o terminal simplesmente não retorna o prompt — sinal de
que algum `Client` não foi liberado).

---

## O que ainda NÃO está implementado nessas duas classes

Pra quem for integrar, vale saber os limites atuais:

- **Capacidade total (20)** não é controlada aqui — só os 17 lugares da
  sala. Isso é responsabilidade da Integração (tipicamente um
  `Semaphore(20)` chamado antes de `enter()` e liberado depois da saída).
- **Logs no formato do enunciado** (`[HH:MM.mmm] [Thread-Cliente-NN]
  CATEGORIA: ...`) ainda não existem — os `println` atuais são só para
  debug interno.
- **POS/pagamento** não é gerenciado aqui — é o `Barbeiro`/Integração quem
  disputa a POS; o `Client` só espera passivamente via
  `waitUntilAttended()`.
