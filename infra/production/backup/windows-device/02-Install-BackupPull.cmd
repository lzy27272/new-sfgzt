@echo off
setlocal
chcp 65001 >nul
title 四方馆中台 - 异地备份连接
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-BackupPull.ps1" -ConnectionFile "%~dp0backup-connection.json"
set "SFG_EXIT=%ERRORLEVEL%"
echo.
if "%SFG_EXIT%"=="0" (
  echo 操作完成。请按窗口提示发送“同步结果.json”。
) else (
  echo 操作未完成，请保留窗口中的错误信息。
)
pause
exit /b %SFG_EXIT%
