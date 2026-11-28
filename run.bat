@echo off
echo Building Budget Assistant for YNAB...
call gradlew installDist

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Running Budget Assistant for YNAB...
echo.
call "%~dp0build\install\ynab-budget-assistant\bin\ynab-budget-assistant.bat" %*
exit /b %ERRORLEVEL%
