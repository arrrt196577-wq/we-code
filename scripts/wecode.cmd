@echo off
setlocal

set "WECODE_JAR=%~dp0..\wecode.jar"

if not exist "%WECODE_JAR%" (
    echo WeCode startup failed: Jar not found at "%WECODE_JAR%".
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo WeCode startup failed: Java was not found on PATH. Install Java 21 or later.
    exit /b 1
)

java -jar "%WECODE_JAR%" %*
exit /b %errorlevel%
