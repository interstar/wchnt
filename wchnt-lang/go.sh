#!/bin/bash

# WCHNT to Haxe to JavaScript to Execution Pipeline
# Usage: ./go.sh <wchnt-file>

if [ $# -eq 0 ]; then
    echo "Usage: $0 <wchnt-file>"
    echo "Example: $0 examples/test.wcn"
    exit 1
fi

# Run from this script's directory so `lein` / `lime` find the project files.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

WCHNT_FILE="$1"
BASE_NAME=$(basename "$WCHNT_FILE" .wcn)
HAXE_FILE="Main.hx"
JS_FILE="${BASE_NAME}.js"
NEKO_FILE="${BASE_NAME}.n"

# Remove generated artifacts on success; leave them on failure for debugging.
cleanup() {
    status=$1
    if [ "$status" -eq 0 ]; then
        rm -f "$HAXE_FILE" "$JS_FILE" "$NEKO_FILE"
    fi
}
trap 'cleanup $?' EXIT

echo "🚀 WCHNT Pipeline: $WCHNT_FILE"
echo ""

# Step 1: Compile WCHNT to Haxe
echo "📝 Step 1: Compiling WCHNT to Haxe..."
if lein run "$WCHNT_FILE" > "$HAXE_FILE" < /dev/null; then
    echo "✅ WCHNT → Haxe: $HAXE_FILE"
else
    echo "❌ WCHNT compilation failed"
    if [ -s "$HAXE_FILE" ]; then
        printf "Compiler diagnostic: " >&2
        head -n 1 "$HAXE_FILE" >&2
    fi
    exit 1
fi

if ! INFO=$(lein run --info "$WCHNT_FILE"); then
    echo "❌ Could not inspect WCHNT target information" >&2
    printf '%s\n' "$INFO" >&2
    exit 1
fi

if ! printf '%s' "$INFO" | jq -e '.success == true' >/dev/null; then
    echo "❌ Compiler returned invalid target information" >&2
    printf '%s\n' "$INFO" >&2
    exit 1
fi

PAGE_KIND=$(printf '%s' "$INFO" | jq -r '.pageKind')
if [ "$PAGE_KIND" = "library" ]; then
    echo ""
    echo "📚 Library detected - skipping Haxe compilation and execution"
    echo "✅ Library generation complete!"
    exit 0
fi

HOST=$(printf '%s' "$INFO" | jq -r '.target // empty')

if [ "$HOST" = "openfl" ]; then
    echo ""
    echo "🪟 OpenFL host — writing project.xml and launching lime"
    mkdir -p generated
    cp "$HAXE_FILE" generated/Main.hx
    cat > project.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<project>
    <meta title="WCHNT" package="org.wchnt.app" version="1.0.0" />
    <app main="Main" path="Export" file="wchnt" />
    <window width="800" height="600" fps="60" background="#111111" vsync="true" />
    <source path="generated" />
    <haxelib name="openfl" />
</project>
EOF
    echo ""
    echo "⚡ lime test neko"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if lime test neko; then
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "✅ OpenFL run complete!"
        exit 0
    else
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "❌ OpenFL build failed"
        exit 1
    fi
fi

if [ "$HOST" = "cli" ]; then
    echo ""
    echo "⌨️  CLI host — compiling to neko"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if haxe -neko "$NEKO_FILE" -main Main < /dev/null; then
        echo "✅ Haxe → neko: $NEKO_FILE"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "🎯 Running (stdin until EOF / Ctrl-D)"
        neko "$NEKO_FILE"
        exit $?
    else
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "❌ Haxe (neko) compilation failed"
        exit 1
    fi
fi

echo ""

# Step 2: Compile Haxe to JavaScript
echo "⚡ Step 2: Compiling Haxe to JavaScript..."
if haxe -js "$JS_FILE" -main Main; then
    echo "✅ Haxe → JavaScript: $JS_FILE"
else
    echo "❌ Haxe compilation failed"
    exit 1
fi

echo ""

# Step 3: Run the JavaScript
echo "🎯 Step 3: Running JavaScript..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
node "$JS_FILE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Execution complete!" 
