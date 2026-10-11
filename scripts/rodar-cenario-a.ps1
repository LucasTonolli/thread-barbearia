# Cenario A: JVM restrita a 1 nucleo logico (Windows). Uso: .\scripts\rodar-cenario-a.ps1 [args do Main...]
# - Start-Process do Windows PowerShell 5.1 nao tem parametro -Affinity; usamos "start /affinity 1" do cmd
#   (mascara hexadecimal 1 = so o processador logico 0), aplicada antes de a JVM iniciar.
# - "start /wait" nao repassa o codigo de saida para "cmd /c"; por isso /v:on + "exit !errorlevel!".
# - A prova de que a JVM viu 1 nucleo esta na 1a linha do log e na coluna "processadores" do CSV.
Set-Location (Join-Path $PSScriptRoot "..")
$mainArgs = ($args -join " ")
cmd /v:on /c "start `"`" /b /wait /affinity 1 java -cp out barbearia.Main --cenario A $mainArgs & exit !errorlevel!"
exit $LASTEXITCODE
