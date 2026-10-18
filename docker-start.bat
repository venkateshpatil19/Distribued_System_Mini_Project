@echo off
rem Runs DeployGuard in Docker: 3 controllers, 3 agents and the dashboard, one container each.
rem Needs Docker Desktop running. The first run builds the images from source (a few minutes).
setlocal
cd /d "%~dp0"
title DeployGuard on Docker

where docker >nul 2>&1 || (echo [x] Docker not found. Install Docker Desktop from https://www.docker.com/products/docker-desktop & pause & exit /b 1)
docker info >nul 2>&1 || (echo [x] Docker is not running. Start Docker Desktop, wait until it shows "Engine running", then try again. & pause & exit /b 1)

echo Stopping any earlier run and freeing ports 3000 and 8081-8083...
docker compose down >nul 2>&1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1"
if errorlevel 1 (pause & exit /b 1)

echo Building images and starting containers...
docker compose up -d --build
if errorlevel 1 (echo [x] docker compose failed, see the messages above. & pause & exit /b 1)

echo Waiting for the dashboard to answer (up to 2 minutes)...
set /a tries=0
:wait
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://127.0.0.1:3000/api/overview; if ($r.StatusCode -eq 200) { exit 0 } } catch {}; exit 1" >nul 2>&1
if not errorlevel 1 goto ready
set /a tries+=1
if %tries% geq 40 goto notready
timeout /t 2 /nobreak >nul
goto wait

:ready
docker compose ps
echo.
echo DeployGuard is running in Docker.
echo   Dashboard:   http://localhost:3000
echo   Controllers: http://localhost:8081  8082  8083
echo   Logs:        docker compose logs -f controller-1
echo   Stop:        docker-stop.bat
start "" http://localhost:3000/dashboard
timeout /t 10
exit /b 0

:notready
echo [x] The dashboard did not answer within 2 minutes. Container status and recent logs:
docker compose ps
docker compose logs --tail 15 controller-1 dashboard
pause
exit /b 1
