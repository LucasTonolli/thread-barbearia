# Problema da Barbearia de Hilzer — Simulação Concorrente em Java

Implementação da versão clássica do **Problema da Barbearia de Ralph Hilzer**, conforme apresentado por Stallings (2012), utilizando Threads em Java e mecanismos rigorosos de sincronização.

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

- [ ] 3 cadeiras de barbeiro.
- [ ] 3 barbeiros implementados como threads trabalhadoras.
- [ ] Sala de espera com sofá de 4 lugares.
- [ ] Capacidade total do recinto rigorosamente igual a 20 clientes.
- [ ] Nenhum cliente entra se a capacidade máxima estiver satisfeita.
- [ ] Cliente senta no sofá se houver vaga; caso contrário, espera em pé.
- [ ] Ao liberar um barbeiro: chama o cliente há mais tempo no sofá **e** promove o cliente há mais tempo em pé para o lugar vago no sofá (FIFO em dois níveis).
- [ ] Qualquer barbeiro pode receber pagamento, mas apenas um cliente paga por vez (POS única, exclusão mútua serializada).
- [ ] Barbeiros dividem tempo entre: cortar cabelo, receber pagamento e dormir cooperativamente enquanto aguardam clientes (sem consumir ciclos de CPU nesse período).

---

## 4. Restrições Rígidas (não-negociáveis)

1. **Proibido busy-wait** — nenhuma thread pode ficar em laço `while` checando uma condição sem bloquear.
2. **Zero deadlocks e zero starvation**, comprovados via logs e/ou testes de carga.
3. **Disciplina FIFO explícita** no sofá e na promoção em pé → sofá.
4. **Capacidade total (20) rigorosamente respeitada** em todos os momentos, mesmo sob concorrência.
5. **Pagamento serializado** na POS (um de cada vez, entre os 3 barbeiros).
6. **Logs suficientes** para auditar todos os eventos: entrada, espera em pé, sentar no sofá, ser chamado, pagamento, saída.

---

## 5. Arquitetura e Divisão de Responsabilidades

O projeto é dividido em três frentes de trabalho, cada uma responsável por um conjunto de classes. As fronteiras foram desenhadas para minimizar dependências cruzadas durante o desenvolvimento, mas os **recursos compartilhados** (sofá, POS, capacidade total) são definidos como contrato pela frente de Integração para que os outros dois lados codifiquem contra uma interface estável.

### 5.1 Barbeiro

**Responsável por:** `Barbeiro.java`

- Implementa `Runnable`.
- Ciclo de vida da thread: dormir (bloqueio real, sem busy-wait) enquanto não há cliente para atender; acordar quando chamado.
- Chama o cliente há mais tempo no sofá quando fica disponível.
- Controla a ocupação da própria cadeira (1 das 3 cadeiras de barbeiro).
- Simula o corte de cabelo com `Thread.sleep(...)`.
- Ao concluir o corte, disputa a POS (recurso da frente de Integração) para receber o pagamento.
- Registra eventos de log: `ATENDIMENTO`, `PAGAMENTO`, conclusão de corte.

### 5.2 Cliente e Espera

**Responsável por:** `Cliente.java`, `SalaDeEspera.java`

- `Cliente` implementa `Runnable`: tenta entrar na barbearia (respeitando a capacidade total, controlada via recurso da Integração), aguarda em pé ou no sofá, e sai após o pagamento.
- `SalaDeEspera` encapsula:
  - A fila em pé (FIFO).
  - O sofá (4 lugares, FIFO estrito).
  - A lógica de promoção em pé → sofá quando uma vaga é liberada.
- Registra eventos de log: `CHEGADA`, `ESPERA`, `PROMOÇÃO`, `SAÍDA`.

### 5.3 Integração

**Responsável por:** `CaixaPOS.java`, mecanismos de sinalização sofá↔barbeiro, controle de capacidade total, `Logger`/sistema de auditoria, `Main.java`.

