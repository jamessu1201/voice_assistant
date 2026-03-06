# whisper_server.py (RTX 3090)

from fastapi import FastAPI, File, UploadFile, Request, Query
from faster_whisper import WhisperModel
import tempfile
import os
import time
import re
import wave
import numpy as np
from opencc import OpenCC

# 🆕 降噪
try:
    import noisereduce as nr
    NOISEREDUCE_AVAILABLE = True
    print("✓ noisereduce 降噪庫已載入")
except ImportError:
    NOISEREDUCE_AVAILABLE = False
    print("⚠ noisereduce 未安裝，降噪功能停用")

# 載入模式配置
from modes import MODE_CONFIG

app = FastAPI()

print("=" * 60)
print("正在載入 Whisper 模型...")
whisper_model = WhisperModel(
    "large-v3",
    device="cuda",
    device_index=0,
    compute_type="float16"
)
print("✓ Whisper 模型載入完成")

s2t_converter = OpenCC('s2t')
print("✓ 簡繁轉換器初始化完成")

print(f"✓ 已載入模式：{', '.join(MODE_CONFIG.keys())}")
print("=" * 60)

# ============================================================
# 通用修正表（所有模式都會用到）
# ============================================================

OVERCONVERSION_FIX = {
    "幹擾": "干擾",
    "幹涉": "干涉",
    "幹預": "干預",
    "幹戈": "干戈",
    "幹旱": "乾旱",
    "相幹": "相干",
    "不相幹": "不相干",
    "若幹": "若干",
    "一幹二淨": "一乾二淨",
    "吊躍": "調閱",
}

# ============================================================
# 🆕 音頻降噪
# ============================================================

def denoise_audio(input_path: str, output_path: str, prop_decrease: float = 0.7) -> str:
    """
    對音檔進行降噪處理
    
    Args:
        input_path: 輸入音檔路徑
        output_path: 輸出音檔路徑
        prop_decrease: 降噪強度 (0-1)，預設 0.7
    
    Returns:
        輸出音檔路徑
    """
    if not NOISEREDUCE_AVAILABLE:
        return input_path  # 沒有安裝降噪庫，返回原檔案
    
    try:
        # 讀取音檔
        with wave.open(input_path, 'rb') as w:
            params = w.getparams()
            sample_rate = w.getframerate()
            frames = w.readframes(w.getnframes())
            audio = np.frombuffer(frames, dtype=np.int16).astype(np.float32)
        
        # 使用前 0.3 秒作為噪音樣本
        noise_sample_duration = int(sample_rate * 0.3)
        noise_sample = audio[:noise_sample_duration]
        
        # 計算原始噪音 RMS
        orig_noise_rms = np.sqrt(np.mean(noise_sample**2))
        
        # 只有噪音 RMS > 100 才進行降噪（避免過度處理安靜音檔）
        if orig_noise_rms < 100:
            print(f"  🔇 噪音較低 (RMS={orig_noise_rms:.0f})，跳過降噪")
            return input_path
        
        # noisereduce 降噪
        denoised = nr.reduce_noise(
            y=audio,
            sr=sample_rate,
            y_noise=noise_sample,
            prop_decrease=prop_decrease,
            stationary=True,
        )
        
        # 轉回 int16
        denoised = np.clip(denoised, -32768, 32767).astype(np.int16)
        
        # 保存
        with wave.open(output_path, 'wb') as w:
            w.setparams(params)
            w.writeframes(denoised.tobytes())
        
        # 計算降噪效果
        new_noise = denoised[:noise_sample_duration]
        new_noise_rms = np.sqrt(np.mean(new_noise.astype(np.float32)**2))
        reduction = (1 - new_noise_rms / orig_noise_rms) * 100
        
        print(f"  🔇 降噪完成：RMS {orig_noise_rms:.0f} → {new_noise_rms:.0f} (降低 {reduction:.0f}%)")
        
        return output_path
        
    except Exception as e:
        print(f"  ⚠ 降噪失敗：{e}")
        return input_path  # 失敗時返回原檔案

# ============================================================
# 處理函數
# ============================================================

