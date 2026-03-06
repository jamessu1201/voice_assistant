import sys
import os

# 讓 tests 能 import server 的 code
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'server', 'gateway'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'server', 'whisper'))
