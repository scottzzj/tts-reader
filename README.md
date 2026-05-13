# tts-reader

NaturalReader-style local reader demo.  
仿 NaturalReader 的本地朗读器示例。

## What This Project Does / 这个项目具体做什么

This project is a local TTS reader with synchronized text highlighting.  
这个项目是一个本地 TTS 阅读器，支持语音播放时的文本高亮跟读。

## Runtime Screenshot / 页面运行图
查看github pages

## Stack / 技术栈

- `frontend`: Vue 3 + Vite
- `backend`: Spring Boot
- `tts mode`: `mock` or external HTTP TTS service

## Frontend Details / 前端细节

Frontend entry / 前端入口：

- `frontend/src/App.vue`
- `frontend/src/components/ReaderToolbar.vue`
- `frontend/src/components/ReaderTimeline.vue`
- `frontend/src/components/ReaderPanel.vue`

Current frontend logic / 当前前端逻辑：

- The editor keeps the original full text / 输入区始终保留完整原文
- The preview area renders the full text instead of switching entire chunks / 预览区显示完整文本，不再整块切换
- Highlighting follows backend segment timing / 高亮跟随后端返回的 segment 时间轴
- The first batch is shorter so playback can start earlier / 第一批文本更短，用来尽快起播
- Later batches are grouped by paragraph and sentence boundaries / 后续批次按段落和句子边界分组

Frontend batch config / 前端分批参数：

- first target length: `180`
- first minimum length: `120`
- paragraph target length: `320`
- paragraph minimum length: `220`

## Backend Details / 后端细节

Backend entry / 后端入口：

- `backend/src/main/java/com/example/ttsreader/controller/TtsController.java`
- `backend/src/main/java/com/example/ttsreader/service/TtsService.java`

Main API endpoints / 主要接口：

- `POST /api/tts`
  Single request, directly returns one synthesized result / 单次请求，直接返回一次合成结果
- `POST /api/tts/tasks`
  Creates an async task for progressive synthesis / 创建异步任务，用于渐进式合成
- `GET /api/tts/tasks/{taskId}`
  Poll task progress, chunk readiness, and alignment / 轮询任务进度、分块可播状态和对齐结果
- `GET /api/tts/voices`
  Returns available voices / 返回可用音色
- `GET /api/tts/files/{fileName}`
  Serves generated audio files / 返回生成后的音频文件

Task model / 任务模型：

- Task status: `PROCESSING`, `COMPLETED`, `FAILED`
- Chunk status: `PENDING`, `READY`, `BUFFERED`
- Alignment status: `PENDING`, `ALIGNED`, `ROUGH`, `FAILED`

Chunking behavior / 文本分块逻辑：

- Backend target chunk length: `1200`
- Backend minimum chunk length: `600`
- Hard boundaries: `。！？；.!?;` and line breaks
- Soft boundaries: `，、：,:`
- If text is too long, backend splits it first, synthesizes each chunk, then merges audio and segment timelines

Audio output behavior / 音频输出逻辑：

- Generated files are stored under `${user.home}/tts-reader/outputs`
- Public audio path prefix is `/api/tts/files`
- Expired tasks and output files are cleaned automatically
- Default task retention time is 2 hours

## Voices / 音色

In `mock` mode, backend provides these demo voices:  
`mock` 模式下，后端内置以下演示音色：

- `aria`
- `luna`
- `jun`

In HTTP mode, voices come from the upstream TTS service.  
在 HTTP 模式下，音色列表来自上游 TTS 服务。

The current UI maps real-mode voices like this:  
当前界面对真实模式音色做了中文映射：

- `cosy-default` -> `默认男声`
- `cosy-gentle` -> `温柔女声`
- `cosy-clear` -> `清晰女声`

## TTS Modes / TTS 模式

### `mock`

Built-in mock audio generator for local UI and interaction testing.  
内置模拟音频生成器，适合本地联调 UI 和交互。

Default config / 默认配置：

- sample rate: `22050`
- default char duration: `180ms`
- punctuation duration: `260ms`
- whitespace duration: `90ms`
- pause gap: `24ms`

### `http`

Calls an external HTTP TTS service.  
调用外部 HTTP TTS 服务。

Backend config / 后端配置：

