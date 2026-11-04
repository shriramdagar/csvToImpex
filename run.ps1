param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ScriptArgs
)

$JarPath = Join-Path $PSScriptRoot "build\libs\hybris-impex-converter-1.0.0.jar"

if (-not (Test-Path $JarPath)) {
    Write-Host "[INFO] Building application with Gradle..." -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "gradlew.bat") jar
}

java -jar $JarPath $ScriptArgs
