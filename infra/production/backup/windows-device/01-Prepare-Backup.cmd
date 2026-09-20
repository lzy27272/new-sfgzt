@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Prepare-BackupDevice.ps1"
set "SFG_PREPARE_EXIT=%ERRORLEVEL%"
echo.
if not "%SFG_PREPARE_EXIT%"=="0" echo Setup did not complete. Keep the error text above for support.
pause
exit /b %SFG_PREPARE_EXIT%
