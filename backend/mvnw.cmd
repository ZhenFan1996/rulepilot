@echo off
setlocal

set "BASE_DIR=%~dp0"
set "MAVEN_VERSION=3.9.13"
set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%"
set "MAVEN_ARCHIVE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%" mkdir "%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
  if not exist "%MAVEN_ARCHIVE%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile '%MAVEN_ARCHIVE%'"
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%MAVEN_ARCHIVE%' '%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%'"
  if errorlevel 1 exit /b 1
)

cd /d "%BASE_DIR%"
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