- **`CaixaPOS`**: mutex/lock garantindo que só um cliente pague por vez, usado tanto por `Cliente` quanto por `Barbeiro`.
- **Sinalização sofá ↔ barbeiro**: mecanismo (ex.: `Semaphore` com `fairness = true`, ou `Lock` + `Condition`) que evita lost wake-up entre o barbeiro que fica livre e o cliente que está no sofá.
- **Capacidade total (20)**: tipicamente um `Semaphore(20, fair = true)` compartilhado, adquirido na entrada e liberado na saída do cliente.
- **Logger**: sistema de logs cronometrados e thread-safe, no formato padronizado (ver seção 7).
- **`Main`**: inicializa e sobe as threads de barbeiros e clientes, configura os cenários de execução (afinidade de CPU) e coordena o encerramento da simulação.

> Antes de codar, as três frentes devem combinar as **assinaturas das classes/interfaces compartilhadas** (`CaixaPOS`, `SalaDeEspera`, o semáforo de capacidade) para que o desenvolvimento avance em paralelo sem bloqueios entre os integrantes.

---

## 6. Estrutura de Diretórios Sugerida

```
barbearia/
├── src/
│   ├── main/java/barbearia/
│   │   ├── Main.java
│   │   ├── barbeiro/
│   │   │   └── Barbeiro.java
│   │   ├── cliente/
│   │   │   ├── Cliente.java
│   │   │   └── SalaDeEspera.java
│   │   └── integracao/
│   │       ├── CaixaPOS.java
│   │       └── Logger.java
├── build.gradle (ou pom.xml)
└── README.md
```

---

## 7. Formato de Log para Auditoria

Todo evento relevante deve ser registrado com timestamp, identificação da thread e descrição clara, incluindo o estado da capacidade quando aplicável:

```
[00:01.120] [Thread-Cliente-04] CHEGADA: Entrou na barbearia (Lotação: 12/20).
[00:01.122] [Thread-Cliente-04] ESPERA: Sofá lotado. Aguardando em pé (Posição 8 da fila em pé).
[00:02.340] [Thread-Barbeiro-01] ATENDIMENTO: Chamou Cliente-01 do sofá para a Cadeira 1.
[00:02.341] [Thread-Cliente-04] PROMOÇÃO: Assumiu assento no sofá (Posição 4 do sofá).
[00:05.890] [Thread-Cliente-01] PAGAMENTO: Corte concluído. Solicitando máquina POS.
[00:06.450] [Thread-Cliente-01] SAÍDA: Pagamento concluído com Barbeiro-01. Saiu (Lotação: 11/20).
```

O sistema de log precisa ser **thread-safe** (múltiplas threads escrevendo concorrentemente) e **cronologicamente consistente**.

---

## 8. Experimento Prático: Afinidade de CPU

Para avaliar empiricamente pseudoparalelismo vs. paralelismo real, a aplicação deve rodar com carga de **pelo menos 50 clientes** em dois cenários:

- **Cenário A — Monoprocessador:** JVM restrita a 1 único núcleo lógico.
  - Windows (PowerShell): `Start-Process java -ArgumentList "-jar Barbearia.jar" -Affinity 1`
  - Linux (taskset): `taskset -c 0 java -jar Barbearia.jar`
- **Cenário B — Multicore Livre:** execução distribuída livremente entre todos os núcleos disponíveis.

---

## 9. Como Compilar e Executar

> Ajustar conforme a ferramenta de build escolhida (Maven ou Gradle).

```bash
# Compilar
javac -d out src/main/java/barbearia/**/*.java

# Executar (exemplo simples, sem build tool)
java -cp out barbearia.Main
```

---

## 10. Referências Bibliográficas

- Stallings, W., & Paul, G. K. (2012). _Operating Systems: Internals and Design Principles_ (Vol. 9). New York: Pearson.
- Silberschatz, A., Galvin, P. B., & Gagne, G. (2018). _Operating System Concepts_ (10th ed.). Hoboken: Wiley.
- Goetz, B., Peierls, T., Bloch, J., Bowbeer, J., Holmes, D., & Lea, D. (2006). _Java Concurrency in Practice_. Boston: Addison-Wesley.
