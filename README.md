# tts-reader

NaturalReader-style local reader demo.

## Structure

- `frontend`: Vue 3 + Vite reader UI
- `backend`: Spring Boot BFF with mock TTS and pluggable HTTP TTS mode

## Run

### Frontend

```powershell
cd E:\IdeaWorkSpace\tts-reader\frontend
npm.cmd install
npm.cmd run dev
```

### Backend

```powershell
cd E:\IdeaWorkSpace\tts-reader
.\mvnw.cmd -f backend\pom.xml spring-boot:run
```

## TTS mode

Default mode is `mock`, which generates a synthetic WAV file and character timings so the page can be tested end-to-end.

To connect a real TTS service, update `backend/src/main/resources/application.yml`:

```yaml
tts:
  mode: http
  http:
    base-url: http://127.0.0.1:9880
```

## Real CosyVoice mode

This repo includes helper scripts to run the page against a local CosyVoice3 bridge:

1. Start the bridge service:

```powershell
D:\CosyVoice\start-cosyvoice3-bridge.bat
```

2. Start the backend in real TTS mode:

```powershell
E:\IdeaWorkSpace\tts-reader\start-reader-real.bat
```

3. Open:

```text
http://127.0.0.1:8081
```

Or start both with:

```powershell
E:\IdeaWorkSpace\tts-reader\start-real-stack.bat
```

This starts:

- backend UI/API: `http://127.0.0.1:8081`
- frontend dev server: `http://127.0.0.1:5174`

Notes:

- Real mode uses the local CosyVoice3 model on `D:\CosyVoice`.
- Current alignment is estimated per character from the generated audio duration, not forced alignment from the model.
- The bridge now uses GPU when available.
- Long text is automatically split in the backend and merged back into one WAV plus one global character timeline.
- Real mode frontend dev can point at `8081` through `VITE_API_TARGET`.
- Chunk synthesis uses limited parallelism to reduce long-text wait time.
