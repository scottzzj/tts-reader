# tts-reader

Local TTS reader demo with synchronized text highlighting.

## Preview

- GitHub Pages: [https://scottzzj.github.io/tts-reader/](https://scottzzj.github.io/tts-reader/)
- Local static preview: `http://127.0.0.1:8081/preview.html`

The preview page is a static shell for README/demo display only. Real playback still depends on the backend and a TTS service.

## Runtime Screenshot

See GitHub Pages preview:
[https://scottzzj.github.io/tts-reader/](https://scottzzj.github.io/tts-reader/)

## Overview

- `frontend/`: Vue 3 + Vite UI
- `backend/`: Spring Boot API and static asset host
- `docs/`: GitHub Pages static preview

TTS modes:

- `mock`: local fake audio for UI testing
- `http`: forwards requests to an external HTTP TTS bridge

Main capabilities:

- Editable reading input
- Read-along preview with synchronized highlighting
- Progressive chunk synthesis for long text
- Click-to-seek from highlighted text
- Timeline scrubbing
- Voice and speed switching

## API

- `POST /api/tts`
- `POST /api/tts/tasks`
- `GET /api/tts/tasks/{taskId}`
- `GET /api/tts/voices`
- `GET /api/tts/files/{fileName}`

## Run

### Mock / development mode

Frontend:

```powershell
cd E:\IdeaWorkSpace\tts-reader\frontend
npm.cmd install
npm.cmd run dev
```

Backend:

```powershell
cd E:\IdeaWorkSpace\tts-reader
.\mvnw.cmd -f backend\pom.xml spring-boot:run
```

Open:

- Main page: `http://127.0.0.1:8080`
- Static preview: `http://127.0.0.1:8080/preview.html`

### Real HTTP TTS mode

This repo does not bundle CosyVoice itself. If you already have a compatible HTTP TTS service, point `tts.http.base-url` to it.

Common local setup:

- TTS bridge: `http://127.0.0.1:9880`
- Main page: `http://127.0.0.1:8081`
- Static preview: `http://127.0.0.1:8081/preview.html`

Common scripts:

- `start-reader-real.bat`: backend on `8081` with `tts.mode=http`
- `start-real-stack.bat`: bridge + backend + frontend dev server

## Configuration

Default backend config lives in [backend/src/main/resources/application.yml](/E:/IdeaWorkSpace/tts-reader/backend/src/main/resources/application.yml).

```yaml
tts:
  mode: mock
  output-dir: ${user.home}/tts-reader/outputs
  public-path-prefix: /api/tts/files
  chunk-concurrency: 2
  stream-task-queue-capacity: 16
  synthesis-queue-capacity: 128
  http:
    base-url: http://127.0.0.1:9880
    connect-timeout-seconds: 5
    read-timeout-seconds: 900
```

## Notes

This project is a local demo. The real playback path depends on an external HTTP TTS service.
