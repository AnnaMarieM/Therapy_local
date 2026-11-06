# The Windows entry point to the pinned builder. Same container and same docker/inside.sh as scripts/build.sh.
# Usage: .\scripts\build.ps1 [full|fast]
#   full (default)  clean, lint, unit tests, debug APK and test APK
#   fast            clean and the two APKs only
# Output: dist\Therapy-main.apk, dist\Therapy-main-androidTest.apk, dist\Therapy-main.apk.sha256
# Needs Docker Desktop (Linux containers). On Windows-on-Arm the x86_64 aapt2 runs under software emulation inside the container.
param([ValidateSet('full', 'fast')][string]$Mode = 'full')
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$tag = (Get-FileHash -Algorithm SHA256 (Join-Path $root 'docker\Dockerfile')).Hash.ToLower().Substring(0, 12)
$image = "therapy-builder:$tag"

# A developer's local.properties points at their own SDK; hide it so the image SDK is always used.
$mask = @()
if (Test-Path (Join-Path $root 'local.properties')) {
    $empty = [System.IO.Path]::GetTempFileName()
    $mask = @('-v', "${empty}:/workspace/local.properties:ro")
}

docker build -q -t $image (Join-Path $root 'docker') | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'docker build failed' }

$arch = (docker run --rm --entrypoint uname $image -m).Trim()
if ($arch -eq 'x86_64') {
    Write-Host "[build] container arch ${arch}: aapt2 runs natively"
} else {
    Write-Host "[build] container arch ${arch}: aapt2 is x86_64-only, running it under qemu-user software emulation (no KVM needed, slower resource steps)"
}

# A named volume keeps the Gradle cache in the Linux VM; bind mounts from NTFS are slow.
$epoch = (git -C $root log -1 --format=%ct).Trim()
docker run --rm `
    -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle-home -e ANDROID_USER_HOME=/tmp/home/.android `
    -e SOURCE_DATE_EPOCH=$epoch `
    -v "${root}:/workspace" `
    -v therapy-builder-gradle-home:/gradle-home `
    @mask `
    -w /workspace `
    $image `
    bash docker/inside.sh $Mode
if ($LASTEXITCODE -ne 0) { throw "build failed (exit $LASTEXITCODE)" }
