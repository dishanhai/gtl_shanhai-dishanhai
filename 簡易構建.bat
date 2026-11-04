@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

rem 增量構建。不刪 build，編譯可沿用上次結果。
rem build 會跑 reobfJar。遊戲要用混淆產物，不是中途的 Mojmap class。
set "JAVA_HOME=%ProgramFiles%\Java\jdk-21.0.11"
set "GRADLE_OPTS=-Xmx4G -Xms1G -Dfile.encoding=UTF-8"
set "GRADLE_CMD=%~dp0gradle-install\gradle-8.8\bin\gradle.bat"
set "REOBF_JAR=%~dp0build\reobfJar\output.jar"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo 找不到 JDK 21：%JAVA_HOME%
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
