[CmdletBinding()]
param(
    [switch]$Build = $false,
    [switch]$Launch = $true,
    [switch]$ForceUser0 = $false,
    [string]$AdbPath = "C:\MesProgrammes\adb-platform-tools\adb.exe",
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Definition }
$projectRoot = Split-Path $scriptDir -Parent

if (-not $ApkPath) {
    $ApkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
}

Write-Host "=== DÃ©ploiement VarioAppli ===" -ForegroundColor Cyan

# 1. VÃ©rification ADB
if (-not (Test-Path $AdbPath)) {
    Write-Error "Impossible de trouver adb.exe Ã  l'emplacement : $AdbPath"
    exit 1
}

# 2. VÃ©rification appareil connectÃ©
Write-Host "VÃ©rification de l'appareil USB..." -ForegroundColor Gray
$devicesOutput = & $AdbPath devices
$deviceLines = $devicesOutput | Where-Object { $_ -match "^\s*([a-zA-Z0-9\.\:\-]+)\s+(device|unauthorized|offline)" }

if (-not $deviceLines) {
    Write-Host "`n[ERREUR] Aucun appareil Android dÃ©tectÃ© en USB." -ForegroundColor Red
    Write-Host "VÃ©rifiez que :" -ForegroundColor Yellow
    Write-Host " 1. Le tÃ©lÃ©phone est branchÃ© en USB."
    Write-Host " 2. Le 'DÃ©bogage USB' est activÃ© dans les Options pour dÃ©veloppeurs."
    Write-Host " 3. Le cÃ¢ble USB permet le transfert de donnÃ©es (pas uniquement la charge).`n"
    exit 1
}

$unauthorized = $deviceLines | Where-Object { $_ -match "unauthorized" }
if ($unauthorized) {
    Write-Host "`n[ATTENTION] L'appareil est dÃ©tectÃ© mais NON AUTORISÃ‰ ('unauthorized')." -ForegroundColor Yellow
    Write-Host "DÃ©verrouillez votre tÃ©lÃ©phone et appuyez sur 'Autoriser le dÃ©bogage USB' sur l'Ã©cran.`n"
    exit 1
}

Write-Host "Appareil dÃ©tectÃ© avec succÃ¨s." -ForegroundColor Green

# 3. Compilation optionnelle
if ($Build) {
    Write-Host "`nCompilation de l'APK (assembleDebug)..." -ForegroundColor Cyan
    Push-Location $projectRoot
    try {
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) {
            Write-Error "La compilation Gradle a Ã©chouÃ© avec le code $LASTEXITCODE."
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}

# 4. VÃ©rification prÃ©sence APK
if (-not (Test-Path $ApkPath)) {
    Write-Error "Fichier APK introuvable : $ApkPath. Veuillez lancer la compilation d'abord."
    exit 1
}

# 5. Installation APK
Write-Host "`nInstallation de l'APK sur le tÃ©lÃ©phone..." -ForegroundColor Cyan

$resolvedApk = (Resolve-Path $ApkPath).Path

function Try-Install([string[]]$argsList) {
    $proc = Start-Process -FilePath $AdbPath -ArgumentList $argsList -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$env:TEMP\adb_out.txt" -RedirectStandardError "$env:TEMP\adb_err.txt"
    $out = Get-Content "$env:TEMP\adb_out.txt" -Raw -ErrorAction SilentlyContinue
    $err = Get-Content "$env:TEMP\adb_err.txt" -Raw -ErrorAction SilentlyContinue
    return [PSCustomObject]@{
        ExitCode = $proc.ExitCode
        Output = "$out`n$err".Trim()
    }
}

$installArgs = @("install", "-r", "-d")
if ($ForceUser0) {
    $installArgs += @("--user", "0")
}
$installArgs += $resolvedApk

$res = Try-Install $installArgs

if ($res.Output -match "Success") {
    Write-Host "Installation rÃ©ussie !" -ForegroundColor Green
} else {
    Write-Host "Retour ADB : $($res.Output)" -ForegroundColor Yellow
    
    # Tentative automatique avec --user 0 si profil pro restreint
    if (-not $ForceUser0 -and ($res.Output -match "INSTALL_FAILED_USER_RESTRICTED" -or $res.Output -match "SecurityException" -or $res.Output -match "Failure")) {
        Write-Host "Tentative d'installation ciblÃ©e sur l'espace personnel (--user 0)..." -ForegroundColor Magenta
        $resUser0 = Try-Install @("install", "--user", "0", "-r", "-d", $resolvedApk)
        if ($resUser0.Output -match "Success") {
            Write-Host "Installation rÃ©ussie sur le profil utilisateur 0 !" -ForegroundColor Green
        } else {
            Write-Error "Ã‰chec de l'installation : $($resUser0.Output)"
            exit 1
        }
    } else {
        Write-Error "Ã‰chec de l'installation de l'APK : $($res.Output)"
        exit 1
    }
}

# 6. DÃ©marrage automatique de l'activitÃ©
if ($Launch) {
    Write-Host "`nLancement de l'application..." -ForegroundColor Cyan
    & $AdbPath shell am start -n "com.vario.app/.MainActivity"
}

Write-Host "`nDÃ©ploiement terminÃ© avec succÃ¨s !" -ForegroundColor Green
