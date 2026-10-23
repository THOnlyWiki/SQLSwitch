@echo off
setlocal
set "ROOT=%~dp0"
set "JAR=%ROOT%build\libs\SQLSwitch-1.0.0-all.jar"
if not exist "%JAR%" (
  echo Building SQLSwitch...
  call "%ROOT%gradlew.bat" fatJar
  if errorlevel 1 exit /b 1
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
