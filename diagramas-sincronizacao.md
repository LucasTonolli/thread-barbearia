# Diagramas de sincronização

Diagramas da arquitetura de sincronização, fiéis ao código atual (`Barber`, `Client`, `WaitingRoom`,
`Capacity`, `PosTerminal`, `AuditLogger`). Estão em Mermaid: o GitHub e o VS Code (com extensão de
Mermaid) os renderizam direto. Para o relatório, dá para exportar cada um como imagem em
[mermaid.live](https://mermaid.live).

---

## 1. Recursos compartilhados e quem os protege

Cada estado compartilhado tem exatamente um lock "dono". As setas tracejadas indicam que o recurso escreve no
log enquanto segura o próprio lock.

```mermaid
flowchart LR
    subgraph T["Threads"]
        C["Clientes (N)"]
        B["Barbeiros (3)"]
    end
    subgraph R["Recursos e seus locks"]
        CAP["Capacity<br/>Semaphore(20) + contador<br/>alterados sob o lock do log"]
        WR["WaitingRoom<br/>sofá (4) + fila em pé (13)<br/>monitor synchronized"]
        CH["BarberChair (x3)<br/>monitor synchronized"]
        POS["PosTerminal<br/>ReentrantLock(true)"]
        CM["Client<br/>monitor próprio<br/>(fim do atendimento)"]
        LOG["AuditLogger<br/>ReentrantLock"]
    end
    C -- "tryEnter / leave" --> CAP
    C -- "enter: espera até ser chamado" --> WR
    C -- "waitUntilAttended" --> CM
    B -- "getNext / wait" --> WR
    B -- "occupy / release" --> CH
    B -- "acquire / release" --> POS
    B -- "completeAttendance" --> CM
    CAP -.-> LOG
    WR -.-> LOG
    POS -.-> LOG
```

---

## 2. Ordem de aquisição de locks (por que não há deadlock)

Uma seta A → B significa "B é adquirido enquanto A está retido". O grafo não tem ciclo, logo não há espera
circular, que é condição necessária para deadlock. Os monitores da cadeira e do cliente nunca são retidos
enquanto se pede outro lock.

```mermaid
flowchart TD
    WR["Monitor da WaitingRoom"] --> LOG["Lock do AuditLogger"]
    POS["Lock da PosTerminal"] --> LOG
    LOG --> FIM["Não adquire mais nada<br/>(só atômicos e Semaphore.tryAcquire/release,<br/>que não bloqueiam)"]
    CH["Monitor da BarberChair<br/>(isolado: seção curta)"]
    CM["Monitor do Client<br/>(isolado: só flag + notifyAll)"]
```

---

## 3. Ciclo de vida completo de um cliente

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant CAP as Capacity
    participant WR as WaitingRoom
    participant B as Barbeiro
    participant POS as PosTerminal

    C->>CAP: tryEnter() sob o lock do log
    alt lotação cheia (20/20)
        CAP-->>C: false, CHEGADA ... Desistiu
    else há vaga
        CAP-->>C: true, CHEGADA (Lotação N/20)
        C->>WR: enter(): fim do sofá ou fim da fila em pé, notifyAll()
        Note over C,WR: Cliente em wait() no monitor da sala enquanto iAmNext for falso
        B->>WR: getNext(): chama a cabeça do sofá, promove, notifyAll()
        WR-->>C: acorda, vê iAmNext = true, enter() retorna true
        C->>C: waitUntilAttended(): wait() no monitor do cliente
        B->>B: chair.occupy() e corte (sleep)
        B->>POS: acquire() (fila FIFO justa)
        POS-->>B: posse da POS
        B->>B: pagamento (sleep)
        B->>POS: release() no finally
        B->>C: setAttendedBy() e completeAttendance() no finally (notifyAll)
        C->>CAP: leave() no finally: SAÍDA (Lotação N-1/20)
    end
```

A vaga na `Capacity` é obtida na chegada e só devolvida no último passo. Durante toda a passagem pelo sofá,
pela fila em pé e pela cadeira, o cliente conta como 1 dos 20; por isso as promoções entre filas não alteram
a lotação.

---

## 4. Barbeiro dormindo sem lost wake-up

```mermaid
sequenceDiagram
    participant B as Barbeiro
    participant M as Monitor da WaitingRoom
    participant C as Cliente

    B->>M: synchronized (entra no monitor)
    B->>M: getNext() retorna null (sofá vazio)
    B->>M: wait(): solta o monitor e dorme
    C->>M: synchronized enter(): entra no sofá
    C->>M: notifyAll()
    M-->>B: acorda e passa a disputar o monitor
    C->>M: wait(): solta o monitor
    B->>M: reavalia no while: getNext() retorna o cliente
    Note over B,C: Entre o teste (getNext) e o wait() o barbeiro nunca solta o monitor. Nenhum enter() cabe nesse intervalo, então a notificação não se perde.
```

O mesmo padrão (testar a condição num `while` e dormir no mesmo monitor de quem a altera) vale para o cliente
esperando ser chamado (monitor da sala) e esperando o fim do atendimento (monitor do próprio cliente).

---

## 5. `getNext()`: chamada e promoção atômicas (FIFO em dois níveis)

```mermaid
flowchart TD
    A["Barbeiro chama getNext(nome, cadeira)"] --> SC
    subgraph SC["Tudo dentro de synchronized (WaitingRoom)"]
        S{"Sofá vazio?"}
        S -- "sim" --> N["Retorna null:<br/>o barbeiro dorme (wait)"]
        S -- "não" --> P1["poll(): cabeça do sofá<br/>(há mais tempo sentado)"]
        P1 --> P2["Grava ordem de chamada<br/>e registra ATENDIMENTO"]
        P2 --> P3{"Fila em pé vazia?"}
        P3 -- "não" --> P4["poll() da cabeça em pé e offer()<br/>no fim do sofá: PROMOÇÃO"]
        P3 -- "sim" --> P5
        P4 --> P5["iAmTheNext() no cliente chamado"]
        P5 --> P6["notifyAll()"]
    end
    P6 --> R["Retorna o cliente ao barbeiro"]
```

Invariante mantida: **fila em pé não vazia ⇒ sofá cheio**. Ninguém novo senta no sofá enquanto há alguém em
pé esperando, então a ordem de chamada é igual à ordem de entrada na sala. Quem decide o próximo é o barbeiro
(marcando `iAmNext`); os clientes acordados pelo `notifyAll()` que não foram marcados voltam a dormir, então
a disputa pelo monitor (que não é justo) não altera a ordem.

---

## 6. Estados do cliente

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Chegada
    Chegada --> [*] : t1
    Chegada --> Sofa : t2
    Chegada --> EmPe : t3
    Chegada --> SaiuSemAtendimento : t4
    EmPe --> Sofa : t5
    EmPe --> SaiuSemAtendimento : t6
    Sofa --> SaiuSemAtendimento : t6
    Sofa --> Chamado : t7
    Chamado --> Chamado : t8
    Chamado --> Atendido : t9
    Chamado --> SaiuSemAtendimento : t10
    Atendido --> [*] : t11
    SaiuSemAtendimento --> [*] : t11

    state "Em pé" as EmPe
    state "Sofá" as Sofa
    state "Saiu sem atendimento" as SaiuSemAtendimento
```

| Transição | Quando acontece | Onde |
| --- | --- | --- |
| t1 | Lotação 20/20: desiste na hora (`CHEGADA ... Desistiu.`) | `Capacity.tryEnter` |
| t2 | Há vaga e o sofá tem lugar | `WaitingRoom.enter` |
| t3 | Há vaga e o sofá está lotado | `WaitingRoom.enter` |
| t4 | Sala cheia (17): caminho defensivo, raro | `WaitingRoom.enter` |
| t5 | Promoção: era a cabeça da fila em pé quando um barbeiro chamou alguém | `WaitingRoom.getNext` |
| t6 | Interrompido antes de ser chamado; se estava no sofá, o primeiro em pé é promovido | `WaitingRoom.enter` (catch) |
| t7 | Era a cabeça do sofá: barbeiro marca `iAmNext` | `WaitingRoom.getNext` |
| t8 | Interrompido depois de chamado: segue para o atendimento (gap 7) | `WaitingRoom.enter`, `Client.waitUntilAttended` |
| t9 | Corte e pagamento concluídos | `Barber.serve` |
| t10 | Barbeiro interrompido ou falhou: o `finally` destrava o cliente | `Barber.serve` (finally) |
| t11 | Devolve a vaga e registra `SAÍDA` (com o barbeiro ou com o motivo) | `Client.run` (finally) |

---

## 7. Estados do barbeiro (`BarberState`)

```mermaid
stateDiagram-v2
    [*] --> SLEEPING
    SLEEPING --> CALLING_CLIENT : getNext retorna cliente
    CALLING_CLIENT --> CUTTING_HAIR : chair.occupy
    CUTTING_HAIR --> WAITING_POS : fim do corte (sleep)
    WAITING_POS --> RECEIVING_PAYMENT : pos.acquire (fila FIFO)
    RECEIVING_PAYMENT --> CALLING_CLIENT : release, completeAttendance e há cliente no sofá
    RECEIVING_PAYMENT --> SLEEPING : release, completeAttendance e sofá vazio (wait)
    SLEEPING --> FINISHED : stopWhenEmpty com sala vazia, stop ou interrupção
    FINISHED --> [*]
```

Em `SLEEPING` a thread está em `wait()` no monitor da sala: bloqueada de verdade, sem consumir CPU.

---

## 8. Disputa pela POS com lock justo

```mermaid
sequenceDiagram
    participant B1 as Barbeiro-01
    participant B2 as Barbeiro-02
    participant B3 as Barbeiro-03
    participant POS as PosTerminal (ReentrantLock justo)

    B1->>POS: lockInterruptibly()
    POS-->>B1: posse (ninguém na fila)
    B2->>POS: lockInterruptibly()
    Note right of POS: B2 entra na fila FIFO do AQS e estaciona (park)
    B3->>POS: lockInterruptibly()
    Note right of POS: B3 entra na fila atrás de B2
    B1->>POS: unlock() no finally
    POS-->>B2: unpark: o primeiro da fila recebe a posse
    B1->>POS: lockInterruptibly() de novo (próximo cliente)
    Note right of POS: Modo justo: hasQueuedPredecessors() é verdadeiro, B1 vai para trás de B3
    B2->>POS: unlock()
    POS-->>B3: unpark
    B3->>POS: unlock()
    POS-->>B1: unpark
    Note over B1,POS: Quem segura a POS só dorme (pagamento) e escreve no log. Não pede outro lock bloqueante: sem espera circular, sem deadlock.
```

Um pagamento por vez é garantido pelo lock único; o pico de pagamentos simultâneos medido em todos os testes
foi 1. A justiça impede que um barbeiro (e o cliente que ele atende) seja preterido indefinidamente na POS.
