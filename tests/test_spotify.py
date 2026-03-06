"""
測試 Spotify 指令解析邏輯
這些測試不需要 GPU、不需要網路，純邏輯測試
"""
import pytest
import sys
import os

# 直接 import gateway 的純邏輯函數
# 因為 app.py 頂層會 import ddgs 等套件，這邊用 mock 處理
from unittest.mock import MagicMock

# Mock 掉不需要的模組，讓 import 不會失敗
sys.modules.setdefault('ddgs', MagicMock())
sys.modules.setdefault('fastapi', MagicMock())
sys.modules.setdefault('pydantic', MagicMock())

# 為了避免 import app.py 時觸發 FastAPI 初始化，
# 我們直接把要測試的純函數複製出來。
# 當 app.py 結構調整後，可以改成直接 import。

import re

def is_spotify_command(text: str) -> bool:
    """判斷是否為 Spotify 音樂控制指令（從 app.py 複製）"""
    text_lower = text.lower()
    
    play_keywords = [
        '播放', '放', 'play', '聽', '听',
        '來一首', '来一首', '我要聽', '我要听',
        '想聽', '想听', '給我', '给我'
    ]
    
    control_keywords = [
        '暫停', 'pause', '下一首', 'next',
        '上一首', 'previous', '繼續播放', '停止播放'
    ]
    
    has_play = any(keyword in text_lower for keyword in play_keywords)
    has_control = any(keyword in text_lower for keyword in control_keywords)
    
    if '播放器' in text or '播放軟體' in text or 'player' in text_lower:
        return False
    
    if 'spotify' in text_lower:
        return True
    
    return has_play or has_control


def extract_song_query(text: str) -> str:
    """智能提取歌曲搜尋字串（從 app.py 複製）"""
    play_keywords = ['播放', 'play', '聽', '來一首', '放一首', '給我放', '我要聽', '想聽']
    has_play_keyword = any(kw in text.lower() for kw in play_keywords)
    
    if not has_play_keyword:
        return None
    
    song = text
    remove_prefixes = [
        '播放', 'play', '聽', '來一首', '放一首', '給我放', '我要聽', '想聽',
        '幫我播放', '幫我放', '請播放', '請放'
    ]
    for prefix in remove_prefixes:
        if song.lower().startswith(prefix.lower()):
            song = song[len(prefix):]
            break
        song = re.sub(rf'^{re.escape(prefix)}\s*', '', song, flags=re.IGNORECASE)
    
    remove_suffixes = ['的歌', '的歌曲', '的音樂', '歌曲', '音樂']
    for suffix in remove_suffixes:
        if song.endswith(suffix):
            song = song[:-len(suffix)]
    
    song = re.sub(r'\s*的\s*', ' ', song)
    song = ' '.join(song.split())
    song = song.strip()
    
    if not song:
        return None
    
    return song


def needs_web_search(text: str) -> bool:
    """判斷問題是否需要網路搜尋（從 app.py 複製）"""
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


# ============================================================
# 測試 is_spotify_command
# ============================================================

class TestIsSpotifyCommand:
    """測試 Spotify 指令判斷"""
    
    def test_play_keywords(self):
        assert is_spotify_command("播放晴天") == True
        assert is_spotify_command("放一首歌") == True
        assert is_spotify_command("聽周杰倫") == True
        assert is_spotify_command("來一首五月天的倔強") == True
        assert is_spotify_command("我要聽音樂") == True
        assert is_spotify_command("想聽 YOASOBI") == True
    
    def test_control_keywords(self):
        assert is_spotify_command("暫停") == True
        assert is_spotify_command("下一首") == True
        assert is_spotify_command("上一首") == True
        assert is_spotify_command("繼續播放") == True
    
    def test_spotify_keyword(self):
        assert is_spotify_command("打開 Spotify") == True
        assert is_spotify_command("用spotify放歌") == True
    
    def test_exclude_player_app(self):
        """「播放器」不應被判定為 Spotify 指令"""
        assert is_spotify_command("打開播放器") == False
        assert is_spotify_command("開啟播放軟體") == False
        assert is_spotify_command("open music player") == False
    
    def test_non_spotify(self):
        assert is_spotify_command("今天天氣如何") == False
        assert is_spotify_command("你好嗎") == False
        assert is_spotify_command("打開 Google Maps") == False


# ============================================================
# 測試 extract_song_query
# ============================================================

class TestExtractSongQuery:
    """測試歌曲提取"""
    
    def test_basic_play(self):
        assert extract_song_query("播放晴天") == "晴天"
        assert extract_song_query("播放淚橋") == "淚橋"
    
    def test_artist_and_song(self):
        assert extract_song_query("播放周杰倫的晴天") == "周杰倫 晴天"
        assert extract_song_query("播放 A-Lin 的摯友") == "A-Lin 摯友"
        assert extract_song_query("來一首五月天的倔強") == "五月天 倔強"
    
    def test_artist_only(self):
        assert extract_song_query("聽 YOASOBI") == "YOASOBI"
        assert extract_song_query("播放周杰倫") == "周杰倫"
    
    def test_english_song(self):
        result = extract_song_query("Play Shape of You")
        assert result is not None
        assert "Shape of You" in result
    
    def test_remove_suffix(self):
        assert extract_song_query("播放周杰倫的歌") == "周杰倫"
        assert extract_song_query("播放一些音樂") is not None
    
    def test_no_play_keyword(self):
        """沒有播放關鍵詞的不應提取"""
        assert extract_song_query("今天天氣如何") is None
        assert extract_song_query("打開地圖") is None
    
    def test_complex_song_names(self):
        """複雜歌名測試"""
        result = extract_song_query("播放告五人的披星戴月的想你")
        assert result is not None
        assert "告五人" in result
        # 注意：「的」會被替換成空格，所以結果可能是「告五人 披星戴月 想你」
    
    def test_polite_prefix(self):
        assert extract_song_query("幫我播放晴天") == "晴天"
        assert extract_song_query("請播放晴天") == "晴天"


# ============================================================
# 測試 needs_web_search
# ============================================================

class TestNeedsWebSearch:
    """測試網路搜尋判斷"""
    
    def test_weather(self):
        assert needs_web_search("今天天氣如何") == True
        assert needs_web_search("明天會下雨嗎") == True
        assert needs_web_search("台北氣溫幾度") == True
    
    def test_news(self):
        assert needs_web_search("最近有什麼新聞") == True
        assert needs_web_search("最新消息") == True
    
    def test_price(self):
        assert needs_web_search("台積電股價") == True
        assert needs_web_search("美金匯率") == True
    
    def test_no_search_needed(self):
        assert needs_web_search("你好嗎") == False
        assert needs_web_search("幫我算 1+1") == False
        assert needs_web_search("什麼是人工智慧") == False
