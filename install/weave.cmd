@echo off
rem weave: runs the weave CLI with the Java on this machine.
rem Put this file and weave.jar in the same folder (or set WEAVE_JAR to the jar).
setlocal
set "JAR=%WEAVE_JAR%"
if "%JAR%"=="" set "JAR=%~dp0weave.jar"
if not exist "%JAR%" (
  echo weave: cannot find weave.jar ^(looked for %JAR%^). Put it next to this script, or set WEAVE_JAR. 1>&2
  exit /b 2
)
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
  echo weave: Java is not installed. Install a JDK 17 or newer ^(https://adoptium.net^), then run weave again. 1>&2
  exit /b 2
)
"%JAVA%" %JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
