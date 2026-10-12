<#
Civ V Living World - launcher for Windows.

  -Action play      start the game: the Minecraft instance. The whole game runs inside it; nothing else is started.
  -Action bridge    the older way: start Civilization V (debug socket on, windowed) and Minecraft, which then shows
                    and steers that Civ game; Civ's settings are put back afterwards
  -Action civ       start only Civilization V that way
  -Action minecraft start only the Minecraft instance, without touching which of the two ways it uses
  -Action restore   put Civ's own settings files back now (Civ must be closed)
  -Action status    show what is running and whether settings are currently changed

For "play" nothing outside the Minecraft instance is read or changed.

For "bridge", Civilization V itself is never modified. Three settings files in Documents are changed for the
length of one session and put back when Civ closes:
  config.ini                EnableTuner = 1     (the debug socket the bridge talks to; achievements are off while it is on)
  GraphicsSettingsDX11.ini  FullScreen = 0      (so both games can be on screen together)
  UserSettings.ini          SkipIntroVideo = 1
The originals are kept in <this folder>\backup until they are back in place.
#>
param(
    [ValidateSet('play', 'bridge', 'civ', 'minecraft', 'restore', 'status', 'watch')]
    [string]$Action = 'play',
    [int]$WindowWidth = 1920,
    [int]$WindowHeight = 1080,
    # Log a failure without showing a message box (for runs nobody is watching).
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$BackupDir = Join-Path $Root 'backup'
$LogFile = Join-Path $Root 'launcher.log'
$CivUserDir = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'My Games\Sid Meier''s Civilization 5'
$SettingsFiles = @('config.ini', 'GraphicsSettingsDX11.ini', 'UserSettings.ini')
# Any of the game's three executables counts as "Civ is running".
$CivProcesses = @('CivilizationV_DX11', 'CivilizationV', 'CivilizationV_Tablet')
$PrismExe = Join-Path $env:LOCALAPPDATA 'SkyCraft\Prism\prismlauncher.exe'
$Instance = 'CivLivingWorld'
$Latin1 = [Text.Encoding]::GetEncoding(28591)

function Log([string]$Message) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $LogFile -Value $line -Encoding ASCII
    Write-Output $Message
}

function Find-CivDir {
    $libraries = @()
    $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
    if (-not $steam) { $steam = 'C:\Program Files (x86)\Steam' }
    $vdf = Join-Path $steam 'steamapps\libraryfolders.vdf'
    if (Test-Path $vdf) {
        foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) {
            $libraries += $m.Groups[1].Value.Replace('\\', '\')
        }
    }
    $libraries += $steam
    foreach ($lib in $libraries) {
        $dir = Join-Path $lib 'steamapps\common\Sid Meier''s Civilization V'
        if (Test-Path (Join-Path $dir 'CivilizationV_DX11.exe')) { return $dir }
    }
    throw 'Civilization V was not found in any Steam library.'
}

function Test-CivRunning { return [bool](Get-Process -Name $CivProcesses -ErrorAction SilentlyContinue) }

function Test-SettingsChanged { return (Test-Path (Join-Path $BackupDir 'active.txt')) }

# Changes one "Key = value" line and leaves every other byte of the file as it was.
function Set-IniValue([string]$Path, [string]$Key, [string]$Value) {
    $text = [IO.File]::ReadAllText($Path, $Latin1)
    $pattern = '(?m)^(' + [regex]::Escape($Key) + '\s*=\s*)[^\r\n]*'
    if ($text -notmatch $pattern) { throw "$Key not found in $Path" }
    $new = [regex]::Replace($text, $pattern, ('${1}' + $Value), 1)
    [IO.File]::WriteAllText($Path, $new, $Latin1)
}

function Restore-Settings {
    if (-not (Test-SettingsChanged)) { return }
    if (Test-CivRunning) { throw 'Civilization V is still running; close it first.' }
    foreach ($name in $SettingsFiles) {
        $saved = Join-Path $BackupDir $name
        if (Test-Path $saved) { Copy-Item $saved (Join-Path $CivUserDir $name) -Force }
    }
    Remove-Item (Join-Path $BackupDir 'active.txt') -Force
    Log 'Civ settings restored to how they were.'
}

function Test-SteamSignedIn {
    $active = (Get-ItemProperty 'HKCU:\Software\Valve\Steam\ActiveProcess' -ErrorAction SilentlyContinue).ActiveUser
    return [bool]((Get-Process -Name steam -ErrorAction SilentlyContinue) -and $active -and $active -ne 0)
}

# Civilization V only starts with the Steam client running and signed in. After a fresh boot it
# may not be running, and at the Windows lock screen it does not sign in at all.
function Start-SteamIfNeeded {
    if (-not (Get-Process -Name steam -ErrorAction SilentlyContinue)) {
        $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamExe
        if (-not $steam -or -not (Test-Path $steam)) { $steam = 'C:\Program Files (x86)\Steam\steam.exe' }
        if (-not (Test-Path $steam)) { throw 'Steam was not found.' }
        Start-Process -FilePath $steam -ArgumentList '-silent'
        Log 'Steam was not running; started it.'
    }
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline -and -not (Test-SteamSignedIn)) { Start-Sleep -Seconds 2 }
    if (-not (Test-SteamSignedIn)) {
        throw 'Steam is running but not signed in. Unlock the desktop, sign in to Steam, and start again. Nothing was changed.'
    }
}

