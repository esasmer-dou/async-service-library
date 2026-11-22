function Initialize-AslJavaTooling {
    param(
        [int]$RequiredMajorVersion = 21
    )

    $runningOnWindows = [System.IO.Path]::DirectorySeparatorChar -eq '\'
    $javaExecutableName = $runningOnWindows ? "java.exe" : "java"
    $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
    $javaHomeSource = "process"

    if ([string]::IsNullOrWhiteSpace($javaHome) -and $runningOnWindows) {
        $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
        $javaHomeSource = "user"
    }
    if ([string]::IsNullOrWhiteSpace($javaHome) -and $runningOnWindows) {
        $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
        $javaHomeSource = "machine"
    }

    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        $javaHome = [System.IO.Path]::GetFullPath(
            [Environment]::ExpandEnvironmentVariables($javaHome.Trim().Trim('"'))
        )
        $javaCommand = Join-Path $javaHome "bin\$javaExecutableName"
        if (-not (Test-Path -LiteralPath $javaCommand -PathType Leaf)) {
            throw "JAVA_HOME from $javaHomeSource scope does not contain ${javaExecutableName}: $javaHome"
        }

        $releaseFile = Join-Path $javaHome "release"
        if ($RequiredMajorVersion -gt 0 -and (Test-Path -LiteralPath $releaseFile -PathType Leaf)) {
            $versionLine = Get-Content -LiteralPath $releaseFile |
                Where-Object { $_ -match '^JAVA_VERSION=' } |
                Select-Object -First 1
            if ($versionLine -match '^JAVA_VERSION="?(?<major>\d+)') {
                $actualMajorVersion = [int]$Matches.major
                if ($actualMajorVersion -ne $RequiredMajorVersion) {
                    throw "ASL requires Java $RequiredMajorVersion, but JAVA_HOME points to Java ${actualMajorVersion}: $javaHome"
                }
            }
        }

        $env:JAVA_HOME = $javaHome
        $javaBin = Join-Path $javaHome "bin"
        $pathEntries = @($env:Path -split [System.IO.Path]::PathSeparator)
        if ($pathEntries.Count -eq 0 -or
            -not [string]::Equals(
                $pathEntries[0].TrimEnd([char[]]@('\', '/')),
                $javaBin.TrimEnd([char[]]@('\', '/')),
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
            $env:Path = "$javaBin$([System.IO.Path]::PathSeparator)$env:Path"
        }
    } else {
        $javaResolution = Get-Command $javaExecutableName -ErrorAction SilentlyContinue
        if ($null -eq $javaResolution) {
            throw "Java could not be resolved. Set JAVA_HOME to a Java $RequiredMajorVersion JDK."
        }
        $javaCommand = $javaResolution.Source
        $javaHomeSource = "PATH"
    }

    $mavenNames = $runningOnWindows ? @("mvn.cmd", "mvn") : @("mvn")
    $mavenCommand = $null
    foreach ($mavenName in $mavenNames) {
        $mavenResolution = Get-Command $mavenName -ErrorAction SilentlyContinue
        if ($null -ne $mavenResolution) {
            $mavenCommand = $mavenResolution.Source
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($mavenCommand)) {
        throw "Maven could not be resolved from PATH."
    }

    return [pscustomobject]@{
        JavaCommand = $javaCommand
        JavaHome = $javaHome
        JavaHomeSource = $javaHomeSource
        MavenCommand = $mavenCommand
    }
}
