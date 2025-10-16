@echo off
REM Build project using javac (no Maven)
setlocal
set "SRC_DIR=src\main\java"
set "OUT_DIR=out"
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"
echo Collecting Java sources...
if exist sources.txt del /q sources.txt
for /R "%SRC_DIR%" %%f in (*.java) do @echo %%f>>sources.txt
if not exist sources.txt (
  echo No Java source files found under %SRC_DIR%.
  endlocal
  exit /b 1
)
echo Compiling Java sources...
javac -d "%OUT_DIR%" @sources.txt
if errorlevel 1 (
  echo Compilation failed. Ensure JDK is installed and 'javac' is on PATH.
  del /q sources.txt
  endlocal
  exit /b 1
)
del /q sources.txt
echo Compilation successful.
if not exist target mkdir target
echo Creating jar: target\audio-streaming.jar
jar --create --file target\audio-streaming.jar -C "%OUT_DIR%" .
if errorlevel 1 (
  echo Jar creation failed. Ensure 'jar' is available (part of the JDK).
  endlocal
  exit /b 1
)
echo Jar created at target\audio-streaming.jar
endlocal
