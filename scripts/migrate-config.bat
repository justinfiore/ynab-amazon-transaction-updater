@echo off
setlocal
set "LIB=%~dp0..\lib"
if not exist "%LIB%" set "LIB=%~dp0..\build\install\ynab-budget-assistant\lib"
if not exist "%LIB%" (
  echo Run gradlew installDist before using the source-checkout migration script. 1>&2
  exit /b 1
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -cp "%LIB%\*" com.ynab.budgetassistant.config.ConfigMigration %*
exit /b %ERRORLEVEL%
