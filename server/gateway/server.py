from fastapi import FastAPI, File, UploadFile
from pydantic import BaseModel
import requests
import json
import re
from ddgs import DDGS
from datetime import datetime

# Whisper 服務地址（RTX 3090 Server）
WHISPER_SERVER = "http://100.81.58.112:8081"

app = FastAPI()

INSTALLED_APPS = {}

class VoiceCommand(BaseModel):
    text: str
    installed_apps: dict = {}

@app.get("/")
def read_root():
    return {"status": "ok", "message": "語音助手 API 正常運作"}

def needs_web_search(text: str) -> bool:
    """判斷問題是否需要網路搜尋"""
    realtime_keywords = [
        '天氣', '氣溫', '溫度', '下雨', '颱風',
        '今天', '明天', '現在', '目前', '最近',
        '新聞', '消息', '發生', '事件',
        '股價', '匯率', '價格',
        '營業時間', '地址', '電話',
        '比賽', '賽事', '得分',
        '最新', '剛剛', '昨天'
    ]
    
    return any(keyword in text for keyword in realtime_keywords)

def web_search(query: str, max_results: int = 3) -> str:
    """使用 DuckDuckGo 搜尋"""
    try:
        print(f"🔍 正在搜尋：{query}")
        
        ddgs = DDGS()
        results = list(ddgs.text(query, max_results=max_results))
        
        if not results:
            return "搜尋無結果"
        
        search_summary = "搜尋結果：\n\n"
        for i, result in enumerate(results, 1):
            title = result.get('title', '')
            body = result.get('body', '')
            search_summary += f"{i}. {title}\n{body}\n\n"
        
        print(f"✓ 找到 {len(results)} 筆結果")
        return search_summary
        
    except Exception as e:
        print(f"搜尋錯誤：{str(e)}")
        return f"搜尋時發生錯誤：{str(e)}"

def is_spotify_command(text: str) -> bool:
    """判斷是否為 Spotify 音樂控制指令"""
    text_lower = text.lower()
    
    # 🆕 增強的播放關鍵詞檢測
    play_keywords = [
        '播放', '放', 'play', '聽', '听', 
        '來一首', '来一首', '我要聽', '我要听',
        '想聽', '想听', '給我', '给我'
    ]
    
    control_keywords = [
        '暫停', 'pause', '下一首', 'next', 
        '上一首', 'previous', '繼續播放', '停止播放'
    ]
    
    # 檢查是否包含播放或控制關鍵詞
    has_play = any(keyword in text_lower for keyword in play_keywords)
    has_control = any(keyword in text_lower for keyword in control_keywords)
    
    # 🆕 排除開啟播放器的情況
    if '播放器' in text or '播放軟體' in text or 'player' in text_lower:
        return False
    
    # 如果包含 spotify 關鍵字，一定是 Spotify 指令
    if 'spotify' in text_lower:
        return True
    
    return has_play or has_control

def extract_song_query(text: str) -> str:
    """
    智能提取歌曲搜尋字串
    
    支援格式：
    - "播放淚橋" → "淚橋"
    - "播放 A-Lin 的摯友" → "A-Lin 摯友"
    - "播放周杰倫的晴天" → "周杰倫 晴天"
    - "來一首五月天的倔強" → "五月天 倔強"
    - "聽 YOASOBI" → "YOASOBI"
    - "Play Shape of You" → "Shape of You"
    """
    import re
    
    # 檢查是否包含播放關鍵詞
    play_keywords = ['播放', 'play', '聽', '來一首', '放一首', '給我放', '我要聽', '想聽']
    has_play_keyword = any(kw in text.lower() for kw in play_keywords)
    
    if not has_play_keyword:
        return None
    
    # 移除播放相關的前綴詞
    song = text
    remove_prefixes = [
        '播放', 'play', '聽', '來一首', '放一首', '給我放', '我要聽', '想聽',
        '幫我播放', '幫我放', '請播放', '請放'
    ]
    for prefix in remove_prefixes:
        if song.lower().startswith(prefix.lower()):
            song = song[len(prefix):]
            break
        # 也處理中間有空格的情況
        song = re.sub(rf'^{re.escape(prefix)}\s*', '', song, flags=re.IGNORECASE)
    
    # 移除後綴詞
    remove_suffixes = ['的歌', '的歌曲', '的音樂', '歌曲', '音樂']
    for suffix in remove_suffixes:
        if song.endswith(suffix):
            song = song[:-len(suffix)]
    
    # 處理「歌手 的 歌名」格式 → 「歌手 歌名」
    # 例如：「A-Lin 的 摯友」→「A-Lin 摯友」
    song = re.sub(r'\s*的\s*', ' ', song)
    
    # 清理多餘空格
    song = ' '.join(song.split())
    song = song.strip()
    
    if not song:
        return None
    
    return song

def is_open_app_command(text: str) -> bool:
    """判斷是否為開啟 App 指令"""
    open_keywords = ['開', '打開', '啟動', '開啟', 'open', 'launch']
    return any(keyword in text for keyword in open_keywords)

