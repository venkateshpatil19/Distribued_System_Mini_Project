@echo off
rem Starts DeployGuard: 3 controllers, 3 agents and the dashboard, then opens the browser.
rem Needs Java 21+ and Node.js 18+. Every step is also written to logs\start.log.
setlocal
cd /d "%~dp0"
title DeployGuard launcher
set "ROOT=%CD%"
if not exist logs mkdir logs
if not exist data mkdir data
set "LOG=%ROOT%\logs\start.log"
echo ==== start.bat %date% %time% ==== > "%LOG%"

rem ---------- Java 21+: PATH first, then JAVA_HOME and the usual install folders ----------
call :say Looking for Java 21 or newer...
set "JAVA="
call :tryjava java
if defined JAVA_HOME if not defined JAVA call :tryjava "%JAVA_HOME%\bin\java.exe"
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-*" "%ProgramFiles%\Java\jdk-*" "%ProgramFiles%\Microsoft\jdk-*" "%ProgramFiles%\Zulu\zulu-*" "%ProgramFiles%\Amazon Corretto\jdk*" "%USERPROFILE%\.jdks\*") do if not defined JAVA call :tryjava "%%~d\bin\java.exe"
rem IntelliJ IDEA ships its own Java 21 runtime (jbr).
for /d %%d in ("%ProgramFiles%\JetBrains\*") do if not defined JAVA call :tryjava "%%~d\jbr\bin\java.exe"
if not defined JAVA (
  call :say [x] Java 21 or newer was not found. The java on your PATH reports:
  java -version 2>&1
  java -version >> "%LOG%" 2>&1
  call :fail "Install JDK 21 from https://adoptium.net (tick 'Set JAVA_HOME'), then run start.bat again."
  exit /b 1
)
call :say   using "%JAVA%"

rem ---------- Node.js 18+ ----------
set "NODE="
call :trynode node
if not defined NODE call :trynode "%ProgramFiles%\nodejs\node.exe"
if not defined NODE (
  call :fail "Node.js 18 or newer was not found. Install Node.js 20 LTS from https://nodejs.org, then run start.bat again."
  exit /b 1
)
call :say   using "%NODE%"

rem ---------- ready-made builds (build.bat recreates them after code changes) ----------
if not exist "backend\target\deployguard.jar" (
  call :fail "backend\target\deployguard.jar is missing. Run build.bat first."
  exit /b 1
)
if not exist "frontend\.next\standalone\server.js" (
  call :fail "The dashboard build is missing. Run build.bat first."
  exit /b 1
)

rem ---------- stop an old run and free the ports, whoever holds them ----------
call :say Freeing ports 3000, 8081-8083, 8101-8103, 9091-9093...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\stop.ps1" >> "%LOG%" 2>&1
if errorlevel 1 (
  type "%LOG%" | findstr /C:"still in use"
  call :fail "Some ports could not be freed. Run start.bat as administrator, or close the program using them."
  exit /b 1
)
call :say   ports are free

set "JAR=%ROOT%\backend\target\deployguard.jar"
rem 127.0.0.1 instead of localhost: Node 18 tries IPv6 first and does not fall back.
set "PEERS=1=127.0.0.1:9091,2=127.0.0.1:9092,3=127.0.0.1:9093"
call :say Starting 3 controllers...
for %%i in (1 2 3) do call :controller %%i
timeout /t 5 /nobreak >nul
call :say Starting 3 agents...
for %%i in (1 2 3) do call :agent %%i
call :say Starting the dashboard...
call :dashboard

call :say Waiting for the dashboard to answer (up to 2 minutes)...
set /a tries=0
:wait
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://127.0.0.1:3000/api/overview; if ($r.StatusCode -eq 200) { exit 0 } } catch {}; exit 1" >nul 2>&1
if not errorlevel 1 goto ready
set /a tries+=1
if %tries% geq 40 goto notready
timeout /t 2 /nobreak >nul
goto wait

