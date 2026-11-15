[CmdletBinding()]
param(
    [string]$AppDir = $(if ($env:APP_DIR) { $env:APP_DIR } else { Join-Path $HOME "Telegram-bot-setup" }),
    [string]$RepoUrl = $(if ($env:REPO_URL) { $env:REPO_URL } else { "https://github.com/ether4o4/Telegram-bot-setup.git" }),
    [string[]]$Provider = @(),
    [switch]$AllProviders,
    [switch]$Reconfigure,
    [switch]$NonInteractive
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step([string]$Message) { Write-Host "[+] $Message" -ForegroundColor Cyan }
function Stop-Install([string]$Message) { throw "[error] $Message" }

function Require-Command([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Stop-Install "$Name is required. $Hint"
    }
}

Write-Step "Telegram-bot-setup one-shot installer"
Require-Command "git" "Install Git from https://git-scm.com/download/win and rerun."
Require-Command "python" "Install Python 3.10+ from python.org or the Microsoft Store and rerun."

python -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
if ($LASTEXITCODE -ne 0) { Stop-Install "Python 3.10 or newer is required." }

if (Test-Path (Join-Path $AppDir ".git")) {
    $dirty = git -C $AppDir status --porcelain
    if ($dirty) {
        Write-Warning "Existing checkout has uncommitted changes; leaving source untouched."
    } else {
        Write-Step "Updating existing checkout"
        git -C $AppDir pull --ff-only
        if ($LASTEXITCODE -ne 0) { Stop-Install "Git update failed." }
    }
} elseif (Test-Path $AppDir) {
    Stop-Install "$AppDir exists but is not a Git checkout. Move it or pass -AppDir."
} else {
    Write-Step "Cloning into $AppDir"
    git clone --depth 1 $RepoUrl $AppDir
    if ($LASTEXITCODE -ne 0) { Stop-Install "Git clone failed." }
}

Set-Location $AppDir
Write-Step "Creating isolated Python environment"
python -m venv .venv
if ($LASTEXITCODE -ne 0) { Stop-Install "Could not create the Python environment." }

$Python = Join-Path $AppDir ".venv\Scripts\python.exe"
& $Python -m pip install --upgrade pip
& $Python -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) { Stop-Install "Python dependency installation failed." }

$setupArgs = @()
foreach ($name in $Provider) { $setupArgs += @("--provider", $name) }
if ($AllProviders) { $setupArgs += "--all-providers" }
if ($NonInteractive) { $setupArgs += "--non-interactive" }

$needsSetup = -not (Test-Path ".env") -or $Reconfigure -or $setupArgs.Count -gt 0
if ($needsSetup) {
    Write-Step "Configuring Telegram and AI providers"
    & $Python -m src.setup_env @setupArgs
    if ($LASTEXITCODE -ne 0) { Stop-Install "Configuration failed; no invalid .env was written." }
} else {
    & $Python -m src.setup_env --validate-only
    if ($LASTEXITCODE -ne 0) {
        if ($NonInteractive) { Stop-Install "Existing configuration is invalid." }
        Write-Warning "Existing configuration needs attention; opening the setup assistant."
        & $Python -m src.setup_env
        if ($LASTEXITCODE -ne 0) { Stop-Install "Configuration is still invalid." }
    }
}

& $Python -m src.setup_env --validate-only
if ($LASTEXITCODE -ne 0) { Stop-Install "Final configuration validation failed." }

Write-Step "Install complete"
Write-Host "    Start: cd `"$AppDir`"; .\start.ps1"
Write-Host "    Check: & `"$Python`" -m src.setup_env --validate-only"
