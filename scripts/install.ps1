[CmdletBinding()]
param(
    # Install the Shade Jar built at cli/target/wecode.jar by default.
    [string]$JarPath = (Join-Path $PSScriptRoot "..\cli\target\wecode.jar")
)

$ErrorActionPreference = "Stop"
$installRoot = Join-Path $env:LOCALAPPDATA "wecode"
$binDirectory = Join-Path $installRoot "bin"
$targetJar = Join-Path $installRoot "wecode.jar"
$launcherTemplate = Join-Path $PSScriptRoot "wecode.cmd"
$targetLauncher = Join-Path $binDirectory "wecode.cmd"

# Validate the source Jar before replacing the existing user installation.
$resolvedJar = Resolve-Path -LiteralPath $JarPath -ErrorAction SilentlyContinue
if ($null -eq $resolvedJar -or -not (Test-Path -LiteralPath $resolvedJar -PathType Leaf)) {
    throw "Installable Jar not found: $JarPath. Run mvn -q -pl cli -am package first."
}

# Stop if the launcher template is missing instead of creating a partial command installation.
if (-not (Test-Path -LiteralPath $launcherTemplate -PathType Leaf)) {
    throw "Launcher template not found: $launcherTemplate"
}

# Create the user-scoped install directory and update only the Jar and launcher files.
New-Item -ItemType Directory -Path $binDirectory -Force | Out-Null
Copy-Item -LiteralPath $resolvedJar -Destination $targetJar -Force
Copy-Item -LiteralPath $launcherTemplate -Destination $targetLauncher -Force

# Append the launcher directory only once, avoiding duplicate user PATH entries.
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$pathEntries = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$existsInPath = $pathEntries | Where-Object {
    [string]::Equals($_.TrimEnd('\'), $binDirectory.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)
}

if ($null -eq $existsInPath) {
    $newUserPath = @($pathEntries + $binDirectory) -join ';'
    [Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
}

Write-Host "WeCode installed: $targetJar"
Write-Host "Launcher directory added to user PATH: $binDirectory"
Write-Host "Open a new terminal and run: wecode --help"
