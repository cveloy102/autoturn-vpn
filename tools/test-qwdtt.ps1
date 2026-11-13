param(
    [string]$Go = "$PSScriptRoot/../.deps/go-qwdtt/go/bin/go.exe",
    [string]$Jdk = "$PSScriptRoot/../.deps/jdk17/jdk-17.0.20.1+1"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/..").Path
$taskClasses = Join-Path $taskRoot '.work/qwdtt-config-test'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -d $taskClasses "$taskRoot/app/src/main/java/app/autoturn/vpn/QwdttConfig.java" "$PSScriptRoot/tests/QwdttConfigTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Parser compilation failed' }
& "$Jdk/bin/java.exe" -cp $taskClasses app.autoturn.vpn.QwdttConfigTest
if ($LASTEXITCODE -ne 0) { throw 'Parser tests failed' }
Push-Location "$taskRoot/third_party/qwdtt-client/src"
try {
    & $Go test -mod=readonly -v socks5_server.go socks5_server_test.go
    if ($LASTEXITCODE -ne 0) { throw 'SOCKS tests failed' }
    & $Go test -mod=readonly -v bootstrap_dns.go bootstrap_dns_test.go socks5_server.go
    if ($LASTEXITCODE -ne 0) { throw 'Bootstrap DNS isolation tests failed' }
} finally { Pop-Location }
# This uses the existing checkout matching the app's libXray.aar, without modifying it.
if (!(Test-Path -LiteralPath "$taskRoot/.deps/libxray/go.mod")) {
    throw 'Xray chain test needs the existing .deps/libxray source checkout'
}
$taskChain = Join-Path $taskRoot '.work/qwdtt-chain'
New-Item -ItemType Directory -Path $taskChain -Force | Out-Null
Copy-Item -LiteralPath "$taskRoot/third_party/qwdtt-client/src/socks5_server.go" -Destination "$taskChain/socks5_server.go"
Copy-Item -LiteralPath "$taskRoot/third_party/qwdtt-client/src/socks5_server_test.go" -Destination "$taskChain/socks5_server_test.go"
Copy-Item -LiteralPath "$PSScriptRoot/tests/QwdttChainTest.go" -Destination "$taskChain/chain_test.go"
Copy-Item -LiteralPath "$PSScriptRoot/tests/QwdttChain.go.mod" -Destination "$taskChain/go.mod"
Push-Location $taskChain
try {
    & $Go test -mod=mod -v -run TestXrayQwdttAuthenticatedTCPAndUDP .
    if ($LASTEXITCODE -ne 0) { throw 'Xray chain test failed' }
} finally { Pop-Location }