:ready
call :say
call :say DeployGuard is running.
call :say   Dashboard:   http://localhost:3000
call :say   Controllers: http://localhost:8081  8082  8083
call :say   Logs:        %ROOT%\logs
call :say   State:       %ROOT%\data   (delete this folder to start fresh)
call :say   Stop:        stop.bat     Demo: demo.bat
start "" http://localhost:3000/dashboard
timeout /t 10
exit /b 0

:notready
call :say [x] The cluster did not answer within 2 minutes. Last log lines:
for %%f in (controller-1 dashboard) do (
  echo ---- %%f.log
  powershell -NoProfile -Command "Get-Content '%ROOT%\logs\%%f.log' -Tail 12 -ErrorAction SilentlyContinue"
)
pause
exit /b 1

rem ---------- helpers ----------

:say
echo(%*
echo(%*>> "%LOG%"
exit /b 0

:fail
echo [x] %~1
echo [x] %~1>> "%LOG%"
echo Details are in logs\start.log
pause
exit /b 1

rem Sets JAVA to %1 when that java runs and is version 21 or newer.
:tryjava
"%~1" -version >nul 2>&1 || exit /b 0
"%~1" -version 2>&1 | findstr /R /C:"version .2[1-9]" /C:"version .[3-9][0-9]" >nul && set "JAVA=%~1"
exit /b 0

rem Sets NODE to %1 when that node runs and is version 18 or newer.
:trynode
"%~1" -e "process.exit(+process.versions.node.split('.')[0] >= 18 ? 0 : 1)" >nul 2>&1 && set "NODE=%~1"
exit /b 0

rem Each process gets a small .cmd file that restarts it if it exits, in its own minimized window.
:controller
(
  echo @echo off
  echo title DG controller-%1
  echo set DG_CONTROLLER_ID=%1
  echo set HTTP_PORT=808%1
  echo set GRPC_PORT=909%1
  echo set PEERS=%PEERS%
  echo set DATA_DIR=%ROOT%\data
  echo :loop
  echo "%JAVA%" -Xmx192m -jar "%JAR%" controller ^>^> "%ROOT%\logs\controller-%1.log" 2^>^&1
  echo echo process exited, restarting ^>^> "%ROOT%\logs\controller-%1.log"
  echo timeout /t 1 /nobreak ^>nul
  echo goto loop
) > "%ROOT%\logs\run-controller-%1.cmd"
start "DG controller-%1" /min cmd /c "%ROOT%\logs\run-controller-%1.cmd"
exit /b 0

rem Agents know every controller and follow whichever one is the leader.
:agent
(
  echo @echo off
  echo title DG agent-%1
  echo set DG_AGENT_ID=agent-%1
  echo set HTTP_PORT=810%1
  echo set CONTROLLERS=%PEERS%
  echo :loop
  echo "%JAVA%" -Xmx128m -jar "%JAR%" agent ^>^> "%ROOT%\logs\agent-%1.log" 2^>^&1
  echo echo process exited, restarting ^>^> "%ROOT%\logs\agent-%1.log"
  echo timeout /t 1 /nobreak ^>nul
  echo goto loop
) > "%ROOT%\logs\run-agent-%1.cmd"
start "DG agent-%1" /min cmd /c "%ROOT%\logs\run-agent-%1.cmd"
exit /b 0

rem The dashboard proxies to all three controllers and skips one that is down.
:dashboard
(
  echo @echo off
  echo title DG dashboard
  echo set CONTROLLER_URL=http://127.0.0.1:8081,http://127.0.0.1:8082,http://127.0.0.1:8083
  echo set PORT=3000
  echo set HOSTNAME=0.0.0.0
  echo cd /d "%ROOT%\frontend\.next\standalone"
  echo :loop
  echo "%NODE%" server.js ^>^> "%ROOT%\logs\dashboard.log" 2^>^&1
  echo echo process exited, restarting ^>^> "%ROOT%\logs\dashboard.log"
  echo timeout /t 1 /nobreak ^>nul
  echo goto loop
) > "%ROOT%\logs\run-dashboard.cmd"
start "DG dashboard" /min cmd /c "%ROOT%\logs\run-dashboard.cmd"
exit /b 0
