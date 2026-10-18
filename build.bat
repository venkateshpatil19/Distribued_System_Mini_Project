@echo off
rem Rebuilds the backend jar and the dashboard. Run it after changing code (needs Maven 3.9+ and internet the first time).
setlocal
cd /d "%~dp0"
where mvn >nul 2>&1 || (echo [x] Maven not found. Install Maven 3.9+ from https://maven.apache.org and add it to PATH. & goto fail)
echo Building backend...
pushd backend
rem -U retries downloads that failed before instead of using Maven's cached failure.
call mvn -q -U -DskipTests package
popd
if not exist "backend\target\deployguard.jar" (echo [x] Backend build failed. & goto fail)
echo Building dashboard (a few minutes the first time)...
pushd frontend
call npm ci || (popd & echo [x] npm ci failed. & goto fail)
call npm run build || (popd & echo [x] Dashboard build failed. & goto fail)
popd
echo Build finished.
if not "%~1"=="nopause" pause
exit /b 0
:fail
if not "%~1"=="nopause" pause
exit /b 1
