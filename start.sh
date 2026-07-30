#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# start.sh — Ollama 图片筛选器 一键启动脚本 (Java B/S)
# ═══════════════════════════════════════════════════════════════════════
# 功能:
#   1. 自动检测 Java 21+
#   2. 自动检测/下载 Maven (无需手动安装)
#   3. 自动检测 Ollama 服务状态
#   4. 编译并启动 Spring Boot 应用
# ═══════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── 配置 ──────────────────────────────────────────────────────────────
MAVEN_VERSION="3.9.9"
MAVEN_DIR="$HOME/.mvn-${MAVEN_VERSION}"
MAVEN_BIN="${MAVEN_DIR}/bin/mvn"

# 镜像优先级: 阿里云 → 华为云 → 腾讯云 → 官方
MAVEN_MIRRORS=(
    "https://mirrors.aliyun.com/apache/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    "https://mirrors.huaweicloud.com/apache/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    "https://mirrors.cloud.tencent.com/apache/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
)

# 默认参数
IMG_DIR="${IMG_DIR:-./img}"
PORT="${PORT:-8080}"
OLLAMA_HOST="${OLLAMA_HOST:-localhost:11434}"

# ── 颜色 ──────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m' # No Color

info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

banner() {
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}       ${GREEN}🖼️  Ollama 图片智能筛选器 — Java B/S${NC}          ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# ── Step 1: 检测 Java ─────────────────────────────────────────────────
check_java() {
    info "检测 Java 运行环境..."
    if ! command -v java &>/dev/null; then
        error "未找到 Java，请安装 JDK 21+:"
        echo "  sudo apt install openjdk-21-jdk"
        echo "  或: sdk install java 21.0.4-tem  (https://sdkman.io)"
        exit 1
    fi

    # 优先使用 Java 21（Maven compiler plugin 需要匹配的 javac）
    # 较新 JDK 的 javac 可能不支持 --release 21
    local java21_home="/usr/lib/jvm/java-21-openjdk-amd64"
    local java21_bin="${java21_home}/bin/java"
    if [ -x "$java21_bin" ]; then
        export JAVA_HOME="$java21_home"
        export PATH="${java21_home}/bin:${PATH}"
        success "Java 21 (JAVA_HOME=${java21_home})"
    else
        # 回退：在常见路径查找 Java 21
        local found21
        found21=$(find /usr/lib/jvm -maxdepth 2 -name java -path "*/21*/bin/java" 2>/dev/null | head -1)
        if [ -n "$found21" ]; then
            export JAVA_HOME="$(dirname "$(dirname "$found21")")"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            success "Java 21 (JAVA_HOME=${JAVA_HOME})"
        else
            warn "未找到 Java 21，使用默认 Java。如编译失败请安装: sudo apt install openjdk-21-jdk"
            local ver
            ver=$(java -version 2>&1 | head -1 | grep -oP '\d+\.\d+\.\d+' | cut -d. -f1 || echo "0")
            if [ "$ver" -lt 21 ]; then
                warn "当前 Java $ver，推荐 Java 21+"
            fi
        fi
    fi
}

# ── Step 2: 检测/下载 Maven ───────────────────────────────────────────
setup_maven() {
    info "检测 Maven 构建工具..."

    # 优先使用系统 mvn
    if command -v mvn &>/dev/null; then
        success "Maven 已安装: $(mvn --version 2>&1 | head -1)"
        MAVEN_CMD="mvn"
        return
    fi

    # 检查本地缓存的 Maven
    if [ -x "${MAVEN_BIN}" ]; then
        success "使用本地 Maven: ${MAVEN_DIR}"
        MAVEN_CMD="${MAVEN_BIN}"
        return
    fi

    # 尝试下载 Maven
    warn "Maven 未安装，正在自动下载 ${MAVEN_VERSION}..."
    local downloaded=false
    for mirror in "${MAVEN_MIRRORS[@]}"; do
        info "尝试: ${mirror}"
        if curl -fsSL --connect-timeout 10 --max-time 120 \
                -o /tmp/apache-maven.tar.gz "$mirror" 2>/dev/null; then
            downloaded=true
            break
        fi
    done

    if ! $downloaded; then
        error "无法下载 Maven。请手动安装: sudo apt install maven"
        echo "  或从 https://maven.apache.org/download.cgi 下载后解压到 ${MAVEN_DIR}"
        exit 1
    fi

    info "解压 Maven 到 ${MAVEN_DIR} ..."
    mkdir -p "${MAVEN_DIR}"
    tar -xzf /tmp/apache-maven.tar.gz -C "${MAVEN_DIR}" --strip-components=1
    rm -f /tmp/apache-maven.tar.gz

    if [ ! -x "${MAVEN_BIN}" ]; then
        error "Maven 安装失败"
        exit 1
    fi
    success "Maven ${MAVEN_VERSION} 安装完成"
    MAVEN_CMD="${MAVEN_BIN}"
}

