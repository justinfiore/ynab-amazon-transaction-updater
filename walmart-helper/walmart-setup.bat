@echo off
setlocal
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if "%~1"=="--categories" goto categories
"%JAVA%" -cp "%~dp0..\lib\*" com.ynab.amazon.YNABAmazonTransactionUpdater --walmart-setup %*
exit /b %ERRORLEVEL%
:categories
if "%~2"=="" goto defaultcategories
"%JAVA%" -cp "%~dp0..\lib\*" com.ynab.amazon.YNABAmazonTransactionUpdater --walmart-categories "%~2"
exit /b %ERRORLEVEL%
:defaultcategories
"%JAVA%" -cp "%~dp0..\lib\*" com.ynab.amazon.YNABAmazonTransactionUpdater --walmart-categories
exit /b %ERRORLEVEL%
