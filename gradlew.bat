@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PINNED_GRADLE_VERSION=8.8"
set "PINNED_GRADLE_SHA256=a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612"
if defined GRADLE_VERSION if not "%GRADLE_VERSION%"=="%PINNED_GRADLE_VERSION%" (
  echo Refusing Gradle %GRADLE_VERSION%; only Gradle %PINNED_GRADLE_VERSION% is allowed. 1>&2
  exit /b 1
)

if defined REALITY_GRADLE_HOME (
  echo Refusing an external Gradle home; use the verified launcher distribution cache. 1>&2
  exit /b 1
)

if defined GRADLE_HOME (
  echo Refusing an external Gradle home; use the verified launcher distribution cache. 1>&2
  exit /b 1
)

where gradle >nul 2>nul
if not errorlevel 1 (
  echo Refusing an unverified Gradle executable on PATH; use the verified launcher distribution cache. 1>&2
  exit /b 1
)

set "BASE_DIR=%~dp0"
set "DIST_DIR=%BASE_DIR%.gradle\wrapper\dists\gradle-%PINNED_GRADLE_VERSION%-bin"
set "PINNED_GRADLE_HOME=%DIST_DIR%\gradle-%PINNED_GRADLE_VERSION%"
set "ZIP=%DIST_DIR%\gradle-%PINNED_GRADLE_VERSION%-bin.zip"

if not exist "%ZIP%" (
  echo No verified Gradle %PINNED_GRADLE_VERSION% ZIP is available. Automatic Windows download is disabled. 1>&2
  exit /b 1
)

set "ACTUAL_GRADLE_SHA256="
for /f "usebackq delims=" %%H in (`powershell.exe -NoProfile -NonInteractive -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%ZIP%').Hash.ToLowerInvariant()"`) do set "ACTUAL_GRADLE_SHA256=%%H"
if not "!ACTUAL_GRADLE_SHA256!"=="%PINNED_GRADLE_SHA256%" (
  echo Refusing Gradle ZIP with unexpected SHA-256: %ZIP% 1>&2
  exit /b 1
)

if not exist "%PINNED_GRADLE_HOME%\bin\gradle.bat" (
  if exist "%PINNED_GRADLE_HOME%" (
    echo Refusing to overwrite an existing incomplete Gradle directory: %PINNED_GRADLE_HOME% 1>&2
    exit /b 1
  )
  powershell.exe -NoProfile -NonInteractive -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%DIST_DIR%'"
  if errorlevel 1 exit /b 1
)

call :verify_home "%PINNED_GRADLE_HOME%"
if errorlevel 1 exit /b 1
call "%PINNED_GRADLE_HOME%\bin\gradle.bat" %*
exit /b %errorlevel%

:verify_home
set "CANDIDATE_HOME=%~1"
if not exist "%CANDIDATE_HOME%\bin\gradle.bat" (
  echo Gradle executable not found under %CANDIDATE_HOME%. 1>&2
  exit /b 1
)
set "HOME_GRADLE_VERSION="
for /f "tokens=2" %%G in ('call "%CANDIDATE_HOME%\bin\gradle.bat" --version ^| findstr /b /c:"Gradle "') do set "HOME_GRADLE_VERSION=%%G"
if not "!HOME_GRADLE_VERSION!"=="%PINNED_GRADLE_VERSION%" (
  echo Refusing unexpected Gradle version under %CANDIDATE_HOME%: !HOME_GRADLE_VERSION! 1>&2
  exit /b 1
)
exit /b 0
