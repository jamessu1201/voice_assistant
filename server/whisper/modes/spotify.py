# modes/spotify.py
# 🎵 Spotify 音樂播放器模式

HOMOPHONES = {
    # 中英混雜常見錯誤
    "both are": "播放",
    "boss are": "播放",
    "both of": "播放",
    "bought": "播放",
    "ball": "播放",
    "both our": "播放",
    "bother": "播放",
    
    # 歌曲名稱 - 淚橋（常見誤識別）
    "类桥": "淚橋",
    "泪桥": "淚橋",
    "類橋": "淚橋",
    "類場": "淚橋",
    "淚場": "淚橋",
    "雷橋": "淚橋",
    "雷場": "淚橋",
    "累橋": "淚橋",
    "累場": "淚橋",
    "淚喬": "淚橋",
    "類喬": "淚橋",
    
    # 🆕 西野加奈誤識別
    "昔夜加 night": "西野加奈",
    "昔夜加": "西野加奈",
    "昔野加奈": "西野加奈",
    "析野加奈": "西野加奈",
    
    # 日文歌手
    "悠阿搜比": "YOASOBI",
    "尤阿搜比": "YOASOBI",
    "有阿搜比": "YOASOBI",
    "yo a so bi": "YOASOBI",
    
    # 英文歌曲（保持原樣）
    "never gonna give you up": "Never Gonna Give You Up",
    "shape of you": "Shape of You",
    "blinding lights": "Blinding Lights",
    
    # 歌手 - 簡轉繁
    "周杰伦": "周杰倫",
    "林俊杰": "林俊傑",
    "邓紫棋": "鄧紫棋",
    "张学友": "張學友",
    "陈奕迅": "陳奕迅",
    "蔡依林": "蔡依林",
    "张惠妹": "張惠妹",
}

ARTIST_CORRECTIONS = {
    # A-Lin 的各種誤識別
    "阿令": "A-Lin",
    "阿玲": "A-Lin",
    "阿林": "A-Lin",
    "啊令": "A-Lin",
    "啊玲": "A-Lin",
    "啊林": "A-Lin",
    "亞林": "A-Lin",
    "a lin": "A-Lin",
    "a-lin": "A-Lin",
    "alin": "A-Lin",
    "艾琳": "A-Lin",
    "阿琳": "A-Lin",

   
    
    # 周興哲
    "周星喆": "周興哲",
    "周星哲": "周興哲",
    "周興喆": "周興哲",
    "周星澤": "周興哲",
    
    # 五月天
    "5月天": "五月天",
    "伍月天": "五月天",
    
    # G.E.M. 鄧紫棋
    "gem": "G.E.M.",
    "GEM": "G.E.M.",
    "鄧子琪": "鄧紫棋",
    "鄧子棋": "鄧紫棋",
    
    # 蔡依林
    "菜依林": "蔡依林",
    "蔡一林": "蔡依林",
    
    # 周杰倫
    "周結倫": "周杰倫",
    "周杰論": "周杰倫",
    "週杰倫": "周杰倫",
    
    # 林俊傑
    "林俊杰": "林俊傑",
    "林竣傑": "林俊傑",
    
    # 張學友
    "張學有": "張學友",
    "章學友": "張學友",
    
    # 陳奕迅
    "陳奕訊": "陳奕迅",
    "陳亦迅": "陳奕迅",
    
    # 張惠妹 / 阿妹
    "張慧妹": "張惠妹",
    "張惠美": "張惠妹",
    "阿妹": "張惠妹",
    "阿mei": "張惠妹",
    
    # 蕭敬騰
    "蕭敬滕": "蕭敬騰",
    "老蕭": "蕭敬騰",
    
    # 田馥甄 / Hebe
    "田復甄": "田馥甄",
    "田馥真": "田馥甄",
    "hebe": "田馥甄",
    "Hebe": "田馥甄",
    
    # 李榮浩
    "李榮皓": "李榮浩",
    "李容浩": "李榮浩",
    
    # 韋禮安
    "偉禮安": "韋禮安",
    "威禮安": "韋禮安",
    
    # 盧廣仲
    "盧廣中": "盧廣仲",
    "盧光仲": "盧廣仲",
    
    # 茄子蛋
    "茄子但": "茄子蛋",
    "茄子彈": "茄子蛋",
    
    # 告五人
    "告5人": "告五人",
    "高五人": "告五人",
    
    # 八三夭
    "八三么": "八三夭",
    "831": "八三夭",
    "883": "八三夭",
    
    # 伍佰
    "五百": "伍佰",
    "500": "伍佰",
    
    # 草東沒有派對
    "草東沒有party": "草東沒有派對",
    
    # YOASOBI
    "夜遊": "YOASOBI",
    
    # Ed Sheeran
    "乙神": "Ed Sheeran",
    "艾德乙神": "Ed Sheeran",
    "ed sheeran": "Ed Sheeran",
    
    # Taylor Swift
    "泰勒斯": "Taylor Swift",
    "泰勒絲": "Taylor Swift",
    "taylor swift": "Taylor Swift",
    
    # Bruno Mars
    "布魯諾瑪斯": "Bruno Mars",
    "火星人布魯諾": "Bruno Mars",
    "bruno mars": "Bruno Mars",
    
    # Coldplay
    "酷玩": "Coldplay",
    "coldplay": "Coldplay",
    
    # Maroon 5
    "魔力紅": "Maroon 5",
    "maroon 5": "Maroon 5",
    "maroon5": "Maroon 5",
}

