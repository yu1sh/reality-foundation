@echo off
setlocal
if not defined REALITY_GRADLE_HOME (
  echo REALITY_GRADLE_HOME must point to an exact Gradle 8.8 installation. 1>&2
  exit /b 1
)
"%REALITY_GRADLE_HOME%\bin\gradle.bat" %*
endlocal
