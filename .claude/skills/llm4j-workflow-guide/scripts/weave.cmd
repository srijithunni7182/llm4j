@echo off
rem Runs weave for a coding agent whose terminal has no `weave` command. Looks, in order: WEAVE_JAR; a weave.exe/weave.cmd
rem on PATH that is not this file; the editor extension's bundled jar; bin\weave.jar next to this skill; a jar fetched into
rem %USERPROFILE%\.llm4j\tools. Otherwise it prints the one line that installs weave. Changes nothing and contacts no one.
setlocal enabledelayedexpansion
if defined WEAVE_JAR (
  if not exist "%WEAVE_JAR%" ( echo WEAVE_JAR points at "%WEAVE_JAR%", which is not a file. 1>&2 & exit /b 2 )
  java -jar "%WEAVE_JAR%" %* & exit /b !ERRORLEVEL!
)
for %%P in (weave.exe weave.bat) do (
  for %%F in ("%%~$PATH:P") do if not "%%~F"=="" if /I not "%%~F"=="%~f0" ( "%%~F" %* & exit /b !ERRORLEVEL! )
)
for /d %%D in ("%USERPROFILE%\.vscode*") do for /d %%E in ("%%D\extensions\srijithunni7182.vscode-llm4j-loom-*") do if exist "%%E\bin\weave.jar" ( java -jar "%%E\bin\weave.jar" %* & exit /b !ERRORLEVEL! )
if exist "%~dp0..\bin\weave.jar" ( java -jar "%~dp0..\bin\weave.jar" %* & exit /b !ERRORLEVEL! )
for /d %%D in ("%USERPROFILE%\.llm4j\tools\*") do for %%J in ("%%D\ai-agent4j-loom-*-cli.jar") do if exist "%%J" ( java -jar "%%J" %* & exit /b !ERRORLEVEL! )
echo weave was not found in this terminal. Install it once: 1>&2
echo   jbang app install weave@srijithunni7182/llm4j        (needs JBang: https://www.jbang.dev) 1>&2
echo or install the "Loom" extension in VS Code (its own terminals get weave on the PATH), or set WEAVE_JAR. 1>&2
exit /b 2
