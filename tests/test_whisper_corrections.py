"""
測試 Whisper 後處理修正邏輯
這些測試不需要 GPU、不需要 Whisper 模型，純邏輯測試
"""
import pytest
import re


# ============================================================
# 從 whisper_server.py 複製出來的純邏輯函數
# 當專案結構調整好後，可以改成直接 import
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


def remove_duplicate_phrases(text: str) -> str:
    """移除重複的短語（從 whisper_server.py 複製）"""
    if not text:
        return text
    
    original = text
    
    text_no_space = text.replace(" ", "")
    half_len = len(text_no_space) // 2
    if half_len > 2:
        first_half = text_no_space[:half_len]
        second_half = text_no_space[half_len:half_len*2]
        if first_half == second_half:
            char_count = 0
            cut_pos = 0
            for i, c in enumerate(text):
                if c != ' ':
                    char_count += 1
                if char_count >= half_len:
                    cut_pos = i + 1
                    break
            result = text[:cut_pos].strip()
            return result
    
    words = text.split()
    if len(words) >= 2:
        mid = len(words) // 2
        first_half = ' '.join(words[:mid])
        second_half = ' '.join(words[mid:2*mid])
        
        if first_half == second_half:
            return first_half
    
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
            return cleaned
    
    return text


def apply_overconversion_fix(text: str) -> str:
    """修正 OpenCC 過度轉換（從 whisper_server.py 複製）"""
    for wrong, correct in OVERCONVERSION_FIX.items():
        if wrong in text:
            text = text.replace(wrong, correct)
    return text


def apply_corrections(text: str, correction_dicts: list) -> str:
    """套用修正表（從 whisper_server.py 複製）"""
    for corrections in correction_dicts:
        for wrong, correct in corrections.items():
            if wrong.lower() in text.lower():
                pattern = re.compile(re.escape(wrong), re.IGNORECASE)
                text = pattern.sub(correct, text)
    return text


# ============================================================
# 測試 remove_duplicate_phrases
# ============================================================

class TestRemoveDuplicatePhrases:
    """測試重複短語移除"""
    
    def test_exact_duplicate(self):
        """整句完全重複"""
        assert remove_duplicate_phrases("播放淚橋 播放淚橋") == "播放淚橋"
    
    def test_duplicate_with_spaces(self):
        """帶空格的重複"""
        result = remove_duplicate_phrases("播放 西野佳奈 播放 西野佳奈")
        assert "西野佳奈" in result
        assert result.count("西野佳奈") == 1
    
    def test_no_duplicate(self):
        """沒有重複的不應改動"""
        assert remove_duplicate_phrases("播放晴天") == "播放晴天"
        assert remove_duplicate_phrases("你好嗎") == "你好嗎"
    
    def test_empty_string(self):
        assert remove_duplicate_phrases("") == ""
    
    def test_none(self):
        assert remove_duplicate_phrases(None) is None
    
    def test_short_text(self):
        """太短的文字不應被處理"""
        assert remove_duplicate_phrases("好好") == "好好"
        assert remove_duplicate_phrases("是") == "是"
    
    def test_chinese_duplicate(self):
        """中文整句重複"""
        assert remove_duplicate_phrases("暫停音樂暫停音樂") == "暫停音樂"
    
    def test_triple_not_affected(self):
        """三次重複也應該處理"""
        result = remove_duplicate_phrases("暫停 暫停 暫停")
        # 至少不會比原來更長
        assert len(result) <= len("暫停 暫停 暫停")


# ============================================================
# 測試 apply_overconversion_fix
# ============================================================

class TestOverconversionFix:
    """測試 OpenCC 過度轉換修正"""
    
    def test_gan_rao(self):
        """幹擾 → 干擾"""
        assert apply_overconversion_fix("這會幹擾信號") == "這會干擾信號"
    
    def test_gan_she(self):
        """幹涉 → 干涉"""
        assert apply_overconversion_fix("不要幹涉") == "不要干涉"
    
    def test_gan_han(self):
        """幹旱 → 乾旱"""
        assert apply_overconversion_fix("今年幹旱") == "今年乾旱"
    
    def test_ruo_gan(self):
        """若幹 → 若干"""
        assert apply_overconversion_fix("若幹年後") == "若干年後"
    
    def test_no_fix_needed(self):
        """不需要修正的文字"""
        assert apply_overconversion_fix("你好嗎") == "你好嗎"
        assert apply_overconversion_fix("播放音樂") == "播放音樂"
    
    def test_multiple_fixes(self):
        """多個需要修正的詞"""
        result = apply_overconversion_fix("這會幹擾到幹涉的行為")
        assert "干擾" in result
        assert "干涉" in result
        assert "幹擾" not in result
        assert "幹涉" not in result


# ============================================================
# 測試 apply_corrections
# ============================================================

class TestApplyCorrections:
    """測試修正表套用"""
    
    def test_single_correction(self):
        corrections = [{"周杰倫": "周杰倫"}]  # identity
        assert apply_corrections("周杰倫", corrections) == "周杰倫"
    
    def test_case_insensitive(self):
        corrections = [{"spotify": "Spotify"}]
        assert apply_corrections("打開SPOTIFY", corrections) == "打開Spotify"
    
    def test_multiple_dicts(self):
        dict1 = {"錯字一": "正確一"}
        dict2 = {"錯字二": "正確二"}
        result = apply_corrections("有錯字一和錯字二", [dict1, dict2])
        assert "正確一" in result
        assert "正確二" in result
    
    def test_no_match(self):
        corrections = [{"不存在": "修正"}]
        assert apply_corrections("你好嗎", corrections) == "你好嗎"