ENGLISH_CORRECTIONS = {
    # Lydia - F.I.R. 飛兒樂團的歌
    "莉迪亞": "Lydia",
    "利迪亞": "Lydia",
    "麗迪亞": "Lydia",
    "lydia": "Lydia",
    
    # 常見英文歌曲
    "shape of you": "Shape of You",
    "ed sheeran": "Ed Sheeran",
    "perfect": "Perfect",
    "thinking out loud": "Thinking Out Loud",
    
    # Taylor Swift 歌曲
    "love story": "Love Story",
    "shake it off": "Shake It Off",
    "blank space": "Blank Space",
    "anti hero": "Anti-Hero",
    
    # 其他熱門英文歌
    "someone like you": "Someone Like You",
    "hello": "Hello",
    "rolling in the deep": "Rolling in the Deep",
    "despacito": "Despacito",
    "uptown funk": "Uptown Funk",
    "happy": "Happy",
    "counting stars": "Counting Stars",
    "radioactive": "Radioactive",
    "believer": "Believer",
    "thunder": "Thunder",
    "demons": "Demons",
    "stressed out": "Stressed Out",
    "heathens": "Heathens",
    "faded": "Faded",
    "alone": "Alone",
    "closer": "Closer",
    "something just like this": "Something Just Like This",
    "let me love you": "Let Me Love You",
    "stay": "Stay",
    "havana": "Havana",
    "senorita": "Señorita",
    "bad guy": "Bad Guy",
    "lovely": "Lovely",
    "dance monkey": "Dance Monkey",
    "blinding lights": "Blinding Lights",
    "watermelon sugar": "Watermelon Sugar",
    "levitating": "Levitating",
    "drivers license": "Drivers License",
    "good 4 u": "Good 4 U",
    "easy on me": "Easy on Me",
    "as it was": "As It Was",
    "anti hero": "Anti-Hero",
    "unholy": "Unholy",
    "flowers": "Flowers",
    "kill bill": "Kill Bill",
}

