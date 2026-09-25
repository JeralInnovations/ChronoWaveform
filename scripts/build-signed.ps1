param(
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.chrono-waveform/signing'),
    [string]$GradleCommand,
    [string]$UnsignedApk,
    [string]$ApkSignerCommand,
    [switch]$CreateKey
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$GradleCommand) { $GradleCommand = Join-Path $projectRoot 'android/gradlew.bat' }
$keyPath = Join-Path $SigningDirectory 'chrono-release.jks'
$credentialsPath = Join-Path $SigningDirectory 'credentials.json'

if (!(Test-Path -LiteralPath $keyPath)) {
    if (!$CreateKey) {
        throw 'No signing key found. Supply your existing key, or use -CreateKey once for a new installation. Back up old app data first.'
    }
    if (Test-Path -LiteralPath $credentialsPath) {
        throw 'Signing credentials exist but the key is missing. Restore the key backup; do not silently replace its identity.'
    }
    New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
    $random = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $generator.GetBytes($random)
    $generator.Dispose()
    $credentials = @{ alias = 'chrono'; password = [Convert]::ToBase64String($random) }
    $env:CHRONO_KEYSTORE_PASSWORD = $credentials.password
    & keytool -genkeypair -keystore $keyPath -storetype JKS -alias chrono `
        -storepass:env CHRONO_KEYSTORE_PASSWORD -keypass:env CHRONO_KEYSTORE_PASSWORD `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=ChronoWaveform'
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
    $credentials | ConvertTo-Json | Set-Content -LiteralPath $credentialsPath
    Write-Host "Created a persistent key. Back up this private directory: $SigningDirectory"
}

if (!(Test-Path -LiteralPath $credentialsPath)) {
    throw 'The key exists but credentials.json is missing. Restore the credentials backup.'
}
$credentials = Get-Content -LiteralPath $credentialsPath -Raw | ConvertFrom-Json
$env:CHRONO_KEYSTORE_FILE = (Resolve-Path -LiteralPath $keyPath).Path
$env:CHRONO_KEYSTORE_PASSWORD = $credentials.password
$env:CHRONO_KEY_PASSWORD = $credentials.password
$env:CHRONO_KEY_ALIAS = $credentials.alias
try {
    if ($UnsignedApk) {
        if (!$ApkSignerCommand) { throw 'Supply -ApkSignerCommand with the Android SDK apksigner path.' }
        $artifactDirectory = Join-Path $projectRoot 'artifacts'
        New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
        $outputApk = Join-Path $artifactDirectory 'ChronoWaveform-2.3.1.apk'
        & $ApkSignerCommand sign --ks $keyPath --ks-key-alias $credentials.alias `
            --ks-pass env:CHRONO_KEYSTORE_PASSWORD --key-pass env:CHRONO_KEY_PASSWORD `
            --out $outputApk $UnsignedApk
        if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
        & $ApkSignerCommand verify $outputApk
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
        Write-Host "Signed APK: $outputApk"
    } else {
        & $GradleCommand -p (Join-Path $projectRoot 'android') assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Signed APK build failed.' }
    }
} finally {
    Remove-Item Env:CHRONO_KEYSTORE_PASSWORD, Env:CHRONO_KEY_PASSWORD -ErrorAction SilentlyContinue
}
