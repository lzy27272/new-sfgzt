@echo off
setlocal EnableExtensions DisableDelayedExpansion
title SFG Backup Device Setup
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-BackupPull.ps1" -ConnectionFile "%~dp0backup-connection.json"
set "SFG_EXIT=%ERRORLEVEL%"
echo.
if not "%SFG_EXIT%"=="0" echo Setup did not complete. Keep the PowerShell error text above for support.
pause
exit /b %SFG_EXIT%
