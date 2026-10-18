@echo off
rem Stops DeployGuard and frees every port it uses (3000, 8081-8083, 8101-8103, 9091-9093).
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1"
set "RC=%errorlevel%"
if not "%~1"=="quiet" (
  if "%RC%"=="0" (echo DeployGuard stopped.) else (echo Some ports are still in use, see above.)
  pause
)
exit /b %RC%
