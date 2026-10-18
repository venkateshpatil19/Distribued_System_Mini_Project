@echo off
rem Runs all tests. Nothing external is needed for the backend tests.
rem Browser tests run only if the app is running (start.bat).
setlocal
cd /d "%~dp0"

echo ===== Backend tests =====
pushd backend
call mvn -q test
set BACK=%errorlevel%
popd

echo ===== Dashboard unit tests =====
pushd frontend
call npm test
set FRONT=%errorlevel%

set E2E=skipped
curl -sf http://localhost:3000/healthz >nul 2>&1
if not errorlevel 1 (
  echo ===== Browser tests =====
  call npx playwright install chromium
  call npx playwright test
  if errorlevel 1 (set E2E=FAILED) else (set E2E=passed)
)
popd

echo.
if "%BACK%"=="0" (echo Backend tests:   passed) else (echo Backend tests:   FAILED)
if "%FRONT%"=="0" (echo Dashboard tests: passed) else (echo Dashboard tests: FAILED)
echo Browser tests:   %E2E%
pause
