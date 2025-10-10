@echo off
REM Run the application. If jar exists, run it; otherwise run from out directory.
setlocal
if exist target\audio-streaming.jar (
  java -cp target\audio-streaming.jar com.example.App %*
  endlocal & exit /b %errorlevel%
)
if exist out (
  java -cp out com.example.App %*
  endlocal & exit /b %errorlevel%
)
echo Build the project first (run build.bat).
endlocal