def remove_duplicate_phrases(text: str) -> str:
    """
    移除重複的短語
    例如：「播放淚橋 播放淚橋」→「播放淚橋」
    例如：「播放 西野佳奈 播放西野佳奈」→「播放 西野佳奈」
    """
    if not text:
        return text
    
    original = text
    
    # 方法 1：檢查是否整句重複（移除空格後比較）
    text_no_space = text.replace(" ", "")
    half_len = len(text_no_space) // 2
    if half_len > 2:  # 至少 3 個字才檢查
        first_half = text_no_space[:half_len]
        second_half = text_no_space[half_len:half_len*2]
        if first_half == second_half:
            # 找到重複，返回原文的前半部分（保留空格）
            # 計算原文中對應的位置
            char_count = 0
            cut_pos = 0
            for i, c in enumerate(text):
                if c != ' ':
                    char_count += 1
                if char_count >= half_len:
                    cut_pos = i + 1
                    break
            result = text[:cut_pos].strip()
            print(f"  去重（整句）：「{original}」→「{result}」")
            return result
    
    # 方法 2：按空格分詞後檢查重複
    words = text.split()
    if len(words) >= 2:
        mid = len(words) // 2
        first_half = ' '.join(words[:mid])
        second_half = ' '.join(words[mid:2*mid])
        
        if first_half == second_half:
            print(f"  去重（分詞）：「{text}」→「{first_half}」")
            return first_half
    
    # 方法 3：檢查連續重複的詞組
    if len(words) >= 4:
        result = []
        i = 0
        while i < len(words):
            found_repeat = False
            for length in range(min(4, len(words) - i), 0, -1):
                if i + length * 2 <= len(words):
                    phrase = words[i:i+length]
                    next_phrase = words[i+length:i+length*2]
                    if phrase == next_phrase:
                        result.extend(phrase)
                        i += length * 2
                        found_repeat = True
                        break
            
            if not found_repeat:
                result.append(words[i])
                i += 1
        
        cleaned = ' '.join(result)
        if cleaned != text:
            print(f"  去重（詞組）：「{text}」→「{cleaned}」")
            return cleaned
    
    return text

def apply_corrections(text: str, correction_dicts: list) -> str:
    """套用修正表"""
    for corrections in correction_dicts:
        for wrong, correct in corrections.items():
            if wrong.lower() in text.lower():
                pattern = re.compile(re.escape(wrong), re.IGNORECASE)
                old_text = text
                text = pattern.sub(correct, text)
                if old_text != text:
                    print(f"  修正：{wrong} → {correct}")
    return text

def apply_overconversion_fix(text: str) -> str:
    """修正 OpenCC 過度轉換"""
    for wrong, correct in OVERCONVERSION_FIX.items():
        if wrong in text:
            text = text.replace(wrong, correct)
            print(f"  過度轉換修正：{wrong} → {correct}")
    return text

def process_transcription(text: str, language: str, mode: str = None) -> str:
    """處理識別結果"""
    original = text
    
    # 0. 🆕 先去除重複
    text = remove_duplicate_phrases(text)
    
    # 1. 簡轉繁
    if language == "zh" or any('\u4e00' <= c <= '\u9fff' for c in text):
        text = s2t_converter.convert(text)
    
    # 2. 修正 OpenCC 過度轉換
    text = apply_overconversion_fix(text)
    
    # 3. 如果有指定模式，套用該模式的修正表
    if mode and mode in MODE_CONFIG:
        config = MODE_CONFIG[mode]
        text = apply_corrections(text, config["corrections"])
    
    if text != original:
        print(f"  最終修正：{original} → {text}")
    
    return text

# ============================================================
# API 路由
# ============================================================

@app.get("/")
def read_root():
    return {
        "status": "ok",
        "message": "Whisper 語音識別服務運行中",
        "model": "large-v3",
        "device": "cuda:0",
        "available_modes": list(MODE_CONFIG.keys()) + ["(default: 僅簡轉繁)"],
    }

