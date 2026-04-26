@echo off
setlocal

cd /d E:\IdeaWorkSpace\tts-reader\frontend

echo Starting tts-reader frontend on http://127.0.0.1:5174
echo Proxy target: http://127.0.0.1:8081

set VITE_HOST=127.0.0.1
set VITE_PORT=5174
set VITE_STRICT_PORT=true
set VITE_API_TARGET=http://127.0.0.1:8081

call npm.cmd run dev

pause
