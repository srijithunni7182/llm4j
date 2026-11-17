@echo off
rem weave, as the Loom extension carries it: runs bin\weave.jar from this extension's folder. It works before the
rem extension has started, from any terminal and for any coding agent. Java comes from LOOM_JAVA, else JAVA_HOME, else
rem the PATH. Make weave Available in Every Terminal writes a copy to %USERPROFILE%\.llm4j\bin\weave.cmd.
setlocal
set "WEAVE_JAR=%~dp0..\bin\weave.jar"
if not exist "%WEAVE_JAR%" (
  echo weave: the jar this launcher runs is missing ^(%WEAVE_JAR%^). Reinstall the Loom extension. 1>&2
  exit /b 2
)
if defined LOOM_JAVA ( set "WEAVE_JAVA=%LOOM_JAVA%" & goto run )
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" ( set "WEAVE_JAVA=%JAVA_HOME%\bin\java.exe" & goto run )
where java >nul 2>nul && ( set "WEAVE_JAVA=java" & goto run )
echo weave needs Java 17 or newer, and none was found: install one ^(https://adoptium.net^), or set JAVA_HOME or LOOM_JAVA. 1>&2
exit /b 2
:run
"%WEAVE_JAVA%" -jar "%WEAVE_JAR%" %*
exit /b %ERRORLEVEL%
