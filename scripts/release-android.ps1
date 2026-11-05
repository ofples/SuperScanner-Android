[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw 'The signed local release script requires Windows DPAPI.'
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$signingDirectory = Join-Path $env:USERPROFILE '.android\signing\SuperScanner'
$keyPath = Join-Path $signingDirectory 'upload-key.p12'
$credentialPath = Join-Path $signingDirectory 'upload-password.dpapi'
$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
$javaPath = (Get-Command java.exe -ErrorAction Stop).Source
$keytoolPath = (Get-Command keytool.exe -ErrorAction Stop).Source
$jarsignerPath = (Get-Command jarsigner.exe -ErrorAction Stop).Source
$bundletoolVersion = '1.18.3'
$bundletoolPath = Join-Path $env:USERPROFILE ".android\tools\bundletool-all-$bundletoolVersion.jar"
$bundletoolHash = 'A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29'
$bundletoolUrl = "https://github.com/google/bundletool/releases/download/$bundletoolVersion/bundletool-all-$bundletoolVersion.jar"

if (-not $sdkRoot) { throw 'ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK.' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat') -PathType Leaf)) { throw 'Gradle wrapper is missing.' }
if (-not (Test-Path -LiteralPath $keyPath -PathType Leaf) -or -not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw 'Signed release prerequisites are missing. Run scripts\create-upload-key.ps1 first; no debug-signing fallback is available.'
}

$buildTools = Join-Path $sdkRoot 'build-tools\36.0.0'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$aapt = Join-Path $buildTools 'aapt.exe'
$apkanalyzer = Join-Path $sdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'
foreach ($tool in @($apksigner, $zipalign, $aapt, $apkanalyzer)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required Android SDK release tool is missing: $tool" }
}

$ndkRoot = Join-Path $sdkRoot 'ndk'
$ndk = Get-ChildItem -LiteralPath $ndkRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndk) { throw 'An installed Android NDK is required to verify 16 KB ELF segment alignment.' }
$readelf = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $readelf -PathType Leaf)) { throw 'NDK llvm-readelf.exe is missing; native ELF alignment cannot be verified.' }

[void][System.IO.Directory]::CreateDirectory((Split-Path -Parent $bundletoolPath))
if (Test-Path -LiteralPath $bundletoolPath -PathType Leaf) {
    $existingBundletoolHash = (Get-FileHash -LiteralPath $bundletoolPath -Algorithm SHA256).Hash
    if ($existingBundletoolHash -ne $bundletoolHash) { throw 'Cached bundletool does not match the pinned official release digest.' }
} else {
    $downloadPath = "$bundletoolPath.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        Invoke-WebRequest -Uri $bundletoolUrl -OutFile $downloadPath -UseBasicParsing
        $downloadHash = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash
        if ($downloadHash -ne $bundletoolHash) { throw 'Downloaded bundletool did not match the pinned official release digest.' }
        Move-Item -LiteralPath $downloadPath -Destination $bundletoolPath
    } finally {
        Remove-Item -LiteralPath $downloadPath -Force -ErrorAction SilentlyContinue
    }
}
$bundletoolVersionOutput = & $javaPath -jar $bundletoolPath version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $bundletoolVersionOutput -notmatch [regex]::Escape($bundletoolVersion)) {
    throw 'Pinned bundletool failed its preflight version check.'
}

