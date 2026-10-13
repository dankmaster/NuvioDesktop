#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start','command')][string]$Action = 'command',
    [string]$SessionDirectory,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Build,
    [switch]$Visible,
    [string]$Command = 'status',
    [string]$RequestFile,
    [Nullable[long]]$PositionMs,
    [Nullable[double]]$Speed,
    [Nullable[int]]$Index,
    [Nullable[bool]]$Muted
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $SessionDirectory) { $SessionDirectory = Join-Path $repo '.debug-cli' }
$SessionDirectory = [System.IO.Path]::GetFullPath($SessionDirectory)
$session = Join-Path $SessionDirectory 'session.json'
if ($Action -eq 'command') {
    $params = @{ Session=$session; Command=$Command }
    foreach($key in @('RequestFile','PositionMs','Speed','Index','Muted')) {
        if ($PSBoundParameters.ContainsKey($key)) { $params[$key] = $PSBoundParameters[$key] }
    }
    & "$PSScriptRoot\debug-command.ps1" @params
    return
}
if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome (JDK 17 or newer).' }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'Java executable does not exist' }
if (Test-Path -LiteralPath $session) {
    try { & "$PSScriptRoot\debug-command.ps1" -Session $session -Command status | Out-Null; throw 'already-running' }
    catch { if ($_.Exception.Message -eq 'already-running') { throw 'Debug CLI already running; use a new session directory or shutdown.' } }
}
if ($Build) {
    Push-Location $repo
    try {
        if (-not (Test-Path -LiteralPath 'local.properties')) { Set-Content -LiteralPath 'local.properties' -Value '# Local debug build' }
        & "$repo\gradlew.bat" :composeApp:prepareDebugCli --console=plain --no-configuration-cache
        if ($LASTEXITCODE -ne 0) { throw 'Debug CLI build failed' }
    } finally { Pop-Location }
}
$classpathFile = Join-Path $repo 'composeApp\build\debug-cli\classpath.txt'
if (-not (Test-Path -LiteralPath $classpathFile)) { throw 'Run with -Build first.' }
New-Item -ItemType Directory -Path $SessionDirectory -Force | Out-Null
if ($IsWindows) {
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $SessionDirectory /inheritance:r /grant:r "*$($sid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect the debug session directory.' }
}
# Session files contain the local API token. Keep this directory private and untracked.
$arguments = @('--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED',
    '-cp',
    (Get-Content -LiteralPath $classpathFile -Raw), 'com.nuvio.app.MainKt', '--debug-cli', $SessionDirectory)
if (-not $Visible) { $arguments += '--background' }
$argumentFile = Join-Path $SessionDirectory 'java.args'
$lines = $arguments | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' }
[System.IO.File]::WriteAllLines($argumentFile, $lines, [System.Text.UTF8Encoding]::new($false))
$previousNativeData = $env:NUVIO_DEBUG_NATIVE_DATA_DIR
try {
    $env:NUVIO_DEBUG_NATIVE_DATA_DIR = Join-Path $SessionDirectory 'native'
    $process = Start-Process -FilePath $java -ArgumentList ('"@' + $argumentFile + '"') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $SessionDirectory 'stdout.log') -RedirectStandardError (Join-Path $SessionDirectory 'stderr.log')
} finally { $env:NUVIO_DEBUG_NATIVE_DATA_DIR = $previousNativeData }
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ([DateTime]::UtcNow -lt $deadline) {
    if ($process.HasExited) { throw 'Debug process exited; inspect stderr.log in the session directory.' }
    if (Test-Path -LiteralPath $session) {
        try { & "$PSScriptRoot\debug-command.ps1" -Session $session -Command status; return } catch { }
    }
    Start-Sleep -Milliseconds 200
}
throw 'Debug process did not become ready within 30 seconds.'