@app.post("/transcribe")
async def transcribe_audio(
    request: Request,
    audio: UploadFile = File(...),
    mode: str = Query(default=None, description="處理模式：spotify, drone, 或留空")
):
    """
    語音識別
    
    - mode=spotify: 音樂播放器模式（歌手、歌曲修正）
    - mode=drone: 無人機模式（指令修正）
    - mode=None: 預設模式（僅簡轉繁）
    """
    start_time = time.time()
    
    # 取得客戶端 IP（用於 log）
    client_ip = request.headers.get("X-Forwarded-For", request.client.host)
    if client_ip:
        client_ip = client_ip.split(",")[0].strip()
    
    # 判斷模式
    mode_name = MODE_CONFIG[mode]["name"] if mode in MODE_CONFIG else "預設（僅簡轉繁）"
    initial_prompt = MODE_CONFIG[mode]["initial_prompt"] if mode in MODE_CONFIG else None
    
    # 🆕 是否啟用降噪（從模式配置中獲取，預設啟用）
    enable_denoise = MODE_CONFIG[mode].get("denoise", True) if mode in MODE_CONFIG else False
    
    try:
        print(f"\n{'='*60}")
        print(f"📥 收到音頻：{audio.filename}")
        print(f"📍 來源 IP：{client_ip}")
        print(f"🎯 模式：{mode_name}")
        
        with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as temp_file:
            content = await audio.read()
            temp_file.write(content)
            temp_file_path = temp_file.name
        
        file_size = len(content) / 1024
        print(f"📊 文件大小：{file_size:.1f} KB")
        
        # 🆕 保存音檔供 debug（保留最近 20 個）
        debug_dir = "/tmp/whisper_debug"
        os.makedirs(debug_dir, exist_ok=True)
        debug_filename = f"{debug_dir}/{time.strftime('%Y%m%d_%H%M%S')}_{mode or 'default'}.wav"
        with open(debug_filename, 'wb') as f:
            f.write(content)
        print(f"💾 已保存音檔：{debug_filename}")
        
        # 清理舊檔案（只保留最近 20 個）
        debug_files = sorted([f for f in os.listdir(debug_dir) if f.endswith('.wav')])
        while len(debug_files) > 20:
            os.remove(os.path.join(debug_dir, debug_files.pop(0)))
        
        # 🆕 降噪處理
        if enable_denoise and NOISEREDUCE_AVAILABLE:
            denoised_path = temp_file_path + ".denoised.wav"
            processed_path = denoise_audio(temp_file_path, denoised_path, prop_decrease=0.7)
        else:
            processed_path = temp_file_path
        
        print("🎤 開始識別...")
        segments, info = whisper_model.transcribe(
            processed_path,  # 🆕 使用降噪後的音檔
            language=None,   # 🆕 不強制語言，讓 Whisper 自動檢測
            beam_size=5,
            best_of=5,
            temperature=0.0,
            vad_filter=True,
            vad_parameters=dict(
                min_silence_duration_ms=800,
                speech_pad_ms=200,
            ),
            condition_on_previous_text=False,
            initial_prompt=initial_prompt
        )
        
        detected_language = info.language
        language_prob = info.language_probability
        
        # 收集識別結果
        transcription = ""
        for segment in segments:
            transcription += segment.text.strip() + " "
        transcription = transcription.strip()
        
        # 🆕 如果檢測到韓文但信心度低（<60%），用中文重試
        if detected_language == "ko" and language_prob < 0.6 and mode == "spotify":
            print(f"⚠️ 韓文信心度低 ({language_prob:.1%})，用中文重試...")
            segments_retry, info_retry = whisper_model.transcribe(
                processed_path,
                language="zh",  # 強制中文
                beam_size=5,
                best_of=5,
                temperature=0.0,
                vad_filter=True,
                vad_parameters=dict(min_silence_duration_ms=800, speech_pad_ms=200),
                condition_on_previous_text=False,
                initial_prompt=initial_prompt
            )
            transcription_retry = ""
            for segment in segments_retry:
                transcription_retry += segment.text.strip() + " "
            transcription_retry = transcription_retry.strip()
            
            # 如果中文結果不為空，使用中文結果
            if transcription_retry:
                transcription = transcription_retry
                detected_language = "zh"
                language_prob = info_retry.language_probability
                print(f"  ✓ 中文重試結果：{transcription}")
        
        print(f"📝 原始識別：{transcription}")
        print(f"🌐 語言：{detected_language} ({language_prob:.1%})")
        
        # 處理識別結果
        original = transcription
        corrected = process_transcription(transcription, detected_language, mode)
        
        # 刪除臨時文件
        try:
            os.unlink(temp_file_path)
            # 🆕 也刪除降噪後的檔案
            if processed_path != temp_file_path:
                os.unlink(processed_path)
        except:
            pass
        
        elapsed = time.time() - start_time
        
        print(f"✅ 最終結果：{corrected}")
        print(f"⏱️  耗時：{elapsed:.2f}秒")
        print(f"{'='*60}\n")
        
        return {
            "text": corrected,
            "original": original,
            "language": detected_language,
            "language_probability": float(language_prob),
            "duration": elapsed,
            "mode": mode or "default",
        }
        
    except Exception as e:
        print(f"✗ 識別錯誤：{str(e)}")
        import traceback
        traceback.print_exc()
        return {
            "text": "",
            "error": str(e)
        }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8081)