[CmdletBinding()]
param(
    [string]$TvIp,
    [int]$Port = 5555,
    [switch]$Build,
    [switch]$Launch,
    [switch]$UninstallFirst,
    [switch]$SkipConnect,
    [switch]$DryRun,
    [string]$ApkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$packageName = "com.m3u.tv"

function Write-Section {
    param([string]$Message)

    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Detail {
    param([string]$Message)

    Write-Host "    $Message"
}

function Format-Argument {
    param([string]$Value)

    if ($Value -match '[\s"]') {
        return '"' + ($Value -replace '"', '\"') + '"'
    }
    return $Value
}

function Format-Command {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    return (@(Format-Argument $FilePath) + ($Arguments | ForEach-Object { Format-Argument $_ })) -join " "
}

function Invoke-ExternalCommand {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [switch]$IgnoreExitCode
    )

    $display = Format-Command -FilePath $FilePath -Arguments $Arguments
    if ($DryRun) {
        Write-Host "[DryRun] $display" -ForegroundColor Yellow
        return 0
    }

    & $FilePath @Arguments
    $exitCode = $LASTEXITCODE
    if (-not $IgnoreExitCode -and $exitCode -ne 0) {
        throw "Command failed with exit code ${exitCode}: $display"
    }
    return $exitCode
}

function Resolve-ExistingPath {
    param([string[]]$Candidates)

    foreach ($candidate in $Candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    return $null
}

function Resolve-AdbPath {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command -and $command.Source) {
        return $command.Source
    }

    $candidates = @(
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        "C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe",
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )

    return Resolve-ExistingPath -Candidates $candidates
}

function Resolve-JavaHome {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand -and $javaCommand.Source) {
        $javaHomeFromPath = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
        $resolvedJavaHomeFromPath = Resolve-ExistingPath -Candidates @($javaHomeFromPath)
        if ($resolvedJavaHomeFromPath) {
            return $resolvedJavaHomeFromPath
        }
    }

    $bundledOpenJdks = @()
    $openJdkRoot = "C:\Program Files\Android\openjdk"
    if (Test-Path $openJdkRoot) {
        $bundledOpenJdks = Get-ChildItem -Path $openJdkRoot -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object FullName
    }

    $candidates = @($env:JAVA_HOME) + $bundledOpenJdks + @(
        "C:\Program Files\Android\Android Studio\jbr"
    )

    return Resolve-ExistingPath -Candidates $candidates
}

function Resolve-AndroidSdkRoot {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        "C:\Program Files (x86)\Android\android-sdk",
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    )

    return Resolve-ExistingPath -Candidates $candidates
}

function Resolve-ApkPath {
    param(
        [string]$ExplicitPath,
        [switch]$AllowMissing
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        if (-not (Test-Path $ExplicitPath)) {
            throw "APK not found at '$ExplicitPath'."
        }
        return (Resolve-Path $ExplicitPath).Path
    }

    $apkFiles = Get-ChildItem -Path (Join-Path $projectRoot "app\tv\build\outputs\apk\debug\tv-*.apk") -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending

    $latest = $apkFiles | Select-Object -First 1
    if (-not $latest) {
        if ($AllowMissing) {
            return (Join-Path $projectRoot "app\tv\build\outputs\apk\debug\tv-<version>.apk")
        }
        throw "No TV APK was found. Run the script with -Build first."
    }

    return $latest.FullName
}

function Invoke-AdbConnect {
    param(
        [string]$AdbPath,
        [string]$DeviceAddress
    )

    $arguments = @("connect", $DeviceAddress)
    $display = Format-Command -FilePath $AdbPath -Arguments $arguments
    if ($DryRun) {
        Write-Host "[DryRun] $display" -ForegroundColor Yellow
        return
    }

    $output = & $AdbPath @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $outputText = ($output | Out-String).Trim()
    if ($outputText) {
        Write-Host $outputText
    }
    if ($exitCode -ne 0) {
        throw "adb connect failed with exit code ${exitCode}: $outputText"
    }
    if ($outputText -notmatch "connected to|already connected to") {
        throw "Could not reach the TV at $DeviceAddress. Check the IP address and confirm that network debugging is enabled. adb said: $outputText"
    }
}

function Wait-ForAuthorizedDevice {
    param(
        [string]$AdbPath,
        [string]$DeviceAddress
    )

    $arguments = @("-s", $DeviceAddress, "get-state")
    $display = Format-Command -FilePath $AdbPath -Arguments $arguments
    if ($DryRun) {
        Write-Host "[DryRun] $display" -ForegroundColor Yellow
        return
    }

    for ($attempt = 1; $attempt -le 15; $attempt++) {
        $output = & $AdbPath @arguments 2>&1
        $stateText = ($output | Out-String).Trim()
        if ($stateText -eq "device") {
            return
        }

        if ($stateText -match "unauthorized") {
            Write-Host "Waiting for device authorization. Check the TV and accept the debugging prompt." -ForegroundColor Yellow
        } elseif ($stateText) {
            Write-Host "Current adb device state: $stateText" -ForegroundColor Yellow
        }

        Start-Sleep -Seconds 2
    }

    throw "The TV is connected but not ready for installs yet. Check the TV for a debugging authorization prompt, then run the script again."
}

