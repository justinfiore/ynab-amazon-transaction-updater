@echo off
echo Building YNAB Amazon Transaction Updater...
call gradlew build

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Running YNAB Amazon Transaction Updater...
echo.
java -jar build/libs/YNABAmazonTransactionUpdater-1.0.0.jar

echo.
echo Application finished.
pause 