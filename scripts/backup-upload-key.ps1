[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'This portable backup script requires Windows DPAPI to read the existing password.'
}

# Resolve Java before prompting or decrypting any password. Prefer the configured JDK.
$keytoolPath = $null
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaKeytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path -LiteralPath $javaKeytool -PathType Leaf) {
        $keytoolPath = (Resolve-Path -LiteralPath $javaKeytool).ProviderPath
    }
}
if (-not $keytoolPath) {
    $keytoolCommand = Get-Command keytool.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($keytoolCommand) { $keytoolPath = $keytoolCommand.Source }
}
if (-not $keytoolPath) {
    throw 'Could not find keytool.exe. Set JAVA_HOME to a JDK or add keytool.exe to PATH.'
}

$signingDirectory = Join-Path $env:USERPROFILE '.android\signing\SuperScanner'
$sourceKeyPath = Join-Path $signingDirectory 'upload-key.p12'
$sourcePasswordPath = Join-Path $signingDirectory 'upload-password.dpapi'
$alias = 'superscanner-upload'
$pathSeparators = [char[]]@([char]'\', [char]'/')
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd($pathSeparators)

function Test-PathWithinRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )

    $normalizedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd($pathSeparators)
    $normalizedRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd($pathSeparators)
    $rootWithSeparator = $normalizedRoot + [System.IO.Path]::DirectorySeparatorChar
    return $normalizedPath.Equals($normalizedRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($rootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-NoReparsePointInPathChain {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $currentPath = [System.IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($currentPath)) {
        $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction Stop
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Purpose must not pass through junctions or symbolic links. Choose a plain folder path."
        }

        $parentPath = [System.IO.Directory]::GetParent($currentPath)
        if (-not $parentPath) { break }
        $currentPath = $parentPath.FullName
    }
}

function Get-BackupPasswordValidationError {
    param(
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Confirmation
    )

    if ($Password.Length -lt 20) { return 'Backup password is too short; use at least 20 characters.' }
    if ($Password.Length -gt 256) { return 'Backup password is too long; use no more than 256 characters.' }
    if ($Password -notmatch '\A[\x20-\x7E]{20,256}\z') {
        return 'Backup password must contain printable ASCII characters only (codes 32 through 126).'
    }
    if ($Confirmation.Length -lt 20) { return 'Confirmation is too short; use at least 20 characters.' }
    if ($Confirmation.Length -gt 256) { return 'Confirmation is too long; use no more than 256 characters.' }
    if ($Confirmation -notmatch '\A[\x20-\x7E]{20,256}\z') {
        return 'Confirmation must contain printable ASCII characters only (codes 32 through 126).'
    }
    if (-not [string]::Equals($Password, $Confirmation, [StringComparison]::Ordinal)) {
        return 'Backup password entries do not match.'
    }
    return $null
}

function Invoke-Keytool {
    param(
        [Parameter(Mandatory = $true)][string]$Keytool,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$CaptureOutput
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $nativeOutput = @()
    $exitCode = 1
    try {
        # Windows PowerShell 5 can turn ordinary native stderr into a terminating error
        # under Stop. Keep this narrowly scoped and rely on keytool's exit code instead.
        $ErrorActionPreference = 'Continue'
        if ($CaptureOutput) {
            $nativeOutput = @(& $Keytool @Arguments 2>$null)
        } else {
            & $Keytool @Arguments *> $null
        }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [PSCustomObject]@{ ExitCode = $exitCode; Output = $nativeOutput }
}

Assert-NoReparsePointInPathChain -Path $workspaceRoot -Purpose 'The SuperScanner project path'

function Set-CurrentUserOnlyAcl {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][bool]$IsDirectory
    )

    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    @($acl.Access) | ForEach-Object { [void]$acl.RemoveAccessRule($_) }

    if ($IsDirectory) {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
            [System.Security.AccessControl.PropagationFlags]::None,
            [System.Security.AccessControl.AccessControlType]::Allow
        )
    } else {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.AccessControlType]::Allow
        )
    }
    [void]$acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Get-CertificateFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$Keytool,
        [Parameter(Mandatory = $true)][string]$Keystore,
        [Parameter(Mandatory = $true)][string]$PasswordEnvironmentName,
        [Parameter(Mandatory = $true)][string]$KeyAlias
    )

    $exportArgs = @(
        '-exportcert', '-rfc',
        '-keystore', $Keystore,
        '-storetype', 'PKCS12',
        '-storepass:env', $PasswordEnvironmentName,
        '-alias', $KeyAlias
    )
    $keytoolResult = Invoke-Keytool -Keytool $Keytool -Arguments $exportArgs -CaptureOutput
    if ($keytoolResult.ExitCode -ne 0) { throw 'Could not verify the upload certificate.' }

    $pemText = @($keytoolResult.Output) -join "`n"
    $match = [System.Text.RegularExpressions.Regex]::Match(
        $pemText,
        '-----BEGIN CERTIFICATE-----\s*(?<body>[A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----'
    )
    if (-not $match.Success) { throw 'Could not read the upload certificate for verification.' }

    $encodedCertificate = $match.Groups['body'].Value -replace '\s', ''
    $certificateBytes = [Convert]::FromBase64String($encodedCertificate)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($sha256.ComputeHash($certificateBytes))
    } finally {
        $sha256.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $sourceKeyPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $sourcePasswordPath -PathType Leaf)) {
    throw 'The existing protected SuperScanner upload key or DPAPI password file is missing.'
}

