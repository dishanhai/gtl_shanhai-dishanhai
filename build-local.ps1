# Author: dishanhai
# Build with the local JDK 21 without starting a Gradle daemon.
& {
    $ErrorActionPreference = 'Stop'
    $jdkHome = Join-Path ${env:ProgramFiles} 'Java\jdk-21.0.11'
    $javaExe = Join-Path $jdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) {
        throw "JDK 21 not found: $javaExe"
    }

    $env:JAVA_HOME = $jdkHome
    $env:GRADLE_OPTS = '-Xmx4G -Xms1G -Dfile.encoding=UTF-8'
    $gradle = Join-Path $PSScriptRoot 'gradle-install\gradle-8.8\bin\gradle.bat'
    Push-Location $PSScriptRoot
    try {
        & $gradle clean build --no-daemon '-Dorg.gradle.jvmargs=' -x test
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}
