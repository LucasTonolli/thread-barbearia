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

## 3. Como isso se conecta com as questões do relatório

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

## 4. Checklist do que falta antes da versão final

- [ ] Adicionar seção crítica única (`synchronized` ou `Lock`) cobrindo
      `enter()` e `getNext()` em conjunto, incluindo a lógica de promoção.
- [ ] Implementar o bloqueio do `Client` até ser chamado (hoje ele só tenta
      entrar uma vez e termina — falta o `wait()` dentro de um método tipo
      "sente e espere").
- [ ] Repetir o mesmo `WaitingRoomStressTest` na versão sincronizada e
      confirmar `0 de 20 rodadas com problema` — este é o par "antes/depois"
      que vai para o relatório.
- [ ] Repetir o teste de FIFO (Caso 5 do `WaitingRoomManualTest`) agora sob
      carga concorrente, não só sequencial.
- [ ] Remover (ou comentar, guardando para reprodução futura) o
      `Thread.sleep` de debug antes da entrega final.
- [ ] Coletar os tempos médios pedidos na tabela da questão 4, em cenário
      monoprocessador (afinidade de CPU) e multicore livre.

## 5. Dados brutos de referência

- Configuração do teste: `NUM_CLIENTS = 40`, `BARBER_ATTEMPTS = 80`, `NUM_ROUNDS = 20`.
- Capacidade real testada: `MAX_SEATED_CLIENTS = 4`, `MAX_STANDING_CLIENTS = 13` (total 17).
- Taxa de falha observada nesta rodada: 1/20 (5%) — amostra pequena, sujeita a
  variação; não interpretar como taxa exata, apenas como confirmação de que o
  bug existe e é reproduzível.
