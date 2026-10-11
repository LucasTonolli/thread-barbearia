# Experimento de afinidade de CPU (Q4): executa os cenários A (1 núcleo) e B (multicore livre)
# N vezes cada, intercalados, e grava resultados/tabela-q4.md a partir dos CSVs reais.
# Uso: .\scripts\rodar-experimento.ps1 [-Clientes 55] [-Repeticoes 5] [-Seed 42] [-IntervaloMaxMs 25]
#                                      [-CorteMs 30] [-PagamentoMs 15]
# Padrão de 55 clientes: com 50, o cenário A às vezes perde o último cliente por lotação e fica com
# 49 atendidos; 55 garante >= 50 atendidos sem reduzir a contenção (ver notas_decisoes.md).
# Aborta (sem gravar a tabela) se alguma execução falhar, se alguma atender menos de 50 clientes ou se
# o cenário A não enxergar exatamente 1 processador.
param(
    [int]$Clientes = 55,
    [int]$Repeticoes = 5,
    [long]$Seed = 42,
    [int]$IntervaloMaxMs = 25,
    [int]$CorteMs = 30,
    [int]$PagamentoMs = 15
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$pt = [Globalization.CultureInfo]::GetCultureInfo("pt-BR")
$inv = [Globalization.CultureInfo]::InvariantCulture

if (Test-Path out) { Remove-Item out -Recurse -Force }
New-Item -ItemType Directory out | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d out $sources
if ($LASTEXITCODE -ne 0) { throw "falha na compilação" }

New-Item -ItemType Directory -Force resultados | Out-Null
Remove-Item resultados\resultados-A-*.csv, resultados\resultados-B-*.csv -ErrorAction SilentlyContinue
$params = "--clientes $Clientes --seed $Seed --intervalo-max-ms $IntervaloMaxMs --corte-ms $CorteMs --pagamento-ms $PagamentoMs"
foreach ($k in 1..$Repeticoes) {
    foreach ($cen in "A", "B") {
        Write-Host "Cenário $cen - execução $k/$Repeticoes"
        $script = ".\scripts\rodar-cenario-$($cen.ToLower()).ps1"
        $argList = $params.Split(" ") + @("--execucao", "$k", "--silencioso")
        & $script @argList | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "execução $cen/$k falhou (código $LASTEXITCODE); ver logs\execucao-$cen-$k.log" }
    }
}

function Load($cen) { 1..$Repeticoes | ForEach-Object { Import-Csv "resultados\resultados-$cen-$_.csv" } }
function Values($rows, $col) { $rows | ForEach-Object { [double]::Parse($_.$col, $inv) } }
function Mean($rows, $col) { (Values $rows $col | Measure-Object -Average).Average }
function Sd($rows, $col) {
    $v = @(Values $rows $col)
    if ($v.Count -lt 2) { return 0 }
    $m = ($v | Measure-Object -Average).Average
    [math]::Sqrt((($v | ForEach-Object { ($_ - $m) * ($_ - $m) } | Measure-Object -Sum).Sum) / ($v.Count - 1))
}
function Fmt($x, $dec) { ([double]$x).ToString("N$dec", $pt) }
function Cell($rows, $col, $dec) { "$(Fmt (Mean $rows $col) $dec) ± $(Fmt (Sd $rows $col) $dec)" }
function MinMax($rows, $col) {
    $m = Values $rows $col | Measure-Object -Minimum -Maximum
    if ($m.Minimum -eq $m.Maximum) { "$($m.Minimum)" } else { "$($m.Minimum)–$($m.Maximum)" }
}

$A = Load "A"; $B = Load "B"
foreach ($r in $A + $B) {
    if ([int]$r.atendidos -lt 50) {
        throw "execução $($r.cenario)/$($r.execucao) atendeu só $($r.atendidos) clientes (< 50): aumente -Clientes"
    }
}
foreach ($r in $A) {
    if ([int]$r.processadores -ne 1) { throw "cenário A/$($r.execucao) viu $($r.processadores) processadores: afinidade não aplicada" }
}

