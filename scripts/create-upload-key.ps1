[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw 'The protected upload-key setup script requires Windows DPAPI.'
}

$signingDirectory = Join-Path $env:USERPROFILE '.android\signing\SuperScanner'
$keyPath = Join-Path $signingDirectory 'upload-key.p12'
$credentialPath = Join-Path $signingDirectory 'upload-password.dpapi'
$alias = 'superscanner-upload'
$keytoolPath = (Get-Command keytool.exe -ErrorAction Stop).Source

if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $credentialPath)) {
    throw 'Upload-key material already exists at the dedicated SuperScanner path. Nothing was changed.'
}

[void][System.IO.Directory]::CreateDirectory($signingDirectory)
$currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$directoryAcl = Get-Acl -LiteralPath $signingDirectory
$directoryAcl.SetAccessRuleProtection($true, $false)
$directoryAcl.Access | ForEach-Object { [void]$directoryAcl.RemoveAccessRule($_) }
$directoryRule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $currentSid,
    [System.Security.AccessControl.FileSystemRights]::FullControl,
    [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
    [System.Security.AccessControl.PropagationFlags]::None,
    [System.Security.AccessControl.AccessControlType]::Allow
)
$directoryAcl.AddAccessRule($directoryRule)
Set-Acl -LiteralPath $signingDirectory -AclObject $directoryAcl

$randomBytes = [byte[]]::new(32)
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$password = $null
$securePassword = $null
try {
    $rng.GetBytes($randomBytes)
    $password = [BitConverter]::ToString($randomBytes).Replace('-', '')
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    $securePassword = ConvertTo-SecureString -String $password -AsPlainText -Force
    $encryptedPassword = ConvertFrom-SecureString -SecureString $securePassword
    [System.IO.File]::WriteAllText($credentialPath, $encryptedPassword, [System.Text.UTF8Encoding]::new($false))
    $env:SUPERSCANNER_UPLOAD_PASSWORD = $password

    & $keytoolPath -genkeypair -keystore $keyPath -storetype PKCS12 -alias $alias `
        -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 `
        -dname 'CN=SuperScanner Internal Upload, O=SuperScanner' `
        -storepass:env SUPERSCANNER_UPLOAD_PASSWORD -keypass:env SUPERSCANNER_UPLOAD_PASSWORD *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keyPath)) {
        Remove-Item -LiteralPath $credentialPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $keyPath -Force -ErrorAction SilentlyContinue
        throw 'keytool failed to create the upload key. No key material was retained.'
    }
    $fileAcl = Get-Acl -LiteralPath $keyPath
    $fileAcl.SetAccessRuleProtection($true, $false)
    $fileAcl.Access | ForEach-Object { [void]$fileAcl.RemoveAccessRule($_) }
    $fileAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
        $currentSid,
        [System.Security.AccessControl.FileSystemRights]::FullControl,
        [System.Security.AccessControl.AccessControlType]::Allow
    ))
    Set-Acl -LiteralPath $keyPath -AclObject $fileAcl
    $credentialAcl = Get-Acl -LiteralPath $credentialPath
    $credentialAcl.SetAccessRuleProtection($true, $false)
    $credentialAcl.Access | ForEach-Object { [void]$credentialAcl.RemoveAccessRule($_) }
    $credentialAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
        $currentSid,
        [System.Security.AccessControl.FileSystemRights]::FullControl,
        [System.Security.AccessControl.AccessControlType]::Allow
    ))
    Set-Acl -LiteralPath $credentialPath -AclObject $credentialAcl
} catch {
    Remove-Item -LiteralPath $credentialPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $keyPath -Force -ErrorAction SilentlyContinue
    throw 'Upload key setup failed; no key material was retained.'
} finally {
    Remove-Item Env:\SUPERSCANNER_UPLOAD_PASSWORD -ErrorAction SilentlyContinue
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    $rng.Dispose()
    $password = $null
    if ($securePassword) { $securePassword.Dispose() }
    [GC]::Collect()
}

Write-Output 'Created the dedicated SuperScanner Play upload key and a current-Windows-user DPAPI credential file. Use the separate backup script to create a portable protected copy.'
Write-Output "Key: $keyPath"
Write-Output "Credential: $credentialPath"
Write-Output 'Back up the PKCS#12 file and its password separately in a trusted password manager; the DPAPI file is not portable.'