@app.post("/transcribe")
async def transcribe_audio(audio: UploadFile = File(...)):
    """轉發到 Whisper 服務"""
    try:
        print(f"\n{'='*60}")
        print(f"收到音頻文件：{audio.filename}")
        
        content = await audio.read()
        files = {'audio': (audio.filename, content, audio.content_type)}
        
        response = requests.post(f"{WHISPER_SERVER}/transcribe?mode=spotify", files=files, timeout=30)
        
        if response.status_code == 200:
            result = response.json()
            print(f"✓ Whisper 識別：{result.get('text', '')}")
            print(f"語言：{result.get('language', 'unknown')}")
            print(f"{'='*60}\n")
            return result
        else:
            print(f"✗ Whisper 錯誤：{response.status_code}")
            return {"text": "", "error": f"Whisper 服務錯誤"}
            
    except Exception as e:
        print(f"✗ 轉發錯誤：{str(e)}")
        return {"text": "", "error": str(e)}

class CommandRequest(BaseModel):
    spoken_text: str
    installed_apps: dict

@app.post("/process_command")
async def process_command(command: VoiceCommand):
    print(f"\n{'='*60}")
    print(f"收到指令：{command.text}")
    
    # 更新已安裝的 App 列表
    if command.installed_apps:
        global INSTALLED_APPS
        INSTALLED_APPS = command.installed_apps
        print(f"更新 App 列表，共 {len(INSTALLED_APPS)} 個應用程式")
    
    # 🆕 優先判斷指令類型（改進順序）
    if is_spotify_command(command.text):
        print("🎵 識別為 Spotify 音樂控制指令")
        return await process_spotify_command(command.text)
    elif is_open_app_command(command.text):
        print("📱 識別為開啟應用程式指令")
        return await process_open_app_command(command.text)
    else:
        print("💬 識別為一般對話")
        return await process_conversation(command.text)

async def process_conversation(text: str):
    """處理一般對話 - 直接回答用戶問題"""
    
    print("正在生成回答...")
    
    search_results = ""
    if needs_web_search(text):
        print("📡 需要網路搜尋")
        search_results = web_search(text)
    
    current_time = datetime.now().strftime("%Y年%m月%d日 %H:%M")
    
    if search_results:
        prompt = f"""現在時間：{current_time}

用戶問題：{text}

網路搜尋結果：
{search_results}

請根據以上搜尋結果，用簡短的繁體中文回答用戶的問題（1-3 句話）。
如果搜尋結果中沒有相關資訊，請誠實說明。
"""
    else:
        prompt = f"""現在時間：{current_time}

用戶問題：{text}

請用簡短的繁體中文回答（1-3 句話）。
"""
    
    try:
        response = requests.post(
            "http://localhost:11434/api/generate",
            json={
                "model": "deepseek-r1:32b",
                "prompt": prompt,
                "stream": False,
                "system": """你是一個友善的台灣語音助手。

回答規則：
1. 使用繁體中文
2. 回答要簡短（1-3 句話），因為會用語音唸出來
3. 語氣要自然、友善
4. 如果有搜尋結果，請總結重點
5. 避免使用太複雜的詞彙
6. 不要提及「根據搜尋結果」，直接說答案

範例：
用戶：「台北今天天氣如何」
回答：「台北今天多雲,氣溫約 22 到 28 度，降雨機率 30%。」

用戶：「你好嗎」
回答：「我很好，謝謝！有什麼我可以幫你的嗎？」
""",
                "options": {
                    "temperature": 0.7,
                    "top_p": 0.9
                }
            },
            timeout=30
        )
        
        result = response.json()
        llm_response = result.get("response", "").strip()
        
        print(f"LLM 回答：{llm_response}")
        print(f"{'='*60}\n")
        
        return {
            "action": "speak",
            "message": llm_response
        }
        
    except Exception as e:
        print(f"錯誤：{str(e)}")
        return {
            "action": "speak",
            "message": "抱歉，我現在無法回答你的問題。"
        }

