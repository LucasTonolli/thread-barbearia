# Notas técnicas — Testes de concorrência da `WaitingRoom`

> Documento de apoio para preencher as questões 1–5 do relatório. Contém evidências
> empíricas coletadas antes de implementar sincronização, servindo como baseline
> "antes" para comparar com a versão sincronizada depois.

## 1. Contexto do experimento

A classe `WaitingRoom` (frente Cliente/Espera) foi implementada primeiro em sua
forma sequencial (sem nenhuma primitiva de sincronização), usando `ArrayDeque<Client>`
para as duas filas (sofá e em pé). Antes de adicionar `synchronized`/`wait`/`notifyAll`,
foi construído um teste de estresse (`WaitingRoomStressTest.java`) para **provar
empiricamente** a necessidade da sincronização, em vez de assumir por teoria.

### Metodologia do teste de estresse

- **Carga:** 40 threads `Client` concorrentes tentando `enter()` ao mesmo tempo
  (capacidade real da sala: 17 = 4 sentados + 13 em pé).
- **Concorrência adicional:** uma thread "barbeiro falso" chamando `getNext()`
  repetidamente em paralelo às entradas dos clientes — para gerar sobreposição
  real entre leitura/escrita das duas filas.
- **Repetição:** 20 rodadas independentes, cada uma com uma `WaitingRoom` nova.
  Repetição é necessária porque bugs de concorrência são **probabilísticos**,
  não determinísticos — uma única execução limpa não prova ausência de bug.
- **Instrumentação de debug:** um `Thread.sleep(2)` foi inserido temporariamente
  dentro do `enter()` (entre a checagem de capacidade e a inserção na fila) para
  alargar a janela de risco entre threads e aumentar a chance de expor a race
  condition. Removido depois de confirmado o problema.

## 2. Resultado observado (versão sem sincronização, com sleep de debug)

```
=== 1 de 20 rodadas tiveram algum problema ===
Rodada 8: 78 excecoes, 1 atendimentos, 1 distintos  <-- PROBLEMA
```

**Exceção obtida (repetida 78 vezes na mesma rodada):**

```
java.lang.NullPointerException: Cannot invoke "Client.getName()" because "<local1>" is null
```

### Causa raiz

No método `getNext()`:

```java
if (this.seatedClients.isEmpty()) {   // passo 1: checa
    return null;
}
Client client = seatedClients.poll(); // passo 2: remove
System.out.println(client.getName() + " is being served."); // passo 3: usa
```

`isEmpty()` e `poll()` são duas operações separadas, não atômicas entre si.
`ArrayDeque` **não é thread-safe**: chamadas concorrentes de `enter()` (que fazem
`offer()`) em outras threads, sobrepostas com o `poll()`/`isEmpty()` de `getNext()`,
corrompem o estado interno da estrutura. O resultado observado foi `poll()`
retornando `null` mesmo logo depois de `isEmpty()` ter retornado `false` —
sintoma clássico de estado inconsistente sob acesso concorrente não protegido.

### Observação sobre a posição do `sleep` de debug

O `Thread.sleep` foi colocado dentro do `enter()`, o que alarga principalmente a
janela de sobreposição **entre chamadas concorrentes de `enter()`**. O bug que
apareceu, porém, nasce da sobreposição **entre `enter()` e `getNext()`**. Isso
sugere que, para maximizar a reprodução desse tipo específico de falha, o sleep
teria mais efeito se colocado dentro do próprio `getNext()`, entre o `isEmpty()`
e o `poll()`. Fica registrado como nota metodológica: a posição do delay de
debug importa porque cada seção crítica tem sua própria janela de risco.

## 3. Resultado depois da sincronização (comparação antes/depois)

Após adicionar `synchronized` em `enter()`, `getNext()` e `printWaitingRoomStatus()`
(cobrindo a mesma seção crítica única, como planejado), o mesmo `WaitingRoomStressTest`
foi executado novamente, com a mesma carga (40 clientes, barbeiro falso, 20 rodadas)
e o `Thread.sleep(2)` de debug ainda presente dentro do `enter()`:

```
=== 0 de 20 rodadas tiveram algum problema ===
```

Nenhuma exceção em nenhuma das 20 rodadas — contra a rodada anterior (sem
sincronização, mesma carga, mesmo sleep de debug), que apresentou o
`NullPointerException` documentado na seção 2. Esse par de resultados —
mesma configuração de teste, único fator alterado sendo a presença do
`synchronized` — é a evidência direta de que a seção crítica elimina a race
condition identificada.

