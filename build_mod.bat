@echo off
chcp 65001 > nul
setlocal enabledelayedexpansion

cd /d "%~dp0"
set "MOD_ROOT=%cd%"
set "JAR_SRC=%MOD_ROOT%\build\libs\gt_shanhai.jar"
set "JAR_DST=d:\minecraft\gtl\.minecraft\versions\八周目\.minecraft\versions\GTL九周目\.minecraft\versions\GTL九周目\mods\gt_shanhai.jar"
set "GRADLE_CMD=%MOD_ROOT%\gradle-install\gradle-8.8\bin\gradle"

echo ========================================
echo        gt_shanhai Build
echo ========================================
echo.

echo Step 1: Cleaning old build...
if exist "%MOD_ROOT%\build" (
    rmdir /s /q "%MOD_ROOT%\build" >nul 2>&1
)
echo Done.
echo.

echo Step 2: Building with gradle...
call "%GRADLE_CMD%" -b "%MOD_ROOT%\build.gradle" build --no-daemon -x test
if !errorlevel! neq 0 (
    echo BUILD FAILED!
    pause
    exit /b 1
)
echo.

echo Step 3: Deploying jar...
if not exist "%JAR_SRC%" (
    echo ERROR: JAR not found at %JAR_SRC%
    pause
    exit /b 1
)

if exist "%JAR_DST%" (
    del /f /q "%JAR_DST%" >nul 2>&1
)

copy /y "%JAR_SRC%" "%JAR_DST%" >nul
if !errorlevel! equ 0 (
    echo.
    echo ========================================
    echo        BUILD SUCCESSFUL
    echo ========================================
    echo JAR deployed to mods folder.
) else (
    echo ERROR: Failed to deploy jar
    pause
    exit /b 1
)

pause
