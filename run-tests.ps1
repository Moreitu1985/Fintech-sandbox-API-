# LedgerForge: check prerequisites, then build and run every test.
# Run from this folder in PowerShell:  powershell -ExecutionPolicy Bypass -File .\run-tests.ps1

# Java and Maven write normal output to stderr; don't let Windows PowerShell treat that as fatal.
$ErrorActionPreference = "Continue"
Write-Host "== Checking prerequisites ==" -ForegroundColor Cyan

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Java not found. Install JDK 21: winget install EclipseAdoptium.Temurin.21.JDK" -ForegroundColor Red
    exit 1
}
$verLine = (cmd /c "java -version 2>&1" | Select-Object -First 1)
Write-Host "Java: $verLine"
if ($verLine -notmatch '"(2[1-9]|[3-9]\d)') {
    Write-Host "Java 21 or newer is required. Install it: winget install EclipseAdoptium.Temurin.21.JDK" -ForegroundColor Red
    exit 1
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "Maven not found. Install it: winget install Apache.Maven  (then open a NEW PowerShell window)" -ForegroundColor Red
    exit 1
}
Write-Host ("Maven: " + (cmd /c "mvn -v 2>&1" | Select-Object -First 1))

Write-Host "`n== Building and running tests (first run downloads libraries, ~2-5 min) ==" -ForegroundColor Cyan
cmd /c "mvn -B verify 2>&1" | Tee-Object -FilePath build-output.txt
$code = $LASTEXITCODE
if ($code -eq 0) {
    Write-Host "`nALL TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "`nBuild failed. The full log is in build-output.txt - tell Claude." -ForegroundColor Red
}
exit $code
