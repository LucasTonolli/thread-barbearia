# Cenario B: multicore livre (Windows). Uso: .\scripts\rodar-cenario-b.ps1 [args do Main...]
Set-Location (Join-Path $PSScriptRoot "..")
java -cp out barbearia.Main --cenario B @args
exit $LASTEXITCODE