| Métrica                 | Sem sincronização                              | Com sincronização |
| ----------------------- | ---------------------------------------------- | ----------------- |
| Rodadas com problema    | 1 de 20 (5%)                                   | 0 de 20 (0%)      |
| Exceções na pior rodada | 78 (`NullPointerException`)                    | 0                 |
| Configuração do teste   | 40 clientes, 20 rodadas, sleep(2) no `enter()` | idêntica          |

**Nota sobre o desenho do lock:** os métodos privados `isSeatedClientsFull()`
e `isStandingClientsFull()` também foram marcados como `synchronized`, além de
`enter()`/`getNext()` que já os chamam de dentro de uma seção crítica. Isso é
redundante, mas não incorreto — o lock intrínseco do Java (`synchronized`) é
**reentrante**: a mesma thread pode readquirir o lock do mesmo objeto sem se
bloquear. Não há custo de deadlock aqui, só uma camada extra de proteção que
seria relevante caso esses métodos privados passassem a ser chamados de fora
de um contexto já sincronizado.

## 4. Bloqueio do `Client` até ser chamado (`wait`/`notifyAll`)

Depois de resolver a corrupção do `ArrayDeque`, faltava o `Client` deixar de
"tentar entrar uma vez e terminar" e passar a **bloquear de verdade** até ser
chamado pelo barbeiro — sem busy-wait. Essa etapa passou por duas versões
incorretas antes de chegar na correta, e os dois erros são relevantes pro
relatório (questão 2, sobre lost wake-ups e uso correto de monitores).

### Tentativa 1 — `client.wait()` dentro de `enter()` (deadlock estrutural)

A primeira versão chamava `client.wait()` dentro do `enter()`, que é
`synchronized` em `this` (a `WaitingRoom`). Isso gera dois problemas:

1. `wait()` só pode ser chamado no mesmo objeto cujo lock a thread já detém.
   Como o lock retido era o da `WaitingRoom`, não o do `Client`, chamar
   `client.wait()` sem antes fazer `synchronized(client)` lança
   `IllegalMonitorStateException` (confirmado em teste — ver log abaixo).
2. Mesmo corrigindo isso, `wait()` só libera o lock do objeto em que é
   chamado. O lock da `WaitingRoom` continuaria retido durante toda a espera,
   travando qualquer outra thread (inclusive o barbeiro) que precisasse de
   `enter()`/`getNext()` — um deadlock estrutural que trava o sistema inteiro
   no primeiro cliente que senta.

### Tentativa 2 — `this.wait()` + `client.notify()` (monitor errado no notify)

Ao trocar para `this.wait()` (correto — libera o lock da `WaitingRoom` durante
a espera), o `notify()` do lado do `getNext()` continuou chamando
`client.notify()`, notificando o monitor errado. Resultado, reproduzido no
teste de estresse:

```
[EXCECAO no barbeiro] java.lang.IllegalMonitorStateException: current thread is not owner
```

`notify()`/`notifyAll()` só podem ser chamados no objeto cujo lock a thread
atual detém — o barbeiro, dentro de `getNext()` (`synchronized` na
`WaitingRoom`), nunca tinha o lock do `Client` individual.

### Versão final — monitor único na `WaitingRoom` + flag por cliente

- `WaitingRoom.enter()`: `while (!client.isTheNext()) { this.wait(); }` —
  espera no monitor da própria `WaitingRoom`, dentro de um `while` (não `if`),
  porque `notifyAll()` acorda todas as threads esperando, e cada uma precisa
  reavaliar sua própria condição antes de prosseguir.
- `WaitingRoom.getNext()`: marca `client.iAmTheNext()` no cliente retirado do
  sofá e chama `this.notifyAll()` **incondicionalmente** (não só quando há
  promoção da fila em pé — bug identificado e corrigido nesse meio-tempo).
- `Client`: guarda o campo `iAmNext` (estado próprio do cliente) com
  `iAmTheNext()`/`isTheNext()`. Fica claro aqui a divisão de responsabilidade:
  o _lock/monitor_ usado para bloquear pertence à `WaitingRoom` (recurso
  compartilhado), mas a _condição_ checada no `while` pertence ao `Client`
  (estado individual).
