# NetMode release pipeline: keystore -> unit tests -> assembleRelease -> collect artifact
# Usage:  powershell -File scripts\release.ps1 [-SkipTests]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "== shit-5G release ==" -ForegroundColor Cyan


# 0) locate java (JAVA_HOME or known local jdk)
$java = $env:JAVA_HOME
if (-not $java -or -not (Test-Path "$java\bin\java.exe")) {
    foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr", "D:\tools\jdk-17.0.21.0.2+1")) {
        if (Test-Path "$c\bin\java.exe") { $java = $c; break }
    }
}
if (-not $java) { throw "JDK 17 not found. Set JAVA_HOME or install Android Studio." }
$env:JAVA_HOME = $java
$env:Path = "$java\bin;" + $env:Path
Write-Host "JAVA_HOME = $java"
# Local workaround: this machine's security software blocks JDK's anonymous unix-domain
# sockets, breaking Gradle daemon/test-worker loopback (Selector.open fails). The patched
# PipeImpl/WEPollSelectorImpl classes below force TCP loopback. Harmless on other machines.
if (Test-Path "D:\tools\patch-out\sun\nio\ch\PipeImpl.class") {
    $env:JAVA_TOOL_OPTIONS = "--patch-module=java.base=D:\tools\patch-out"
    & "$PSScriptRoot\..\gradlew.bat" --stop | Out-Null
}

# 1) keystore bootstrap (persist locally; NEVER commit keystore.properties / *.keystore)
if (-not (Test-Path "$root\keystore.properties")) {
    Write-Host "keystore.properties missing -> generating shit5g.keystore (30y RSA2048)" -ForegroundColor Yellow
    $pw = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    $kt = "$java\bin\keytool.exe"
    $dn = "CN=shit-5G, OU=netmode, O=baiwusanyu, L=Unknown, ST=Unknown, C=CN"
    & $kt -genkeypair -keystore "$root\shit5g.keystore" -alias shit5g -keyalg RSA -keysize 2048 -validity 10950 -storepass $pw -keypass $pw -noprompt -dname $dn | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    $lines = @("storeFile=shit5g.keystore", "storePassword=$pw", "keyAlias=shit5g", "keyPassword=$pw")
    Set-Content -Path "$root\keystore.properties" -Value $lines -Encoding ascii
    Write-Host "Saved. BACK UP keystore.properties + shit5g.keystore (required for future upgrades)!" -ForegroundColor Green
}

# 2) unit test gate
if (-not $SkipTests) {
    Write-Host "== unit tests ==" -ForegroundColor Cyan
    & "$root\gradlew.bat" :app:testReleaseUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "unit tests FAILED - release blocked" }
}

# 3) assemble release
Write-Host "== assembleRelease ==" -ForegroundColor Cyan
& "$root\gradlew.bat" :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "assembleRelease FAILED" }

# 4) collect artifact, renamed as shit5g-<version>-release.apk
$out = "$root\release"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$gradleSrc = Get-Content "$root\app\build.gradle.kts" -Raw
$ver = [regex]::Match($gradleSrc, 'versionName = "([^"]+)"').Groups[1].Value
if (-not $ver) { $ver = "0.0.0" }
$apk = Get-ChildItem "$root\app\build\outputs\apk\release\app-release.apk" | Select-Object -First 1
$dest = Join-Path $out ("shit5g-" + $ver + "-release.apk")
Copy-Item $apk.FullName -Destination $dest -Force
Write-Host ("DONE -> " + $dest) -ForegroundColor Green
Write-Host "GitHub publish: git tag v1.0.0 ; git push origin v1.0.0  (see .github/workflows/release.yml)"
