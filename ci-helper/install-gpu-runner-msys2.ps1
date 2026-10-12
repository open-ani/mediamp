<#
.SYNOPSIS
Installs MSYS2 at C:\msys64 with the packages the Windows x64 FFmpeg, dav1d and mpv builds
need, for the self-hosted GPU render matrix runner (docs/gpu-test-runner.md). Run it again
to update the packages.

Downloads go through curl and pacman, which honour the https_proxy environment variable.
Run it while the runner is idle: updating MSYS2 stops every process using it.
#>
$ErrorActionPreference = 'Stop'

# Where the build looks for MSYS2 when the msys2.dir Gradle property is not set.
$msys2 = 'C:\msys64'
$bash = "$msys2\usr\bin\bash.exe"

# The MSYS2 packages the Windows x64 FFmpeg, dav1d and mpv builds ensure before building
# (buildSrc: ffmpeg/FfmpegTargets.kt, mpv/MpvTargets.kt). The build installs whatever is
# missing from here itself, which then needs the mirrors during the run.
$packages = @(
    'git'
    'make'
    'diffutils'
    'pkg-config'
    'mingw-w64-ucrt-x86_64-ca-certificates'
    'mingw-w64-ucrt-x86_64-gcc'
    'mingw-w64-ucrt-x86_64-nasm'
    'mingw-w64-ucrt-x86_64-openssl'
    'mingw-w64-ucrt-x86_64-libass'
    'mingw-w64-ucrt-x86_64-libplacebo'
    'mingw-w64-ucrt-x86_64-meson'
    'mingw-w64-ucrt-x86_64-ninja'
    'mingw-w64-ucrt-x86_64-pkgconf'
    'mingw-w64-ucrt-x86_64-python'
    'mingw-w64-ucrt-x86_64-python-certifi'
    'mingw-w64-ucrt-x86_64-shaderc'
    'mingw-w64-ucrt-x86_64-spirv-cross'
)

# pacman gives up on a stalled mirror; a retry usually picks another one.
function Invoke-Msys2([string] $command) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & $bash -lc $command
        if ($LASTEXITCODE -eq 0) { return }
        Write-Warning "'$command' failed (attempt $attempt of 3)"
    }
    throw "'$command' failed 3 times"
}

function Stop-Msys2Processes {
    # Through cmd: under ErrorActionPreference Stop, Windows PowerShell turns a redirected
    # native stderr line into an exception.
    cmd /c 'taskkill /F /FI "MODULES eq msys-2.0.dll" >nul 2>&1'
}

if (-not (Test-Path $bash)) {
    $archive = Join-Path $env:TEMP 'msys2-base-x86_64-latest.sfx.exe'
    Write-Host "Downloading MSYS2 to $archive"
    curl.exe -fL --retry 5 -o $archive https://repo.msys2.org/distrib/msys2-base-x86_64-latest.sfx.exe
    if ($LASTEXITCODE -ne 0) { throw 'Downloading MSYS2 failed' }
    Write-Host "Extracting to $msys2"
    $extract = Start-Process -FilePath $archive -ArgumentList '-y', '-oC:\' -Wait -NoNewWindow -PassThru
    if ($extract.ExitCode -ne 0) { throw "Extracting MSYS2 failed ($($extract.ExitCode))" }
    Remove-Item $archive
    # The first login shell creates the home directory and the pacman keyring.
    & $bash -lc ' '
    if ($LASTEXITCODE -ne 0) { throw 'Initializing MSYS2 failed' }
}

# MSYS2's unattended update: the first pass may update only the core runtime, which must not
# be in use while it is replaced; the second updates everything else.
$pacman = 'pacman --noconfirm --disable-download-timeout'
Stop-Msys2Processes
Invoke-Msys2 "$pacman -Syuu"
Stop-Msys2Processes
Invoke-Msys2 "$pacman -Syuu"
Invoke-Msys2 "$pacman -S --needed $($packages -join ' ')"

& $bash -lc "pacman -Q $($packages -join ' ')"
if ($LASTEXITCODE -ne 0) { throw 'Some packages are missing' }
Write-Host "MSYS2 is ready at $msys2"
