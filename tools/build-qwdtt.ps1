param([string]$Go = "$PSScriptRoot/../.deps/go-qwdtt/go/bin/go.exe")
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/..").Path
$taskOutput = Join-Path $taskRoot 'app/src/main/jniLibs/arm64-v8a/libqwdtt.so'
New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
$taskOldEnv = @{}
foreach ($key in @('GOOS', 'GOARCH', 'CGO_ENABLED', 'GOTOOLCHAIN')) {
    $taskOldEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
Push-Location "$taskRoot/third_party/qwdtt-client/src"
try {
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $env:CGO_ENABLED = '0'
    $env:GOTOOLCHAIN = 'local'
    & $Go build -mod=readonly -trimpath '-ldflags=-checklinkname=0 -s -w' -o $taskOutput .
    if ($LASTEXITCODE -ne 0) { throw 'qWDTT native build failed' }
    Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($key in $taskOldEnv.Keys) {
        [Environment]::SetEnvironmentVariable($key, $taskOldEnv[$key], 'Process')
    }
}