async def process_spotify_command(text: str):
    """處理 Spotify 音樂控制指令"""
    
    text_lower = text.lower().strip()
    
    # ==================== 快速規則匹配（不需要 LLM）====================
    
    # 1. 暫停
    if any(kw in text_lower for kw in ['暫停', 'pause', '停止播放', '停']):
        print("✓ 規則匹配：暫停")
        return {"action": "spotify_control", "command": "pause", "song": None}
    
    # 2. 下一首
    if any(kw in text_lower for kw in ['下一首', 'next', '換一首', '跳過']):
        print("✓ 規則匹配：下一首")
        return {"action": "spotify_control", "command": "next", "song": None}
    
    # 3. 上一首
    if any(kw in text_lower for kw in ['上一首', 'previous', '前一首']):
        print("✓ 規則匹配：上一首")
        return {"action": "spotify_control", "command": "previous", "song": None}
    
    # 4. 繼續播放（不指定歌曲）
    if text_lower in ['播放', '繼續', '繼續播放', 'play', 'resume', '播放音樂']:
        print("✓ 規則匹配：繼續播放")
        return {"action": "spotify_control", "command": "play", "song": None}
    
    # 5. 播放特定內容 - 智能提取歌曲/歌手
    song = extract_song_query(text)
    if song:
        print(f"✓ 規則匹配：播放 [{song}]")
        return {"action": "spotify_control", "command": "play", "song": song}
    
    # ==================== 如果規則匹配失敗，使用精簡版 LLM ====================
    print("規則匹配失敗，使用 LLM 分析...")
    
    prompt = f"""分析音樂指令：「{text}」

回傳 JSON：
- 播放歌曲: {{"action":"spotify_control","command":"play","song":"歌手 歌名"}}
- 暫停: {{"action":"spotify_control","command":"pause","song":null}}
- 下一首: {{"action":"spotify_control","command":"next","song":null}}
- 上一首: {{"action":"spotify_control","command":"previous","song":null}}

注意：如果有歌手和歌名，格式為「歌手 歌名」，例如「A-Lin 摯友」

只回傳 JSON："""

    try:
        response = requests.post(
            "http://localhost:11434/api/generate",
            json={
                "model": "qwen2.5:14b",
                "prompt": prompt,
                "stream": False,
                "system": "只回傳 JSON，不要其他文字。",
                "format": "json",
                "options": {
                    "temperature": 0.1,
                    "num_predict": 100
                }
            },
            timeout=30
        )
        
        result = response.json()
        llm_response = result.get("response", "").replace("```json", "").replace("```", "").strip()
        
        print(f"LLM 回應：{llm_response}")
        
        command_data = json.loads(llm_response)
        
        if "action" not in command_data:
            print("✗ 缺少 action 欄位")
            return {"action": "error", "message": "回應格式錯誤"}
        
        if command_data["action"] == "spotify_control":
            if "command" not in command_data:
                print("✗ 缺少 command 欄位")
                return {"action": "error", "message": "缺少 command"}
            if "song" not in command_data:
                command_data["song"] = None
            
            print(f"✓ Spotify 指令：{command_data['command']}, 歌曲：{command_data.get('song', 'null')}")
        
        print(f"{'='*60}\n")
        return command_data
        
    except json.JSONDecodeError as e:
        print(f"✗ JSON 解析錯誤：{str(e)}")
        print(f"原始回應：{llm_response}")
        return {"action": "error", "message": "無法解析 LLM 回應"}
    except Exception as e:
        print(f"✗ 錯誤：{str(e)}")
        import traceback
        traceback.print_exc()
        return {"action": "error", "message": f"處理失敗：{str(e)}"}

async def process_open_app_command(text: str):
    """處理開啟應用程式指令"""
    
    query_keywords = text.replace('開', '').replace('打開', '').replace('啟動', '').replace('開啟', '').replace('open', '').replace('launch', '').strip()
    print(f"關鍵字：{query_keywords}")
    
    relevant_apps = {}
    if query_keywords:
        for name, pkg in INSTALLED_APPS.items():
            if any(char in name for char in query_keywords):
                relevant_apps[name] = pkg
        
        print(f"根據關鍵字過濾後剩餘 {len(relevant_apps)} 個相關應用")
        
        if len(relevant_apps) > 50:
            relevant_apps = dict(list(relevant_apps.items())[:50])
    else:
        relevant_apps = dict(list(INSTALLED_APPS.items())[:50])
    
    if relevant_apps:
        print("過濾後的應用程式（前 10 個）：")
        for name in list(relevant_apps.keys())[:10]:
            print(f"  - {name}")
    
    prompt = f"""用戶說了：「{text}」

這是開啟應用程式的指令。

已安裝的相關應用程式（共 {len(relevant_apps)} 個）：
{json.dumps(relevant_apps, ensure_ascii=False, indent=2)}

請從列表中找到最匹配的應用程式並回傳：
{{"action": "open_app", "package": "套件名稱", "app_name": "應用名稱"}}

如果找不到，回傳：
{{"action": "unknown", "message": "找不到應用程式"}}

只回傳 JSON。
"""

    try:
        response = requests.post(
            "http://localhost:11434/api/generate",
            json={
                "model": "qwen2.5:14b",
                "prompt": prompt,
                "stream": False,
                "system": "你是應用程式啟動助手。只回傳 JSON 格式。",
                "format": "json",
                "options": {
                    "temperature": 0.2,
                    "top_p": 0.9
                }
            },
            timeout=30
        )
        
        result = response.json()
        llm_response = result.get("response", "").replace("```json", "").replace("```", "").strip()
        
        print(f"LLM 回應：{llm_response}")
        
        command_data = json.loads(llm_response)
        
        print(f"{'='*60}\n")
        return command_data
        
    except Exception as e:
        print(f"錯誤：{str(e)}")
        return {"action": "error", "message": f"處理失敗：{str(e)}"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)