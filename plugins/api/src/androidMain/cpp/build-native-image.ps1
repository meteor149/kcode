param(
    [Parameter(Mandatory = $true)]
    [string]$NdkRoot
)

$ErrorActionPreference = 'Stop'
$compiler = Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android35-clang.cmd'
$source = Join-Path $PSScriptRoot 'native_image.c'
$outputDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../jniLibs/arm64-v8a'))
$output = Join-Path $outputDirectory 'libkcode_native_image.so'
if (-not (Test-Path -LiteralPath $compiler)) { throw "Android NDK compiler is absent: $compiler" }
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
& $compiler -nostdlib -static -fno-pie -fno-stack-protector -fno-builtin -O2 `
    '-Wl,-Ttext=0x3000000000' '-Wl,-e,_start' '-Wl,--build-id=none' `
    '-Wl,-z,max-page-size=16384' $source -o $output
if ($LASTEXITCODE -ne 0) { throw "Native-image bootstrap compilation failed: $LASTEXITCODE" }
Get-FileHash -LiteralPath $output -Algorithm SHA256