$encryptedPassword = $null
$securePassword = $null
$passwordPointer = [IntPtr]::Zero
$password = $null
$tempDirectoryName = "superscanner-release-$([Guid]::NewGuid().ToString('N'))"
$tempDirectory = Join-Path ([IO.Path]::GetFullPath($env:TEMP)) $tempDirectoryName
$stagingBundle = $null
$stagingApk = $null
try {
    $encryptedPassword = [System.IO.File]::ReadAllText($credentialPath)
    $securePassword = ConvertTo-SecureString -String $encryptedPassword
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

    $env:SUPERSCANNER_SIGN_RELEASE = '1'
    $env:SUPERSCANNER_UPLOAD_KEYSTORE = $keyPath
    $env:SUPERSCANNER_UPLOAD_STORE_PASSWORD = $password
    $env:SUPERSCANNER_UPLOAD_KEY_ALIAS = 'superscanner-upload'
    $env:SUPERSCANNER_UPLOAD_KEY_PASSWORD = $password

    [void][System.IO.Directory]::CreateDirectory($tempDirectory)
    $uploadCertificate = Join-Path $tempDirectory 'upload-cert.der'
    & $keytoolPath -exportcert -keystore $keyPath -storetype PKCS12 -alias superscanner-upload `
        -storepass:env SUPERSCANNER_UPLOAD_STORE_PASSWORD -file $uploadCertificate *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $uploadCertificate -PathType Leaf)) {
        throw 'Upload keystore could not be opened using the protected credential.'
    }
    $expectedCertificateHash = (Get-FileHash -LiteralPath $uploadCertificate -Algorithm SHA256).Hash

    Push-Location $projectRoot
    try {
        & .\gradlew.bat --no-daemon --no-configuration-cache `
            :app:lintRelease :app:testReleaseUnitTest :app:bundleRelease :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Gradle release checks or signed release assembly failed.' }
    } finally {
        Pop-Location
    }

    $sourceBundle = Join-Path $projectRoot 'app\build\outputs\bundle\release\app-release.aab'
    $sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $sourceBundle -PathType Leaf) -or -not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
        throw 'Expected release outputs were not produced.'
    }

    $bundleSignerInfo = & $keytoolPath '-J-Duser.language=en' '-J-Duser.country=US' -printcert -jarfile $sourceBundle 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'AAB signer metadata could not be read.' }
    $bundleSignerMatch = [regex]::Match($bundleSignerInfo, '(?m)^\s*SHA256:\s*([0-9A-Fa-f:]+)\s*$')
    if (-not $bundleSignerMatch.Success -or ($bundleSignerMatch.Groups[1].Value -replace ':', '').ToUpperInvariant() -ne $expectedCertificateHash) {
        throw 'AAB signer certificate does not match the dedicated upload key.'
    }

    $jarsignerOutput = & $jarsignerPath '-J-Duser.language=en' '-J-Duser.country=US' -verify -verbose -certs $sourceBundle 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $jarsignerOutput -notmatch '(?im)^\s*jar verified\.\s*$' -or $jarsignerOutput -match '(?i)unsigned entries') {
        throw 'AAB is unsigned, partially unsigned, or failed JAR signature verification.'
    }

    $apkSignerOutput = & $apksigner verify --verbose --print-certs $sourceApk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $apkSignerMatch = [regex]::Match($apkSignerOutput, '(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f]+)\s*$')
    if (-not $apkSignerMatch.Success -or $apkSignerMatch.Groups[1].Value.ToUpperInvariant() -ne $expectedCertificateHash) {
        throw 'APK signer certificate does not match the dedicated upload key.'
    }

    & $javaPath -jar $bundletoolPath validate --bundle=$sourceBundle *> $null
    if ($LASTEXITCODE -ne 0) { throw 'bundletool validation of the AAB failed.' }
    $manifestText = & $javaPath -jar $bundletoolPath dump manifest --bundle=$sourceBundle --module=base 2>$null | Out-String
    if ($LASTEXITCODE -ne 0 -or $manifestText -notmatch 'package="com\.yukti\.superscanner"' -or
        $manifestText -notmatch 'android:versionCode="13"' -or $manifestText -notmatch 'android:versionName="0\.5\.0"' -or
        $manifestText -notmatch 'android:minSdkVersion="26"' -or $manifestText -notmatch 'android:targetSdkVersion="36"') {
        throw 'AAB manifest package, version, min SDK, or target SDK did not match the expected release.'
    }
    if ($manifestText -match 'android:debuggable="true"') { throw 'AAB manifest is marked debuggable.' }
    if ($manifestText -match 'android\.permission\.FOREGROUND_SERVICE|AuthCallbackService') {
        throw 'The Play scanner must use browser-owned sign-in without a foreground service.'
    }

    $bundleConfig = & $javaPath -jar $bundletoolPath dump config --bundle=$sourceBundle 2>$null | Out-String
    if ($LASTEXITCODE -ne 0 -or $bundleConfig -notmatch 'PAGE_ALIGNMENT_16K') {
        throw 'AAB bundle configuration does not declare 16 KB page alignment.'
    }

    $badging = & $aapt dump badging $sourceApk 2>$null | Out-String
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "(?m)^package: name='com\.yukti\.superscanner' versionCode='13' versionName='0\.5\.0'") {
        throw 'Release APK package or version metadata did not match the expected release.'
    }
    if ($badging -notmatch "(?m)^sdkVersion:'26'\s*$" -or $badging -notmatch "(?m)^targetSdkVersion:'36'\s*$") {
        throw 'Release APK min SDK or target SDK did not match the expected release.'
    }
    $debuggable = & $apkanalyzer manifest debuggable $sourceApk 2>$null | Out-String
    if ($LASTEXITCODE -ne 0 -or $debuggable.Trim() -ne 'false') { throw 'Release APK is debuggable or could not be verified as non-debuggable.' }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($sourceApk)
    try {
        $nativeEntries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
        foreach ($entry in $nativeEntries) {
            $nativePath = Join-Path $tempDirectory ([Guid]::NewGuid().ToString('N') + '.so')
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $nativePath, $true)
            $headers = & $readelf --program-headers --wide $nativePath 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) { throw 'Could not inspect a packaged native library ELF header.' }
            $loadLines = @($headers -split "`r?`n" | Where-Object { $_ -match '^\s*LOAD\s' })
            if ($loadLines.Count -eq 0) { throw 'Packaged native library has no readable LOAD segments.' }
            foreach ($line in $loadLines) {
                $alignmentMatch = [regex]::Match($line, '(0x[0-9A-Fa-f]+)\s*$')
                if (-not $alignmentMatch.Success -or [Convert]::ToInt64($alignmentMatch.Groups[1].Value.Substring(2), 16) -lt 16384) {
                    throw 'A packaged native library has a LOAD segment below 16 KB alignment.'
                }
            }
            Remove-Item -LiteralPath $nativePath -Force
        }
    } finally {
        $archive.Dispose()
    }
    & $zipalign -c -P 16 4 $sourceApk *> $null
    if ($LASTEXITCODE -ne 0) { throw 'APK 16 KB ZIP alignment verification failed.' }

    $releaseDirectory = Join-Path $projectRoot 'artifacts\release'
    [void][System.IO.Directory]::CreateDirectory($releaseDirectory)
    $stagingBundle = Join-Path $releaseDirectory ".superscanner-0.5.0-$([Guid]::NewGuid().ToString('N')).aab.tmp"
    $stagingApk = Join-Path $releaseDirectory ".superscanner-0.5.0-$([Guid]::NewGuid().ToString('N')).apk.tmp"
    Copy-Item -LiteralPath $sourceBundle -Destination $stagingBundle
    Copy-Item -LiteralPath $sourceApk -Destination $stagingApk
    $bundle = Join-Path $releaseDirectory 'superscanner-0.5.0.aab'
    $apk = Join-Path $releaseDirectory 'superscanner-0.5.0-release.apk'
    Move-Item -LiteralPath $stagingBundle -Destination $bundle -Force
    $stagingBundle = $null
    Move-Item -LiteralPath $stagingApk -Destination $apk -Force
    $stagingApk = $null

    $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    $bundleHash = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash
    Write-Output 'Signed Android release checks passed.'
    Write-Output "AAB: $bundle ($((Get-Item -LiteralPath $bundle).Length) bytes, SHA-256 $bundleHash)"
    Write-Output "APK: $apk ($((Get-Item -LiteralPath $apk).Length) bytes, SHA-256 $apkHash)"
    Write-Output "Upload certificate SHA-256: $expectedCertificateHash"
    Write-Output "Verified: lintRelease, testReleaseUnitTest, AAB/APK signature and signer identity, package/version/SDK metadata, non-debuggable APK, 16 KB ELF and ZIP alignment, bundletool $bundletoolVersion validation."
} finally {
    Remove-Item Env:\SUPERSCANNER_SIGN_RELEASE, Env:\SUPERSCANNER_UPLOAD_KEYSTORE, Env:\SUPERSCANNER_UPLOAD_STORE_PASSWORD, Env:\SUPERSCANNER_UPLOAD_KEY_ALIAS, Env:\SUPERSCANNER_UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
    if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
    $password = $null
    if ($securePassword) { $securePassword.Dispose() }
    $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd([char[]]@([char]'\', [char]'/')) + [IO.Path]::DirectorySeparatorChar
    $resolvedTempDirectory = [IO.Path]::GetFullPath($tempDirectory)
    $expectedTempName = [IO.Path]::GetFileName($resolvedTempDirectory)
    if ($resolvedTempDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        $expectedTempName -eq $tempDirectoryName -and $expectedTempName -match '^superscanner-release-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $resolvedTempDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($stagingBundle) { Remove-Item -LiteralPath $stagingBundle -Force -ErrorAction SilentlyContinue }
    if ($stagingApk) { Remove-Item -LiteralPath $stagingApk -Force -ErrorAction SilentlyContinue }
    [GC]::Collect()
}
