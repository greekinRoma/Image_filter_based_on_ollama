#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────
# setup.sh — Install dependencies for Ollama Image Filter (Java)
# ────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================"
echo "  Ollama Image Filter — Java Setup"
echo "========================================"
echo ""

# ── Check Java ──────────────────────────────────────────
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '\d+\.\d+\.\d+' | cut -d. -f1)
    echo "✓ Java found: $(java -version 2>&1 | head -1)"
    if [ "${JAVA_VER:-0}" -lt 21 ]; then
        echo "⚠ Java 21+ is recommended. Current: $JAVA_VER"
    fi
else
    echo "✗ Java 21+ is required but not found."
    echo "  Install: sudo apt install openjdk-21-jdk"
    echo "  Or:      sdk install java 21.0.4-tem"
    exit 1
fi

# ── Check Maven ─────────────────────────────────────────
if command -v mvn &>/dev/null; then
    echo "✓ Maven found: $(mvn --version 2>&1 | head -1)"
else
    echo "✗ Maven not found."
    echo "  Install: sudo apt install maven"
    echo "  Or use the Maven wrapper: ./mvnw"
fi

# ── Check Ollama ────────────────────────────────────────
if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
    echo "✓ Ollama is running"
else
    echo "⚠ Ollama is not running on localhost:11434"
    echo "  Start: ollama serve"
fi

# ── Download dependencies ───────────────────────────────
echo ""
echo "Downloading Maven dependencies..."
if command -v mvn &>/dev/null; then
    mvn dependency:resolve -q
    echo "✓ Dependencies resolved"
else
    echo "⚠ Maven not available — skipping dependency download"
fi

echo ""
echo "Setup complete! Run: ./start.sh"
