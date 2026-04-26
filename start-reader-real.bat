@echo off
setlocal

cd /d E:\IdeaWorkSpace\tts-reader

echo Starting tts-reader with real CosyVoice bridge on http://127.0.0.1:8081
echo This expects the bridge service at http://127.0.0.1:9880

call .\mvnw.cmd -f backend\pom.xml spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --tts.mode=http --tts.http.base-url=http://127.0.0.1:9880 --tts.chunk-concurrency=2"

pause
