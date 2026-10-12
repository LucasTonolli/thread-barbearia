# Barbearia de Hilzer — Simulação Concorrente em Java

Simulação do **Problema da Barbearia de Hilzer** (Stallings, 2012) com threads Java: 3 barbeiros, sofá de 4
lugares, lotação de 20 clientes e uma única máquina de pagamento (POS). Contexto do problema, glossário,
requisitos e arquitetura estão em [visao-geral.md](visao-geral.md).

---

## Pré-requisitos

- **JDK 17** (testado com OpenJDK Temurin 17.0.14). Confira com `java -version`.
- Nada mais: compila com `javac` puro, sem Maven/Gradle e sem dependências (os testes não usam JUnit).
- Scripts: PowerShell 5.1+ no Windows ou Bash no Linux (o cenário A do experimento precisa de `taskset`).

---

## Início rápido

```bash
# 1. Compilar (Git Bash / Linux)
javac -d out $(find src -name "*.java")

# 1. Compilar (PowerShell)
javac -d out (Get-ChildItem src -Recurse -Filter *.java).FullName

# 2. Executar com os padrões (50 clientes, cenário B, seed 42)
java -cp out barbearia.Main

# 3. Rodar todos os testes
scripts/run-tests.sh            # ou .\scripts\run-tests.ps1
```

Ao final, o programa imprime um resumo (atendidos, desistências, esperas médias, utilização dos barbeiros) e
`VALIDACAO FINAL: todas as invariantes foram respeitadas.`

---

## Executando a simulação

```bash
java -cp out barbearia.Main [opções]
```

| Opção                   | Padrão | Significado                                                                     |
| ----------------------- | ------ | ------------------------------------------------------------------------------- |
| `--clientes N`          | 50     | número de clientes (threads)                                                    |
| `--cenario A\|B`        | B      | rótulo do cenário no log e no CSV (a afinidade de CPU é aplicada pelos scripts) |
| `--seed S`              | 42     | semente dos intervalos de chegada (mesma semente = mesmas chegadas)             |
| `--intervalo-max-ms MS` | 25     | intervalo entre chegadas sorteado em [0, MS]; `0` = todos chegam juntos         |
| `--corte-ms MS`         | 30     | duração simulada do corte                                                       |
| `--pagamento-ms MS`     | 15     | duração simulada do pagamento                                                   |
| `--timeout-s S`         | 60     | prazo para terminar; ao estourar, imprime dump das threads e sai com 2          |
| `--execucao K`          | —      | sufixo dos arquivos de saída (`execucao-A-3.log`, `resultados-A-3.csv`)         |
| `--silencioso`          | —      | não imprime os eventos no console (o arquivo de log é gravado igual)            |

Exemplos:

```bash
java -cp out barbearia.Main --clientes 80 --seed 7
java -cp out barbearia.Main --intervalo-max-ms 0       # rajada: todos chegam ao mesmo tempo
java -cp out barbearia.Main --silencioso               # só o resumo no console
```

### Saídas

| Arquivo                                   | Conteúdo                          |
| ----------------------------------------- | --------------------------------- |
| `logs/execucao-<cenario>[-K].log`         | log de auditoria completo (UTF-8) |
| `resultados/resultados-<cenario>[-K].csv` | métricas da execução (uma linha)  |

Os dois diretórios são gerados e não são versionados.

### Códigos de saída

| Código | Significado                                                                                  |
| ------ | -------------------------------------------------------------------------------------------- |
| 0      | sucesso, todas as invariantes respeitadas                                                    |
| 2      | timeout/deadlock, exceção não tratada ou invariante violada (diagnóstico no stderr e no log) |
| 64     | argumento inválido                                                                           |

---

## Lendo o log

Formato: `[mm:ss.SSS] [Thread-Ator] EVENTO: mensagem`, com os eventos `CHEGADA`, `ESPERA`, `ATENDIMENTO`,
`PROMOÇÃO`, `PAGAMENTO` e `SAÍDA`. Trecho real de uma execução padrão:

