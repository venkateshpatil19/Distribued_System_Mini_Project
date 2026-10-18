@echo off
rem Runs the narrated real-world demo against the running cluster (start.bat first).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\demo.ps1"
pause
