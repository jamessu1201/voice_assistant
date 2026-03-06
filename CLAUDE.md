# 語音助手 (Voice Assistant)

## 專案概述
Android 機車語音助手，支援 Spotify 音樂控制、App 啟動、一般對話。
使用者透過喚醒詞（Porcupine）或 PTT 按鈕觸發，語音經由伺服器端 Whisper 辨識後，由 LLM 判斷意圖並執行對應動作。

## 架構

```
[Android App] → HTTP → [Gateway Server (4090)] → HTTP → [Whisper Server (3090)]
                              │                              100.81.58.112:8081
                              ├── Ollama (localhost:11434)
                              │    ├── qwen2.5:14b (指令解析)
                              │    └── deepseek-r1:32b (對話)
                              └── DuckDuckGo (網路搜尋)
```

- **Android App** (`android/`): Kotlin, Porcupine 喚醒詞, 錄音 & 送出
- **Gateway Server** (`server/gateway/app.py`): FastAPI, 跑在 4090 機器, port 8080
  - 接收 Android 的語音/指令
  - 轉發音檔給 Whisper Server
  - 用 Ollama 判斷意圖 (Spotify / 開 App / 對話)
  - 需要時用 DuckDuckGo 搜尋即時資訊
- **Whisper Server** (`server/whisper/whisper_server.py`): FastAPI, 跑在 3090 機器, port 8081
  - faster-whisper large-v3, CUDA float16
  - 支援多模式 (spotify, drone) 的修正表
  - OpenCC 簡轉繁 + 降噪 (noisereduce)
  - 重複短語移除

## 兩台伺服器

| 機器 | GPU | 角色 | Tailscale IP | 跑什麼 |
|------|-----|------|-------------|--------|
| 主機 | RTX 4090 | Gateway + LLM | (本機) | `server/gateway/app.py` + Ollama |
| 副機 | RTX 3090 | ASR | 100.81.58.112 | `server/whisper/whisper_server.py` |

兩台透過 Tailscale VPN 連接。

## 常用指令

```bash
# 啟動 Gateway Server (在 4090 上)
cd server/gateway
uvicorn app:app --host 0.0.0.0 --port 8080

# 啟動 Whisper Server (在 3090 上)
cd server/whisper
uvicorn whisper_server:app --host 0.0.0.0 --port 8081

# 跑測試 (在 4090 上，不需要 GPU)
cd /path/to/voice-assistant
pytest tests/ -v

# 部署 Whisper 更新到 3090
./deploy.sh
```

## 測試

- `tests/test_spotify.py`: 測試 Spotify 指令解析（純邏輯，不需要 GPU/網路）
- `tests/test_whisper_corrections.py`: 測試 Whisper 後處理修正（純邏輯）
- 測試可以直接在 4090 上跑，不需要啟動任何服務

## 開發注意事項

- 改 `server/gateway/` 的 code → 直接在 4090 重啟服務即可
- 改 `server/whisper/` 的 code → 跑 `./deploy.sh` 同步到 3090 並重啟
- Android 端的修改需要用 Android Studio 建置
- Whisper Server 的 `modes.py` 定義了各模式的修正表和 initial_prompt
- 所有中文處理都使用繁體中文
