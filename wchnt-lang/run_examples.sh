#!/bin/bash

# WCHNT Language Examples Runner
# Compiles every .wcn in examples/ to Haxe.
#   ./run_examples.sh            compile-only sweep
#   ./run_examples.sh --openfl   also open every %openfl example in parallel

set -e  # Exit on any error

LAUNCH_OPENFL=0
if [ "${1:-}" = "--openfl" ]; then
    LAUNCH_OPENFL=1
    shift
fi

echo "=========================================="
echo "WCHNT Language Examples Runner"
echo "=========================================="
echo

# Get the directory where this script is located, and run from there so
# `lein run` always finds this project's project.clj.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
EXAMPLES_DIR="$SCRIPT_DIR/examples"

# Check if examples directory exists
if [ ! -d "$EXAMPLES_DIR" ]; then
    echo "Error: Examples directory not found at $EXAMPLES_DIR"
    exit 1
fi

# --- optional parallel OpenFL launch -----------------------------------------

# Launch every %openfl example in its own project dir, all at once.
launch_openfl_in_parallel() {
    if ! command -v lime >/dev/null 2>&1; then
        echo "⚠  lime not found — skipping OpenFL parallel launch."
        return 0
    fi

    local openfl_files=""
    local file
    for file in $(find "$EXAMPLES_DIR" -maxdepth 1 -name '*.wcn' | sort); do
        if grep -qE '^%openfl[[:space:]]*$' "$file"; then
            openfl_files="$openfl_files $file"
        fi
    done

    if [ -z "$openfl_files" ]; then
        echo "No %openfl examples found."
        return 0
    fi

    echo
    echo "=========================================="
    echo "Opening OpenFL examples in parallel"
    echo "=========================================="
    echo

    local pids=()
    local names=()

    for file in $openfl_files; do
        local name
        name=$(basename "$file" .wcn)
        local dir="$SCRIPT_DIR/generated/openfl/$name"
        mkdir -p "$dir"

        if ! lein run "$file" > "$dir/Main.hx" < /dev/null 2> "$dir/wchnt.err"; then
            echo "✗ $name: WCHNT → Haxe compile failed"
            sed 's/^/    /' "$dir/wchnt.err" >&2
            continue
        fi

        cat > "$dir/project.xml" <<'PROJECT'
<?xml version="1.0" encoding="utf-8"?>
<project>
    <meta title="WCHNT" package="org.wchnt.app" version="1.0.0" />
    <app main="Main" path="Export" file="wchnt" />
    <window width="800" height="600" fps="60" background="#111111" vsync="true" />
    <source path="." />
    <haxelib name="openfl" />
</project>
PROJECT

        ( cd "$dir" && lime test neko > "run.log" 2>&1; echo $? > "exit.status" ) &
        pids+=("$!")
        names+=("$name")
        echo "  ▶ $name"
    done

    if [ "${#pids[@]}" -eq 0 ]; then
        echo
        echo "No OpenFL examples were launched."
        return 0
    fi

    echo
    echo "Waiting for all OpenFL windows to close..."
    echo "(Close the windows to finish; crashes report a non-zero exit below.)"
    echo

    wait || true

    local name
    for name in "${names[@]}"; do
        local dir="$SCRIPT_DIR/generated/openfl/$name"
        local status
        status=$(cat "$dir/exit.status" 2>/dev/null || echo 1)
        if [ "$status" = "0" ]; then
            echo "  ✓ $name closed cleanly"
        else
            echo "  ✗ $name crashed or exited non-zero (status $status) — see generated/openfl/$name/run.log"
        fi
    done

    return 0
}

# Find all .wcn files
WCN_FILES=$(find "$EXAMPLES_DIR" -name "*.wcn" | sort)

if [ -z "$WCN_FILES" ]; then
    echo "No .wcn files found in $EXAMPLES_DIR"
    exit 1
fi

# Count total files
TOTAL_FILES=$(echo "$WCN_FILES" | wc -l)
CURRENT=0

echo "Found $TOTAL_FILES example files:"
echo "$WCN_FILES" | sed 's|.*/||' | nl
echo

# Run each example
for file in $WCN_FILES; do
    CURRENT=$((CURRENT + 1))
    filename=$(basename "$file")
    
    echo "=========================================="
    echo "[$CURRENT/$TOTAL_FILES] Running: $filename"
    echo "=========================================="
    echo
    
    # Run the example
    if lein run "$file"; then
        echo
        echo "✓ SUCCESS: $filename"
    else
        echo
        echo "✗ FAILED: $filename"
        echo "Continuing with next example..."
    fi
    
    echo
done

echo "=========================================="
echo "All examples completed!"
echo "=========================================="

if [ "$LAUNCH_OPENFL" = "1" ]; then
    launch_openfl_in_parallel
fi
 