JAPANESE_CORRECTIONS = {
    # ========== YOASOBI ==========
    "悠阿搜比": "YOASOBI",
    "尤阿搜比": "YOASOBI",
    "有阿搜比": "YOASOBI",
    "夜遊": "YOASOBI",
    "yo a so bi": "YOASOBI",
    "yoasobi": "YOASOBI",
    "夜に駆ける": "夜に駆ける",
    "夜裡奔馳": "夜に駆ける",
    "yorunikakeru": "夜に駆ける",
    "racing into the night": "夜に駆ける",
    "アイドル": "アイドル",
    "愛抖露": "アイドル",
    "idol": "Idol",
    "群青": "群青",
    "gunjo": "群青",
    "怪物": "怪物",
    
    # ========== Ado ==========
    "阿多": "Ado",
    "阿朵": "Ado",
    "ado": "Ado",
    "usseewa": "うっせぇわ",
    "踊": "踊",
    "唱": "唱",
    "新時代": "新時代",
    "逆光": "逆光",
    
    # ========== 米津玄師 ==========
    "米津玄師": "米津玄師",
    "米津元帥": "米津玄師",
    "米津": "米津玄師",
    "kenshi yonezu": "米津玄師",
    "yonezu kenshi": "米津玄師",
    "lemon": "Lemon",
    "檸檬": "Lemon",
    "打上花火": "打上花火",
    "感電": "感電",
    "kick back": "KICK BACK",
    "kickback": "KICK BACK",
    
    # ========== LiSA ==========
    "lisa": "LiSA",
    "麗莎": "LiSA",
    "莉莎": "LiSA",
    "紅蓮華": "紅蓮華",
    "紅蓮花": "紅蓮華",
    "gurenge": "紅蓮華",
    "炎": "炎",
    "homura": "炎",
    "明け星": "明け星",
    "unlasting": "unlasting",
    
    # ========== Official髭男dism ==========
    "髭男": "Official髭男dism",
    "鬍子男": "Official髭男dism",
    "official髭男dism": "Official髭男dism",
    "higedan": "Official髭男dism",
    "pretender": "Pretender",
    "cry baby": "Cry Baby",
    "subtitle": "Subtitle",
    "宿命": "宿命",
    
    # ========== King Gnu ==========
    "king gnu": "King Gnu",
    "kinggnu": "King Gnu",
    "白日": "白日",
    "hakujitsu": "白日",
    "一途": "一途",
    "逆夢": "逆夢",
    "雨燦々": "雨燦々",
    
    # ========== back number ==========
    "back number": "back number",
    "backnumber": "back number",
    "高嶺の花子さん": "高嶺の花子さん",
    "水平線": "水平線",
    "クリスマスソング": "クリスマスソング",
    "ハッピーエンド": "ハッピーエンド",
    
    # ========== あいみょん (Aimyon) ==========
    "愛繆": "あいみょん",
    "aimyon": "あいみょん",
    "マリーゴールド": "マリーゴールド",
    "marigold": "マリーゴールド",
    "裸の心": "裸の心",
    "愛を伝えたいだとか": "愛を伝えたいだとか",
    
    # ========== 宇多田光 ==========
    "宇多田光": "宇多田ヒカル",
    "宇多田": "宇多田ヒカル",
    "utada hikaru": "宇多田ヒカル",
    "hikaru utada": "宇多田ヒカル",
    "first love": "First Love",
    "初戀": "First Love",
    "one last kiss": "One Last Kiss",
    "beautiful world": "Beautiful World",
    
    # ========== RADWIMPS ==========
    "radwimps": "RADWIMPS",
    "前前前世": "前前前世",
    "zenzenzense": "前前前世",
    "スパークル": "スパークル",
    "sparkle": "スパークル",
    "なんでもないや": "なんでもないや",
    "夢灯籠": "夢灯籠",
    
    # ========== Mrs. GREEN APPLE ==========
    "mrs green apple": "Mrs. GREEN APPLE",
    "mrs. green apple": "Mrs. GREEN APPLE",
    "綠蘋果": "Mrs. GREEN APPLE",
    "青と夏": "青と夏",
    "インフェルノ": "インフェルノ",
    "ダンスホール": "ダンスホール",
    
    # ========== Aimer ==========
    "aimer": "Aimer",
    "艾莫": "Aimer",
    "残響散歌": "残響散歌",
    "カタオモイ": "カタオモイ",
    "蝶々結び": "蝶々結び",
    
    # ========== Eve ==========
    "eve": "Eve",
    "廻廻奇譚": "廻廻奇譚",
    "kaikai kitan": "廻廻奇譚",
    "ドラマツルギー": "ドラマツルギー",
    
    # ========== 藤井風 ==========
    "藤井風": "藤井風",
    "fujii kaze": "藤井風",
    "死ぬのがいいわ": "死ぬのがいいわ",
    "shinunogaiiwai": "死ぬのがいいわ",
    "きらり": "きらり",
    "まつり": "まつり",
    
    # ========== 西野カナ (Kana Nishino) ==========
    "西野佳奈": "西野カナ",
    "西野加奈": "西野カナ",
    "西野香奈": "西野カナ",
    "昔夜加": "西野加奈",     # 🆕 常見誤識別
    "昔野加奈": "西野加奈",   # 🆕
    "析野加奈": "西野加奈",   # 🆕
    "西野佳": "西野カナ",     # 🆕
    "kana nishino": "西野カナ",
    "nishino kana": "西野カナ",
    "if": "if",
    "会いたくて 会いたくて": "会いたくて 会いたくて",
    "トリセツ": "トリセツ",
    "darling": "Darling",
    
    # ========== ずっと真夜中でいいのに。(ZUTOMAYO) ==========
    "zutomayo": "ずっと真夜中でいいのに。",
    "秒針を噛む": "秒針を噛む",
    "正しくなれない": "正しくなれない",
    
    # ========== ONE OK ROCK ==========
    "one ok rock": "ONE OK ROCK",
    "oneokrock": "ONE OK ROCK",
    "萬ok搖滾": "ONE OK ROCK",
    "wherever you are": "Wherever You Are",
    "完全感覚dreamer": "完全感覚Dreamer",
    
    # ========== 其他日本歌手 ==========
    "perfume": "Perfume",
    "bump of chicken": "BUMP OF CHICKEN",
    "天体観測": "天体観測",
    "spitz": "スピッツ",
    "チェリー": "チェリー",
    "cherry": "チェリー",
    "ロビンソン": "ロビンソン",
    "mr children": "Mr.Children",
    "mr.children": "Mr.Children",
    "ミスチル": "Mr.Children",
    "星野源": "星野源",
    "gen hoshino": "星野源",
    "恋": "恋",
    "koi": "恋",
    "ドラえもん": "ドラえもん",
    "すずめ": "すずめ",
    "suzume": "すずめ",
    "カナタハルカ": "カナタハルカ",
}

# 匯出配置
CONFIG = {
    "name": "Spotify 音樂播放器",
    "initial_prompt": "播放 play 暫停 pause 下一首 next 上一首 previous 周杰倫 五月天 林俊傑 西野加奈 A-Lin 淚橋",
    "denoise": True,  # 🆕 啟用降噪
    "corrections": [
        HOMOPHONES,
        ARTIST_CORRECTIONS,
        ENGLISH_CORRECTIONS,
        JAPANESE_CORRECTIONS,
    ],
}