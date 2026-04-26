@echo off
setlocal

echo Starting CosyVoice bridge...
start "" D:\CosyVoice\start-cosyvoice3-bridge.bat

echo Waiting for CosyVoice bridge health check...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$deadline = (Get-Date).AddMinutes(3);" ^
  "while ((Get-Date) -lt $deadline) {" ^
  "  try {" ^
  "    $resp = Invoke-RestMethod -Uri 'http://127.0.0.1:9880/health' -TimeoutSec 5;" ^
  "    if ($resp.status -eq 'ok') { exit 0 }" ^
  "  } catch {}" ^
  "  Start-Sleep -Seconds 2;" ^
  "}" ^
  "Write-Error 'CosyVoice bridge did not become ready in time.'; exit 1"

if errorlevel 1 (
  echo CosyVoice bridge startup failed.
  pause
  exit /b 1
)

echo Starting tts-reader backend...
start "" E:\IdeaWorkSpace\tts-reader\start-reader-real.bat

echo.
echo Access URL: http://127.0.0.1:8081
echo.
echo Normal mode only starts:
echo 1. CosyVoice bridge on 9880
echo 2. Spring Boot page/backend on 8081
echo.
exit /b 0
