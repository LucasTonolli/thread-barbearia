# Compila src/ e tests/ e executa todos os testes. Sai com codigo != 0 se algum falhar.
# Uso: .\scripts\run-tests.ps1
Set-Location (Join-Path $PSScriptRoot "..")

$tests = @(
    "BarberManualTest",
    "WaitingRoomManualTest",
    "FifoIntegrationTest",
    "RobustnessTest",
    "FailureDetectionTest",
    "CapacityVsRoomTest",
    "WaitingRoomStressTest",
    "BarberStressTest",
    "IntegrationStressTest",
    "IntegrationInvariantsTest"
)

if (Test-Path out) { Remove-Item out -Recurse -Force }
New-Item -ItemType Directory out | Out-Null
$sources = Get-ChildItem src, tests -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -Xlint:all -d out $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "!!! falha na compilacao"
    exit 1
}

$failed = @()
foreach ($t in $tests) {
    Write-Host "################ $t"
    java -cp out "tests.$t"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "!!! $t FALHOU"
        $failed += $t
    }
}

Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "TODOS OS $($tests.Count) TESTES PASSARAM"
    exit 0
}
Write-Host "FALHARAM $($failed.Count) de $($tests.Count): $($failed -join ', ')"
exit 1
