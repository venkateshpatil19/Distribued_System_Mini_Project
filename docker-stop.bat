@echo off
rem Stops the DeployGuard containers. The controllers' state is kept in the "state" volume;
rem use "docker compose down -v" to delete it and start fresh.
cd /d "%~dp0"
docker compose down
echo DeployGuard containers stopped.
pause