$destinationInput = $Destination.Trim()
if ($destinationInput -notmatch '^(?:[A-Za-z]:\\|\\\\)') {
    throw 'Destination must be an absolute Windows path outside the project, such as D:\Backups\SuperScanner-upload.p12.'
}

try {
    $destinationFullPath = [System.IO.Path]::GetFullPath($destinationInput)
} catch {
    throw 'Destination is not a valid absolute file path.'
}
$destinationFileName = [System.IO.Path]::GetFileName($destinationFullPath)
$destinationParent = [System.IO.Path]::GetDirectoryName($destinationFullPath)
if ([string]::IsNullOrWhiteSpace($destinationFileName) -or [string]::IsNullOrWhiteSpace($destinationParent)) {
    throw 'Destination must name a new PKCS#12 file.'
}
if ([System.IO.Path]::GetExtension($destinationFileName) -notin @('.p12', '.pfx')) {
    throw 'Destination must use a .p12 or .pfx extension.'
}
if (Test-PathWithinRoot -Path $destinationFullPath -Root $workspaceRoot) {
    throw 'Choose a destination outside the SuperScanner project and Git workspace.'
}
if (Test-Path -LiteralPath $destinationFullPath) {
    throw 'Destination already exists. Choose a new path; existing files are never overwritten.'
}

# Inspect the existing ancestor first so a junction cannot redirect directory creation into the project.
$existingAncestor = $destinationParent
while (-not [System.IO.Directory]::Exists($existingAncestor)) {
    $parentOfAncestor = [System.IO.Directory]::GetParent($existingAncestor)
    if (-not $parentOfAncestor) {
        throw 'Destination parent is not a usable directory path.'
    }
    $existingAncestor = $parentOfAncestor.FullName
}
Assert-NoReparsePointInPathChain -Path $existingAncestor -Purpose 'The backup destination path'

# Create only the explicitly named parent path, then recheck its full ancestor chain.
[void][System.IO.Directory]::CreateDirectory($destinationParent)
$destinationParent = [System.IO.Path]::GetFullPath($destinationParent)
Assert-NoReparsePointInPathChain -Path $destinationParent -Purpose 'The backup destination path'
$destinationFullPath = [System.IO.Path]::GetFullPath((Join-Path $destinationParent $destinationFileName))
if (Test-PathWithinRoot -Path $destinationFullPath -Root $workspaceRoot) {
    throw 'Resolved destination points into the SuperScanner project or Git workspace.'
}
if ((Test-Path -LiteralPath $destinationFullPath) -or
    [System.IO.Directory]::Exists($destinationFullPath) -or
    [System.IO.File]::Exists($destinationFullPath)) {
    throw 'Destination already exists. Choose a new path; existing files are never overwritten.'
}

$temporaryDirectory = $null
$temporaryKeyPath = $null
$published = $false
$backupPassword = $null
$confirmation = $null
$sourcePassword = $null
$backupPasswordText = $null
$confirmationText = $null
$sourcePasswordText = $null
$backupPasswordPointer = [IntPtr]::Zero
$confirmationPointer = [IntPtr]::Zero
$sourcePasswordPointer = [IntPtr]::Zero
$sourceEnvironmentName = $null
$backupEnvironmentName = $null
$certificateFingerprint = $null