- Tratamento de interrupção: se o cliente for interrompido enquanto espera,
  ele é removido da fila em que estiver (`seatedClients` ou `standingClients`)
  antes de retornar `false` — evita deixar uma thread "fantasma" registrada
  na sala depois de desistir.

### Resultado — mesmo `WaitingRoomStressTest`, agora com bloqueio real

Repetindo a mesma carga (40 clientes, barbeiro falso, 20 rodadas), agora com o
`Client` de fato bloqueando em `enter()` até ser chamado (não mais uma
tentativa única e imediata):

```
=== 0 de 20 rodadas tiveram algum problema ===
```

0 exceções, 0 deadlocks (execução não travou), 0 atendimentos duplicados —
validado tanto localmente quanto de forma independente. Esse teste é mais
representativo do comportamento real do sistema do que a versão anterior,
porque agora exercita o ciclo completo de bloqueio/notificação, não só a
inserção nas filas.

## 5. Como isso se conecta com as questões do relatório

**Questão 2 (Arquitetura de Sincronização, Exclusão Mútua e Ausência de Lost
Wake-ups)** — este experimento é a evidência empírica direta de que, sem uma
seção crítica única protegendo `seatedClients` e `standingClients`, ocorrem
race conditions reais e reproduzíveis (não apenas hipotéticas). O `NullPointerException`
observado documenta um mecanismo concreto de falha: checagem e ação separadas
(`isEmpty()` → `poll()`) sob uma estrutura de dados não thread-safe.

**Questão 3 (Disciplina de Fila / Fairness)** — ainda não testado diretamente;
depende da implementação de `wait`/`notifyAll` (ou `Semaphore` com fairness).
Fica como próximo experimento: depois de sincronizar, repetir uma carga
concorrente e verificar se a ordem de atendimento continua batendo com a ordem
de chegada (FIFO), o que hoje só foi validado sequencialmente (uma thread por vez).

**Questão 4 (Análise Comparativa Monoprocessador vs. Multicore)** — este
experimento ainda não mede tempo/desempenho, apenas corretude. A tabela
comparativa do enunciado precisa de uma rodada separada, com a versão já
sincronizada e sem o `sleep` de debug (que distorceria os tempos).

**Questão 5 (Trade-offs de Arquitetura)** — a observação sobre a posição do
`sleep` de debug é um bom gancho para discutir como diferentes primitivas de
sincronização (`synchronized`/`wait`/`notifyAll` vs. `Lock`+`Condition` vs.
`BlockingQueue`) mudam a granularidade e a localização das seções críticas.

## 6. Checklist do que falta antes da versão final

- [x] Adicionar seção crítica única (`synchronized` ou `Lock`) cobrindo
      `enter()` e `getNext()` em conjunto, incluindo a lógica de promoção.
      **Concluído e validado — ver seção 3 (0/20 rodadas com problema).**
- [x] Implementar o bloqueio do `Client` até ser chamado. **Concluído e
      validado — ver seção 4 (0/20 rodadas com problema, sem deadlock).**
- [x] Repetir o mesmo `WaitingRoomStressTest` na versão sincronizada e
      confirmar `0 de 20 rodadas com problema` — este é o par "antes/depois"
      que vai para o relatório. **Concluído — ver seção 3.**
- [ ] Repetir o teste de FIFO (Caso 5 do `WaitingRoomManualTest`) agora sob
      carga concorrente, não só sequencial.
- [ ] Definir e implementar o restante do ciclo de vida do `Client` depois de
      ser chamado (corte, disputa da POS, saída) — hoje o `run()` termina
      logo após `enterWaitingRoom()` retornar `true`.
- [ ] Remover (ou comentar, guardando para reprodução futura) o
      `Thread.sleep` de debug antes da entrega final.
- [ ] Coletar os tempos médios pedidos na tabela da questão 4, em cenário
      monoprocessador (afinidade de CPU) e multicore livre.

## 7. Dados brutos de referência

- Configuração do teste: `NUM_CLIENTS = 40`, `BARBER_ATTEMPTS = 80`, `NUM_ROUNDS = 20`.
- Capacidade real testada: `MAX_SEATED_CLIENTS = 4`, `MAX_STANDING_CLIENTS = 13` (total 17).
- Taxa de falha observada nesta rodada: 1/20 (5%) — amostra pequena, sujeita a
  variação; não interpretar como taxa exata, apenas como confirmação de que o
  bug existe e é reproduzível.