function Start-Civ {
    if (Test-CivRunning) { Log 'Civilization V is already running.'; return }
    Start-SteamIfNeeded
    # A previous session that ended badly (power cut, crash): first put the originals back.
    Restore-Settings
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    foreach ($name in $SettingsFiles) {
        Copy-Item (Join-Path $CivUserDir $name) (Join-Path $BackupDir $name) -Force
    }
    Set-Content -Path (Join-Path $BackupDir 'active.txt') -Value (Get-Date -Format o) -Encoding ASCII
    Set-IniValue (Join-Path $CivUserDir 'config.ini') 'EnableTuner' '1'
    Set-IniValue (Join-Path $CivUserDir 'UserSettings.ini') 'SkipIntroVideo' '1'
    $gfx = Join-Path $CivUserDir 'GraphicsSettingsDX11.ini'
    Set-IniValue $gfx 'FullScreen' '0'
    Set-IniValue $gfx 'WindowResX' "$WindowWidth"
    Set-IniValue $gfx 'WindowResY' "$WindowHeight"
    $civDir = Find-CivDir
    # Started directly, the game normally asks Steam to start it again, and Steam then picks the
    # DirectX 9 version. With its app id in the environment it accepts being started as it is.
    $env:SteamAppId = '8930'
    $env:SteamGameId = '8930'
    Start-Process -FilePath (Join-Path $civDir 'CivilizationV_DX11.exe') -WorkingDirectory $civDir
    Log "Civilization V started from $civDir (debug socket on, windowed ${WindowWidth}x${WindowHeight})."
    Start-Sleep -Seconds 15
    if (-not (Test-CivRunning)) {
        # It closed again straight away (seen at the lock screen): do not leave the settings changed.
        Restore-Settings
        throw 'Civilization V closed right after starting. Is the desktop unlocked and Steam signed in?'
    }
    # A separate hidden process waits for Civ to close and then restores the settings.
    Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $PSCommandPath), '-Action', 'watch')
}

function Watch-Civ {
    # Give the game time to appear, then wait until it has been gone for a while. (At start-up
    # one process can hand over to another, so a short gap does not mean the game was closed.)
    $deadline = (Get-Date).AddMinutes(3)
    while (-not (Test-CivRunning) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    $goneSince = $null
    while ($true) {
        if (Test-CivRunning) { $goneSince = $null }
        elseif (-not $goneSince) { $goneSince = Get-Date }
        elseif (((Get-Date) - $goneSince).TotalSeconds -ge 20) { break }
        Start-Sleep -Seconds 3
    }
    Restore-Settings
}

# Which way the mod plays is one line in its settings file: engine=builtin (the game inside Minecraft) or engine=civ5.
function Set-Engine([string]$Value) {
    $config = Join-Path (Split-Path -Parent $PrismExe) "instances\$Instance\.minecraft\config\livingworld.properties"
    $lines = @()
    if (Test-Path $config) { $lines = @([IO.File]::ReadAllLines($config)) }
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -like 'engine=*') { $lines[$i] = "engine=$Value"; $found = $true }
    }
    if (-not $found) { $lines += "engine=$Value" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $config) | Out-Null
    # No byte order mark: Java reads this file.
    [IO.File]::WriteAllLines($config, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
    Log "The game is set to run as '$Value'."
}

function Start-Minecraft {
    if (-not (Test-Path $PrismExe)) { throw "Prism Launcher was not found at $PrismExe (it comes with SkyCraft)." }
    $instanceDir = Join-Path (Split-Path -Parent $PrismExe) "instances\$Instance"
    if (-not (Test-Path $instanceDir)) { throw "The Minecraft instance '$Instance' is not installed in Prism." }
    Start-Process -FilePath $PrismExe -ArgumentList @('--launch', $Instance) -WorkingDirectory (Split-Path -Parent $PrismExe)
    Log "Minecraft instance '$Instance' started."
}

try {
    switch ($Action) {
        'play' { Set-Engine 'builtin'; Start-Minecraft }
        'bridge' { Set-Engine 'civ5'; Start-Civ; Start-Minecraft }
        'civ' { Start-Civ }
        'minecraft' { Start-Minecraft }
        'restore' { Restore-Settings; if (-not (Test-SettingsChanged)) { Write-Output 'Civ settings are the originals.' } }
        'watch' { Watch-Civ }
        'status' {
            Write-Output ("Civilization V running: {0}" -f (Test-CivRunning))
            Write-Output ("Minecraft (java) running: {0}" -f [bool](Get-Process -Name javaw, java -ErrorAction SilentlyContinue))
            Write-Output ("Civ settings changed for a session: {0}" -f (Test-SettingsChanged))
            $listen = Get-NetTCPConnection -LocalPort 4318 -State Listen -ErrorAction SilentlyContinue
            if ($listen) { $listen | ForEach-Object { Write-Output ("Debug socket listening on {0}:{1}" -f $_.LocalAddress, $_.LocalPort) } }
            else { Write-Output 'Debug socket: not listening' }
        }
    }
}
catch {
    Log ("FAILED: " + $_.Exception.Message)
    if (-not $Quiet -and [Environment]::UserInteractive -and $Action -in @('play', 'bridge', 'civ', 'minecraft', 'restore')) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'Civ V Living World') | Out-Null
    }
    exit 1
}