try {
    Write-Output 'Choose a strong backup password and save it separately in your password manager. This copy uses your password, not the Windows-only DPAPI secret.'
    $backupPassword = Read-Host 'New backup password (hidden)' -AsSecureString
    $confirmation = Read-Host 'Enter the backup password again (hidden)' -AsSecureString
    $backupPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($backupPassword)
    $confirmationPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirmation)
    $backupPasswordText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($backupPasswordPointer)
    $confirmationText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($confirmationPointer)
    $passwordValidationError = Get-BackupPasswordValidationError `
        -Password $backupPasswordText -Confirmation $confirmationText
    if ($passwordValidationError) { throw $passwordValidationError }

    try {
        $encryptedSourcePassword = Get-Content -LiteralPath $sourcePasswordPath -Raw
        $sourcePassword = ConvertTo-SecureString -String $encryptedSourcePassword.Trim()
        $encryptedSourcePassword = $null
    } catch {
        throw 'The existing Windows DPAPI password could not be read for this user.'
    }
    $sourcePasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sourcePassword)
    $sourcePasswordText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($sourcePasswordPointer)

    $guid = [Guid]::NewGuid().ToString('N')
    $sourceEnvironmentName = "SUPERSCANNER_BACKUP_SOURCE_$guid"
    $backupEnvironmentName = "SUPERSCANNER_BACKUP_DEST_$guid"
    if ([Environment]::GetEnvironmentVariable($sourceEnvironmentName, 'Process') -or
        [Environment]::GetEnvironmentVariable($backupEnvironmentName, 'Process')) {
        throw 'Could not reserve temporary password environment names.'
    }
    [Environment]::SetEnvironmentVariable($sourceEnvironmentName, $sourcePasswordText, 'Process')
    [Environment]::SetEnvironmentVariable($backupEnvironmentName, $backupPasswordText, 'Process')

    $certificateFingerprint = Get-CertificateFingerprint -Keytool $keytoolPath -Keystore $sourceKeyPath `
        -PasswordEnvironmentName $sourceEnvironmentName -KeyAlias $alias

    $temporaryDirectory = Join-Path $destinationParent ".SuperScanner-backup-$guid"
    if (Test-Path -LiteralPath $temporaryDirectory) {
        throw 'Could not reserve a private temporary directory beside the destination.'
    }
    [void](New-Item -ItemType Directory -Path $temporaryDirectory -ErrorAction Stop)
    Set-CurrentUserOnlyAcl -Path $temporaryDirectory -IsDirectory $true
    $temporaryKeyPath = Join-Path $temporaryDirectory $destinationFileName

    $importArgs = @(
        '-importkeystore',
        '-srckeystore', $sourceKeyPath,
        '-srcstoretype', 'PKCS12',
        '-srcstorepass:env', $sourceEnvironmentName,
        '-srcalias', $alias,
        '-srckeypass:env', $sourceEnvironmentName,
        '-destkeystore', $temporaryKeyPath,
        '-deststoretype', 'PKCS12',
        '-deststorepass:env', $backupEnvironmentName,
        '-destalias', $alias,
        '-destkeypass:env', $backupEnvironmentName,
        '-noprompt'
    )
    $importResult = Invoke-Keytool -Keytool $keytoolPath -Arguments $importArgs
    if ($importResult.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $temporaryKeyPath -PathType Leaf)) {
        throw 'keytool could not create the portable backup.'
    }
    Set-CurrentUserOnlyAcl -Path $temporaryKeyPath -IsDirectory $false

    $backupFingerprint = Get-CertificateFingerprint -Keytool $keytoolPath -Keystore $temporaryKeyPath `
        -PasswordEnvironmentName $backupEnvironmentName -KeyAlias $alias
    if (-not [string]::Equals($certificateFingerprint, $backupFingerprint, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The backup certificate does not match the original upload key.'
    }

    # File.Move without overwrite is atomic on this same-volume staging directory.
    [System.IO.File]::Move($temporaryKeyPath, $destinationFullPath)
    $published = $true
    try { [System.IO.Directory]::Delete($temporaryDirectory, $false) } catch { }
    $temporaryDirectory = $null
    $temporaryKeyPath = $null

    Write-Output "Portable upload-key backup created: $destinationFullPath"
    Write-Output "Certificate SHA-256: $certificateFingerprint"
    Write-Output 'Store the backup password separately. The original key and DPAPI file were not changed.'
} finally {
    if ($sourceEnvironmentName) {
        [Environment]::SetEnvironmentVariable($sourceEnvironmentName, $null, 'Process')
    }
    if ($backupEnvironmentName) {
        [Environment]::SetEnvironmentVariable($backupEnvironmentName, $null, 'Process')
    }

    if (-not $published) {
        if ($temporaryKeyPath -and [System.IO.File]::Exists($temporaryKeyPath)) {
            Remove-Item -LiteralPath $temporaryKeyPath -Force -ErrorAction SilentlyContinue
        }
        if ($temporaryDirectory -and [System.IO.Directory]::Exists($temporaryDirectory)) {
            try { [System.IO.Directory]::Delete($temporaryDirectory, $false) } catch { }
        }
    }

    if ($backupPasswordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($backupPasswordPointer)
    }
    if ($confirmationPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($confirmationPointer)
    }
    if ($sourcePasswordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($sourcePasswordPointer)
    }
    if ($backupPassword) { $backupPassword.Dispose() }
    if ($confirmation) { $confirmation.Dispose() }
    if ($sourcePassword) { $sourcePassword.Dispose() }
    $backupPasswordText = $null
    $confirmationText = $null
    $sourcePasswordText = $null
    [GC]::Collect()
}
