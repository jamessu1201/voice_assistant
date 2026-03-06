# modes/__init__.py
# 自動載入所有模式配置

from . import spotify
from . import drone

# 模式配置字典
MODE_CONFIG = {
    "spotify": spotify.CONFIG,
    "drone": drone.CONFIG,
}

# 要新增模式：
# 1. 在 modes/ 資料夾建立新檔案，例如 smarthome.py
# 2. 在新檔案中定義 CONFIG = { "name": ..., "initial_prompt": ..., "corrections": [...] }
# 3. 在這裡 import 並加到 MODE_CONFIG