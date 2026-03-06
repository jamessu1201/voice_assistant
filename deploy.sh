#!/bin/bash
# deploy.sh - 將更新部署到各伺服器
#
# 用法：
#   ./deploy.sh           # 部署全部（push git + 同步 3090）
#   ./deploy.sh whisper   # 只同步 Whisper server 到 3090
#   ./deploy.sh gateway   # 只重啟 4090 的 Gateway server

set -e

# ============================================================
# 設定（請根據實際環境修改）
# ============================================================
WHISPER_HOST="100.81.58.112"          # 3090 的 Tailscale IP
WHISPER_USER="$USER"                   # 3090 的使用者名稱，預設同當前用戶
WHISPER_DIR="~/voice-assistant"        # 3090 上的專案路徑
WHISPER_RESTART_CMD="systemctl --user restart whisper"  # 取消註解並修改為你的重啟方式

# ============================================================
# 顏色
# ============================================================
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# ============================================================
# 函數
# ============================================================

deploy_whisper() {
    echo -e "${YELLOW}📡 同步 Whisper Server 到 3090...${NC}"
    
    # 方式一：透過 Git（推薦）
    echo "  推送到 Git..."
    git add -A
    git diff --cached --quiet || git commit -m "update whisper server"
    git push
    
    echo "  在 3090 上 pull..."
    ssh joan3825@100.81.58.112 "cd /mnt/home/joan3825/code/voice_assistant && git pull && sudo systemctl restart whisper-server"
    
    # 方式二：直接 rsync（如果不想用 Git）
    # rsync -avz --delete ./server/whisper/ ${WHISPER_USER}@${WHISPER_HOST}:${WHISPER_DIR}/server/whisper/
    
    # 重啟服務（取消註解並修改為你的重啟方式）
    # echo "  重啟 Whisper Server..."
    # ssh ${WHISPER_USER}@${WHISPER_HOST} "${WHISPER_RESTART_CMD}"
    
    echo -e "${GREEN}✅ Whisper Server 已更新${NC}"
    echo -e "${YELLOW}⚠️  請手動重啟 3090 上的 Whisper Server${NC}"
}

deploy_gateway() {
    echo -e "${YELLOW}🔄 重啟 Gateway Server...${NC}"
    
    # 取消註解並修改為你的重啟方式
    # systemctl --user restart gateway
    
    echo -e "${GREEN}✅ Gateway Server 已更新${NC}"
    echo -e "${YELLOW}⚠️  請手動重啟 Gateway Server${NC}"
}

run_tests() {
    echo -e "${YELLOW}🧪 執行測試...${NC}"
    pytest tests/ -v
    echo -e "${GREEN}✅ 測試通過${NC}"
}

# ============================================================
# 主程式
# ============================================================

case "${1:-all}" in
    whisper)
        run_tests
        deploy_whisper
        ;;
    gateway)
        run_tests
        deploy_gateway
        ;;
    all)
        run_tests
        
        # Git commit & push
        echo -e "${YELLOW}📦 提交到 Git...${NC}"
        git add -A
        git diff --cached --quiet || git commit -m "update: $(date '+%Y-%m-%d %H:%M')"
        git push
        
        deploy_whisper
        deploy_gateway
        ;;
    test)
        run_tests
        ;;
    *)
        echo "用法: ./deploy.sh [whisper|gateway|all|test]"
        exit 1
        ;;
esac

echo ""
echo -e "${GREEN}🎉 部署完成！${NC}"
