param(
    [string]$KeystorePath = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\keystores\ddubi-release.jks'),
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$ApiBaseUrl = 'https://j15e202.p.ssafy.io/api/v1'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $KeystorePath)) {
    throw "Release keystore not found: $KeystorePath"
}
if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw "Client environment file not found: $EnvironmentFile"
}

$securePassword = Read-Host 'DDUBI release keystore password' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)

try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:DDUBI_KEYSTORE_PATH = $KeystorePath
    $env:DDUBI_KEYSTORE_PASSWORD = $plainPassword
    $env:DDUBI_KEY_PASSWORD = $plainPassword
    $env:DDUBI_KEY_ALIAS = 'ddubi'

    Push-Location (Join-Path $PSScriptRoot '..')
    try {
        & flutter build apk --release `
            "--dart-define-from-file=$EnvironmentFile" `
            "--dart-define=API_BASE_URL=$ApiBaseUrl"
        if ($LASTEXITCODE -ne 0) {
            throw "Flutter release build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:DDUBI_KEYSTORE_PATH = $null
    $env:DDUBI_KEYSTORE_PASSWORD = $null
    $env:DDUBI_KEY_PASSWORD = $null
    $env:DDUBI_KEY_ALIAS = $null
    $plainPassword = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
