@echo off
rem Scales the Docker agents and measures join time, config propagation and throughput.
rem Usage: scale.bat 10   (start the stack with docker-start.bat first)
cd /d "%~dp0"
set "N=%~1"
if "%N%"=="" set "N=10"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\scale.ps1" -Agents %N%
pause