```
[00:00.172] [Thread-Barbeiro-02] ATENDIMENTO: Chamou Cliente-06 do sofá para a Cadeira 2.
[00:00.172] [Thread-Cliente-01] SAÍDA: Pagamento concluído com Barbeiro-02. Saiu (Lotação: 7/20).
[00:00.172] [Thread-Cliente-04] PAGAMENTO: Máquina POS liberada. Pagando com Barbeiro-03.
[00:00.172] [Thread-Cliente-10] PROMOÇÃO: Assumiu assento no sofá (Posição 4 do sofá).
[00:00.184] [Thread-Cliente-11] CHEGADA: Entrou na barbearia (Lotação: 8/20).
[00:00.184] [Thread-Cliente-12] CHEGADA: Entrou na barbearia (Lotação: 9/20).
[00:00.184] [Thread-Cliente-13] CHEGADA: Entrou na barbearia (Lotação: 10/20).
[00:00.184] [Thread-Cliente-11] ESPERA: Sofá lotado. Aguardando em pé (Posição 1 da fila em pé).
```

- O ator é quem o evento descreve: `PAGAMENTO` aparece como cliente, embora seja escrito pela thread do barbeiro.
- Desistências: `CHEGADA: Barbearia lotada (Lotação: 20/20). Desistiu.` ou `SAÍDA: ... Desistiu. Saiu (...)`.
- Linhas iniciadas por `#` são informativas (ambiente da execução, diagnóstico de falha).
- Se os acentos aparecerem trocados no console, consulte o arquivo em `logs/`, que é sempre UTF-8.

---

## Experimento de afinidade de CPU

Compara o **Cenário A** (JVM restrita a 1 núcleo) com o **Cenário B** (todos os núcleos).

```powershell
.\scripts\rodar-experimento.ps1     # Windows
```

```bash
scripts/rodar-experimento.sh        # Linux (requer taskset)
```

O script compila, roda 5 execuções de cada cenário (intercaladas, 55 clientes, seed 42) e grava
[resultados/tabela-q4.md](resultados/tabela-q4.md). Ele aborta sem gravar a tabela se alguma execução falhar,
atender menos de 50 clientes ou se o cenário A não enxergar exatamente 1 processador. Parâmetros:
`-Clientes`, `-Repeticoes`, `-Seed`, `-IntervaloMaxMs`, `-CorteMs`, `-PagamentoMs` (no `.sh`, posicionais na
mesma ordem).

Para rodar um cenário avulso (aceita as mesmas opções do `Main`). Esses scripts **não compilam**: rode antes
o passo 1 do [Início rápido](#início-rápido).

|                      | Windows                         | Linux                        |
| -------------------- | ------------------------------- | ---------------------------- |
| Cenário A (1 núcleo) | `.\scripts\rodar-cenario-a.ps1` | `scripts/rodar-cenario-a.sh` |
| Cenário B (livre)    | `.\scripts\rodar-cenario-b.ps1` | `scripts/rodar-cenario-b.sh` |

A primeira linha do log registra `processadores visiveis para a JVM: N`: no cenário A deve ser 1.

---

## Testes

```bash
scripts/run-tests.sh                # ou .\scripts\run-tests.ps1
java -cp out tests.RobustnessTest   # um teste isolado (depois de compilar com o runner)
```

O runner compila `src/` e `tests/`, roda os 10 testes (cerca de 50 s) e sai com código ≠ 0 se algum falhar.
O que cada teste cobre está em [tests/tests.md](tests/tests.md).

---

## Estrutura

```
src/main/java/barbearia/
├── Main.java, Simulation.java
├── barbeiro/     Barber, BarberChair, BarberState
├── cliente/      Client, WaitingRoom
└── integracao/   AuditLogger, Capacity, Metrics, PosTerminal
tests/            testes (pacote "tests")
scripts/          run-tests, rodar-cenario-a/b, rodar-experimento (.sh e .ps1)
resultados/       tabela-q4.md (CSVs gerados não são versionados)
```

## Documentação

- [visao-geral.md](visao-geral.md): o problema, glossário, requisitos, restrições e arquitetura.
- [tests/tests.md](tests/tests.md): cobertura dos testes e últimos resultados.
- [notas_decisoes.md](notas_decisoes.md): decisões de implementação e análise do experimento.
