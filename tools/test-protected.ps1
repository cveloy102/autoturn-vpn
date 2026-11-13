param([string]$Jdk = "$PSScriptRoot/../.deps/jdk17/jdk-17.0.20.1+1")
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/..").Path
$taskClasses = Join-Path $taskRoot '.work/protected-tests'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -d $taskClasses `
    "$taskRoot/app/src/main/java/app/autoturn/vpn/ProtectionRules.java" `
    "$taskRoot/app/src/main/java/app/autoturn/vpn/BootstrapDns.java" `
    "$PSScriptRoot/tests/ProtectedModeTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Protected-mode tests did not compile' }
& "$Jdk/bin/java.exe" -cp $taskClasses app.autoturn.vpn.ProtectedModeTest
if ($LASTEXITCODE -ne 0) { throw 'Protected-mode tests failed' }
