@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0Repair-BackupPull.ps1"
echo.
echo Send repair-result.json from this folder. Never send private keys.
pause
endlocal
