@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

rem Remove old build outputs before building.
rem The build runs reobfJar; use its output for the game.
set "JAVA_HOME=%ProgramFiles%\Java\jdk-21.0.11"
set "GRADLE_OPTS=-Xmx4G -Xms1G -Dfile.encoding=UTF-8"
set "GRADLE_CMD=%~dp0gradle-install\gradle-8.8\bin\gradle.bat"
set "REOBF_JAR=%~dp0build\reobfJar\output.jar"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo 找不到 JDK 21：%JAVA_HOME%
    pause
    exit /b 1
)

if exist "%~dp0build\libs" rmdir /s /q "%~dp0build\libs"
if exist "%~dp0build\libs" (
    echo 無法清除舊的 build\libs 目錄
    pause
    exit /b 1
)

echo 構建 gt_shanhai，保留 reobfJar 混淆
call "%GRADLE_CMD%" build --no-daemon -x test
if errorlevel 1 (
    echo 構建失敗
    pause
    exit /b 1
)

if not exist "%REOBF_JAR%" (
    echo 找不到混淆產物：%REOBF_JAR%
    pause
    exit /b 1
)

echo.
echo 構建完成
echo 混淆 jar：%REOBF_JAR%
pause
exit /b 0