$cpusA = MinMax $A 'processadores'; $cpusB = MinMax $B 'processadores'
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name.Trim()
$fisicos = (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum
$logicos = [Environment]::ProcessorCount
$so = (Get-CimInstance Win32_OperatingSystem)
$jdk = (cmd /c "java -version 2>&1") -join " / "
$data = Get-Date -Format "yyyy-MM-dd HH:mm"

$linhas = @()
foreach ($r in $A + $B) {
    $linhas += "| $($r.cenario) | $($r.execucao) | $($r.processadores) | $($r.atendidos) | $(Fmt ([double]::Parse($r.tempo_total_ms, $inv)) 1) | $(Fmt ([double]::Parse($r.espera_em_pe_ms, $inv)) 1) | $(Fmt ([double]::Parse($r.espera_sofa_ms, $inv)) 1) | $(Fmt ([double]::Parse($r.espera_pos_ms, $inv)) 2) | $(Fmt ([double]::Parse($r.util_b1_pct, $inv)) 1) | $(Fmt ([double]::Parse($r.util_b2_pct, $inv)) 1) | $(Fmt ([double]::Parse($r.util_b3_pct, $inv)) 1) |"
}

$md = @"
# Tabela Q4 — Monoprocessador vs. Multicore

Dados reais gerados por ``scripts/rodar-experimento.ps1`` em $data. Cada célula é a **média de $Repeticoes
execuções ± desvio-padrão amostral**. As execuções de A e B foram intercaladas (A1, B1, A2, B2, ...).
$Clientes clientes chegam em cada execução; quantos foram atendidos está em "Dados de controle" (mínimo exigido: 50).

| Métrica | Cenário A — Monoprocessador (1 núcleo) | Cenário B — Multicore livre ($logicos núcleos lógicos) |
| --- | --- | --- |
| Tempo total para atender $Clientes clientes (ms) | $(Cell $A 'tempo_total_ms' 1) | $(Cell $B 'tempo_total_ms' 1) |
| Tempo médio de espera em pé (ms) | $(Cell $A 'espera_em_pe_ms' 1) | $(Cell $B 'espera_em_pe_ms' 1) |
| Tempo médio de espera no sofá (ms) | $(Cell $A 'espera_sofa_ms' 1) | $(Cell $B 'espera_sofa_ms' 1) |
| Tempo médio de espera na fila da POS (ms) | $(Cell $A 'espera_pos_ms' 2) | $(Cell $B 'espera_pos_ms' 2) |
| Utilização do barbeiro B1 (%) | $(Cell $A 'util_b1_pct' 1) | $(Cell $B 'util_b1_pct' 1) |
| Utilização do barbeiro B2 (%) | $(Cell $A 'util_b2_pct' 1) | $(Cell $B 'util_b2_pct' 1) |
| Utilização do barbeiro B3 (%) | $(Cell $A 'util_b3_pct' 1) | $(Cell $B 'util_b3_pct' 1) |

## Dados de controle

| Item | Cenário A | Cenário B |
| --- | --- | --- |
| Processadores visíveis para a JVM (``Runtime.availableProcessors()``) | $cpusA | $cpusB |
| Clientes atendidos por execução | $(MinMax $A 'atendidos') de $Clientes | $(MinMax $B 'atendidos') de $Clientes |
| Desistências por lotação / sala cheia (total) | $((Values $A 'desist_lotacao' | Measure-Object -Sum).Sum) / $((Values $A 'desist_sala' | Measure-Object -Sum).Sum) | $((Values $B 'desist_lotacao' | Measure-Object -Sum).Sum) / $((Values $B 'desist_sala' | Measure-Object -Sum).Sum) |
| Clientes que passaram pela fila em pé (média) | $(Fmt (Mean $A 'clientes_em_pe') 1) | $(Fmt (Mean $B 'clientes_em_pe') 1) |

## Definições das métricas

- **Tempo total:** do início das threads dos barbeiros até o ``join`` do último barbeiro (inclui o período de
  chegadas).
- **Espera em pé:** entrada no sofá − entrada na fila em pé, só entre os atendidos que ficaram em pé.
- **Espera no sofá:** chamada pelo barbeiro − entrada no sofá, sobre todos os atendidos.
- **Espera na fila da POS:** tempo bloqueado em ``PosTerminal.acquire`` (posse − pedido).
- **Utilização:** (corte + pagamento medidos com ``System.nanoTime``) / tempo total, por barbeiro. Não inclui
  a espera pela POS.

## Ambiente e parâmetros

- SO: $($so.Caption) (versão $($so.Version))
- CPU: $cpu — $fisicos núcleos físicos, $logicos processadores lógicos
- JDK: $jdk
- Parâmetros: $Clientes clientes, 3 barbeiros, lotação 20 (sofá 4 + em pé 13 + 3 cadeiras), corte $CorteMs ms,
  pagamento $PagamentoMs ms, intervalo entre chegadas sorteado em [0, $IntervaloMaxMs] ms com semente $Seed
  (mesma sequência de chegadas em todas as execuções).
- Execuções: $Repeticoes por cenário ($(2 * $Repeticoes) no total).
- Cenário A: ``cmd /v:on /c "start "" /b /wait /affinity 1 java ..."`` (máscara 1 = só o processador lógico 0,
  aplicada antes de a JVM iniciar). Cenário B: ``java ...`` sem restrição.
- Comando: ``.\scripts\rodar-experimento.ps1 -Clientes $Clientes -Repeticoes $Repeticoes -Seed $Seed -IntervaloMaxMs $IntervaloMaxMs -CorteMs $CorteMs -PagamentoMs $PagamentoMs``

## Execuções individuais

| Cenário | Execução | Proc. | Atendidos | Tempo total (ms) | Em pé (ms) | Sofá (ms) | POS (ms) | B1 (%) | B2 (%) | B3 (%) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
$($linhas -join "`n")

CSVs de cada execução: ``resultados/resultados-<cenário>-<k>.csv`` (não versionados). Logs completos:
``logs/execucao-<cenário>-<k>.log``.
"@
[IO.File]::WriteAllText((Join-Path (Get-Location) "resultados\tabela-q4.md"), ($md -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
Write-Host "Tabela gravada em resultados\tabela-q4.md"