Write-Section "M3UAndroid TV installer"
Write-Detail "Project root: $projectRoot"
Write-Detail "Dry run: $DryRun"

$shouldBuild = $Build
if (-not $ApkPath) {
    $hasExistingApk = Get-ChildItem -Path (Join-Path $projectRoot "app\tv\build\outputs\apk\debug\tv-*.apk") -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $hasExistingApk) {
        $shouldBuild = $true
        Write-Section "No existing TV APK found"
        Write-Detail "A fresh TV APK will be built automatically."
    }
}

if ($shouldBuild) {
    Write-Section "Building the TV APK"

    if (-not (Test-Path $gradleWrapper)) {
        throw "Gradle wrapper not found at '$gradleWrapper'."
    }

    $javaHome = Resolve-JavaHome
    $androidSdkRoot = Resolve-AndroidSdkRoot

    if (-not $javaHome) {
        throw "Could not find a JDK. Set JAVA_HOME or install Android Studio's OpenJDK."
    }
    if (-not $androidSdkRoot) {
        throw "Could not find the Android SDK. Set ANDROID_HOME or ANDROID_SDK_ROOT."
    }

    Write-Detail "JAVA_HOME: $javaHome"
    Write-Detail "ANDROID_HOME: $androidSdkRoot"

    if ($DryRun) {
        Write-Host "[DryRun] Would set JAVA_HOME=$javaHome" -ForegroundColor Yellow
        Write-Host "[DryRun] Would set ANDROID_HOME=$androidSdkRoot" -ForegroundColor Yellow
        Write-Host "[DryRun] Would set ANDROID_SDK_ROOT=$androidSdkRoot" -ForegroundColor Yellow
    } else {
        $env:JAVA_HOME = $javaHome
        $env:ANDROID_HOME = $androidSdkRoot
        $env:ANDROID_SDK_ROOT = $androidSdkRoot
        $env:Path = "$javaHome\bin;$androidSdkRoot\platform-tools;$env:Path"
    }

    [void](Invoke-ExternalCommand -FilePath $gradleWrapper -Arguments @(":app:tv:assembleDebug", "--console=plain", "--no-daemon"))
}

$resolvedApkPath = Resolve-ApkPath -ExplicitPath $ApkPath -AllowMissing:($DryRun -and $shouldBuild)
Write-Section "Using APK"
Write-Detail $resolvedApkPath

$adbPath = Resolve-AdbPath
if (-not $adbPath) {
    throw "Could not find adb.exe. Install Android platform-tools or set ANDROID_HOME."
}

Write-Section "ADB"
Write-Detail "adb path: $adbPath"

$deviceAddress = $null
if (-not $SkipConnect) {
    if ([string]::IsNullOrWhiteSpace($TvIp)) {
        $TvIp = Read-Host "Enter your TV IP address (example: 192.168.1.50)"
    }
    if ([string]::IsNullOrWhiteSpace($TvIp)) {
        throw "TV IP is required unless -SkipConnect is used."
    }

    if ($TvIp -match ":\d+$") {
        $deviceAddress = $TvIp
    } else {
        $deviceAddress = "{0}:{1}" -f $TvIp, $Port
    }

    Write-Section "Connecting to TV"
    Write-Detail "Device: $deviceAddress"
    Write-Detail "If the TV prompts you to trust this computer, choose Allow."

    Invoke-AdbConnect -AdbPath $adbPath -DeviceAddress $deviceAddress
    Wait-ForAuthorizedDevice -AdbPath $adbPath -DeviceAddress $deviceAddress
} else {
    Write-Section "Skipping adb connect"
    Write-Detail "Using an already-connected device."
}

$serialArgs = @()
if ($deviceAddress) {
    $serialArgs = @("-s", $deviceAddress)
}

if ($UninstallFirst) {
    Write-Section "Removing old installation"
    [void](Invoke-ExternalCommand -FilePath $adbPath -Arguments ($serialArgs + @("uninstall", $packageName)) -IgnoreExitCode)
}

Write-Section "Installing APK"
[void](Invoke-ExternalCommand -FilePath $adbPath -Arguments ($serialArgs + @("install", "-r", $resolvedApkPath)))

if ($Launch) {
    Write-Section "Launching M3UAndroid on the TV"
    [void](Invoke-ExternalCommand -FilePath $adbPath -Arguments ($serialArgs + @("shell", "monkey", "-p", $packageName, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")))
}

Write-Section "Next steps on the TV"
Write-Detail "Open M3UAndroid."
Write-Detail "Go to the user icon in the top-right corner, then choose Subscribe."
Write-Detail "For Xtream, use Basic URL like http://server:port plus username and password."
Write-Detail "For M3U, use a title and playlist URL."
Write-Detail "Add EPG separately if you want guide data."
Write-Detail "Live TV plays directly. Movies and Series open their detail pages."

Write-Section "Done"
Write-Detail "If you want to preview changes first next time, run with -DryRun."