# ── Step 3: 检测/启动 Ollama ──────────────────────────────────────────
# 辅助函数：用 wget 检测 Ollama API 是否可达
_ollama_api_get() {
    local url="$1"
    wget -qO- --timeout=5 "$url" 2>/dev/null
}

# 辅助函数：解析 JSON 中的模型数量
_ollama_count_models() {
    if command -v jq &>/dev/null; then
        echo "$1" | jq '.models | length' 2>/dev/null || echo "?"
    elif command -v python3 &>/dev/null && python3 -c "import json" 2>/dev/null; then
        echo "$1" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('models',[])))" 2>/dev/null || echo "?"
    else
        # 最后手段：grep 匹配 "name" 字段
        echo "$1" | grep -oP '"name"\s*:' 2>/dev/null | wc -l || echo "?"
    fi
}

check_ollama() {
    info "检测 Ollama 服务 (${OLLAMA_HOST})..."
    local resp

    # 先检查是否已在运行
    resp=$(_ollama_api_get "http://${OLLAMA_HOST}/api/tags")
    if [ -n "$resp" ]; then
        local count
        count=$(_ollama_count_models "$resp")
        success "Ollama 已连接，${count} 个模型可用"
        echo ""
        return
    fi

    # 未运行 — 尝试自动后台启动
    if ! command -v ollama &>/dev/null; then
        warn "Ollama 未安装。请先安装: curl -fsSL https://ollama.com/install.sh | sh"
        echo ""
        return
    fi

    warn "Ollama 未运行，正在后台自动启动..."
    # 启动 ollama serve 并将日志写入临时文件
    local ollama_log="/tmp/ollama-serve-$(date +%Y%m%d-%H%M%S).log"
    nohup ollama serve >"$ollama_log" 2>&1 &
    local ollama_pid=$!

    # 等待 Ollama 就绪（最多等 15 秒）
    info "等待 Ollama 就绪..."
    for i in $(seq 1 15); do
        sleep 1
        resp=$(_ollama_api_get "http://${OLLAMA_HOST}/api/tags")
        if [ -n "$resp" ]; then
            local count
            count=$(_ollama_count_models "$resp")
            success "Ollama 已启动 (PID: ${ollama_pid})，${count} 个模型可用"
            echo "  Ollama 日志: ${ollama_log}"
            echo ""
            return
        fi
        # 检查进程是否还活着
        if ! kill -0 "$ollama_pid" 2>/dev/null; then
            break
        fi
    done

    warn "Ollama 启动超时或失败，请手动启动: ollama serve"
    echo "  日志: ${ollama_log}"
    echo "  之后可以点击「刷新模型列表」按钮重新连接"
    echo ""
}

# ── Step 4: 编译项目 ──────────────────────────────────────────────────
build_project() {
    info "编译项目 (下载依赖可能需要几分钟)..."
    if ${MAVEN_CMD} clean compile -q 2>&1 | tail -5; then
        success "编译成功"
    else
        error "编译失败，请检查上方错误信息"
        exit 1
    fi
    echo ""
}

# ── Step 5: 启动应用 ──────────────────────────────────────────────────
run_app() {
    echo "══════════════════════════════════════════════════════════"
    info "图片目录:    ${IMG_DIR}"
    info "服务端口:    ${PORT}"
    info "Ollama:      ${OLLAMA_HOST}"
    echo "══════════════════════════════════════════════════════════"
    echo ""
    info "正在启动 Spring Boot 应用..."
    echo ""

    ${MAVEN_CMD} spring-boot:run \
        -Dspring-boot.run.arguments="\
--app.image-dir=${IMG_DIR}\
 --server.port=${PORT}" \
        -Dspring-boot.run.jvmArguments="-Duser.language=zh -Duser.country=CN"
}

# ── 入口 ───────────────────────────────────────────────────────────────
main() {
    banner
    check_java
    setup_maven
    check_ollama

    # 如果指定了 --skip-build 则跳过编译
    if [[ "${1:-}" != "--skip-build" ]]; then
        build_project
    fi

    run_app
}

main "$@"