```yaml
tts:
  mode: http
  output-dir: ${user.home}/tts-reader/outputs
  public-path-prefix: /api/tts/files
  chunk-concurrency: 2
  http:
    base-url: http://127.0.0.1:9880
    connect-timeout-seconds: 5
    read-timeout-seconds: 900
```

## TTS Install / TTS 安装说明

This repo does not include CosyVoice itself.  
本仓库不包含 CosyVoice 本体。

Expected local path / 默认本地路径：

```text
D:\CosyVoice
```

Required bridge script / 需要存在的 bridge 启动脚本：

```text
D:\CosyVoice\start-cosyvoice3-bridge.bat
```

Install steps / 安装步骤：

1. Install Python, CUDA, and all dependencies required by your CosyVoice3 runtime.  
   安装 CosyVoice3 需要的 Python、CUDA 和依赖环境。
2. Download or clone your CosyVoice3 runtime into `D:\CosyVoice`.  
   将 CosyVoice3 运行环境下载或克隆到 `D:\CosyVoice`。
3. Prepare `start-cosyvoice3-bridge.bat` so it can start an HTTP bridge on port `9880`.  
   准备 `start-cosyvoice3-bridge.bat`，确保它能在 `9880` 启动 HTTP bridge。
4. Verify health check works before starting this project.  
   启动本项目之前，先确认健康检查可用：

```text
http://127.0.0.1:9880/health
```

If you already have another compatible TTS HTTP service, you can change `tts.http.base-url` to that address.  
如果你已有别的兼容 HTTP TTS 服务，也可以直接修改 `tts.http.base-url`。

## Run / 启动

### Frontend only / 仅前端

```powershell
cd E:\IdeaWorkSpace\tts-reader\frontend
npm.cmd install
npm.cmd run dev
```

### Backend only / 仅后端

```powershell
cd E:\IdeaWorkSpace\tts-reader
.\mvnw.cmd -f backend\pom.xml spring-boot:run
```

## Scripts / 启动脚本说明

### `start-reader-real.bat`

Starts the Spring Boot backend on `8081` in real TTS mode.  
以真实 TTS 模式启动 Spring Boot 后端，端口 `8081`。

What it assumes / 前提：

- CosyVoice bridge is already running on `http://127.0.0.1:9880`
- Backend will call the upstream TTS service with `--tts.mode=http`

### `start-real-stack.bat`

Starts the full real-mode stack.  
启动完整真实模式链路。

What it does / 行为：

1. Starts `D:\CosyVoice\start-cosyvoice3-bridge.bat`
2. Waits for `http://127.0.0.1:9880/health`
3. Starts `start-reader-real.bat`
4. Starts `start-frontend-real.bat`

### `start-normal-stack.bat`

Starts bridge + backend only.  
只启动 bridge 和后端。

What it does / 行为：

1. Starts `D:\CosyVoice\start-cosyvoice3-bridge.bat`
2. Waits for health check success
3. Starts `start-reader-real.bat`
4. Does not start the frontend dev server / 不启动前端开发服务

### `start-frontend-real.bat`

Starts the Vite frontend on `127.0.0.1:5174`.  
启动 Vite 前端，地址 `127.0.0.1:5174`。

It also sets / 同时设置：

- `VITE_HOST=127.0.0.1`
- `VITE_PORT=5174`
- `VITE_STRICT_PORT=true`
- `VITE_API_TARGET=http://127.0.0.1:8081`

## Quick Start / 快速启动

### Full real stack / 启动完整真实模式

```powershell
E:\IdeaWorkSpace\tts-reader\start-real-stack.bat
```

### Bridge + backend only / 只启动 bridge 和后端

```powershell
E:\IdeaWorkSpace\tts-reader\start-normal-stack.bat
```

### Backend only in HTTP mode / 只启动真实模式后端

```powershell
E:\IdeaWorkSpace\tts-reader\start-reader-real.bat
```

Open / 打开：

```text
http://127.0.0.1:8081
```

## Current Limitations / 当前限制

- Alignment is estimated from audio duration, not forced alignment from the model / 对齐是基于音频时长估算，不是模型强制对齐
- Real-mode behavior depends on the external TTS bridge implementation / 真实模式依赖外部 bridge 的实现
- This repo assumes a Windows local environment and fixed local paths / 当前仓库默认按 Windows 本地固定路径运行

## License / 许可证

MIT
