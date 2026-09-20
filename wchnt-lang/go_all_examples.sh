#!/usr/bin/env bash

# Compile every Haxe-capable example and smoke-run it. Windowed examples are
# built first, then run briefly and stopped; CLI examples receive EOF on stdin.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
EXAMPLES_DIR="$SCRIPT_DIR/examples"
LOG_DIR="$SCRIPT_DIR/generated/smoke"
SMOKE_SECONDS="${WCHNT_SMOKE_SECONDS:-5}"

if ! command -v timeout >/dev/null 2>&1; then
    echo "Error: 'timeout' is required for bounded example smoke runs." >&2
    exit 2
fi
if [[ ! "$SMOKE_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
    echo "Error: WCHNT_SMOKE_SECONDS must be a positive integer." >&2
    exit 2
fi

mkdir -p "$LOG_DIR"
mapfile -t WCN_FILES < <(find "$EXAMPLES_DIR" -type f -name '*.wcn' | sort)
if ((${#WCN_FILES[@]} == 0)); then
    echo "No .wcn files found in $EXAMPLES_DIR" >&2
    exit 1
fi

FAILURES=0
SKIPPED=0
CURRENT=0
TOTAL=${#WCN_FILES[@]}

for file in "${WCN_FILES[@]}"; do
    CURRENT=$((CURRENT + 1))
    name="$(basename "$file" .wcn)"
    log="$LOG_DIR/$name.log"
    host="$(sed -nE 's/^%(terminal|cli|openfl|canvas|cli-live)[[:space:]]*$/\1/p' "$file" | head -n 1)"
    printf '\n[%d/%d] %s (%%%s)\n' "$CURRENT" "$TOTAL" "$(basename "$file")" "${host:-no target}"

    case "$host" in
        canvas|cli-live)
            echo "SKIP: live-only target; not executable by the Haxe backend." | tee "$log"
            SKIPPED=$((SKIPPED + 1))
            continue
            ;;
        terminal|cli)
            work="$SCRIPT_DIR/generated/haxe-smoke/$name"
            mkdir -p "$work"
            if ! lein run "$file" > "$work/Main.hx" 2> "$work/compiler.log"; then
                { echo "WCHNT → Haxe compilation failed:"; cat "$work/compiler.log"; head -n 1 "$work/Main.hx"; } | tee "$log"
                FAILURES=$((FAILURES + 1))
                continue
            fi
            if [[ "$host" == terminal ]]; then
                compile_cmd=(haxe -js "$name.js" -main Main)
                run_cmd=(node "$name.js")
            else
                compile_cmd=(haxe -neko "$name.n" -main Main)
                run_cmd=(neko "$name.n")
            fi
            if ! (cd "$work" && "${compile_cmd[@]}") > "$work/build.log" 2>&1; then
                { echo "Haxe compilation failed:"; cat "$work/build.log"; } | tee "$log"
                FAILURES=$((FAILURES + 1))
                continue
            fi
            set +e
            (cd "$work" && timeout --foreground "${SMOKE_SECONDS}s" "${run_cmd[@]}") </dev/null > "$work/run.log" 2>&1
            status=$?
            set -e
            cat "$work/compiler.log" "$work/build.log" "$work/run.log" | tee "$log"
            if ((status == 0)); then
                echo "PASS: WCHNT/Haxe compile and run (log: generated/smoke/$name.log)"
            elif ((status == 124)); then
                echo "FAIL: program exceeded ${SMOKE_SECONDS}s (log: generated/smoke/$name.log)"
                FAILURES=$((FAILURES + 1))
            else
                echo "FAIL: program exited $status (log: generated/smoke/$name.log)"
                FAILURES=$((FAILURES + 1))
            fi
            ;;
        openfl)
            # Build in an isolated directory so each example gets its own
            # generated Main.hx, project.xml, and Lime output.
            work="$SCRIPT_DIR/generated/openfl-smoke/$name"
            mkdir -p "$work"
            if ! lein run "$file" > "$work/Main.hx" 2> "$work/compiler.log"; then
                {
                    echo "WCHNT → Haxe compilation failed:";
                    cat "$work/compiler.log";
                    head -n 1 "$work/Main.hx";
                } 2>&1 | tee "$log"
                FAILURES=$((FAILURES + 1))
                continue
            fi
            cat > "$work/project.xml" <<'PROJECT'
<?xml version="1.0" encoding="utf-8"?>
<project>
    <meta title="WCHNT smoke test" package="org.wchnt.app" version="1.0.0" />
    <app main="Main" path="Export" file="wchnt" />
    <window width="800" height="600" fps="60" background="#111111" vsync="true" />
    <source path="." />
    <haxelib name="openfl" />
</project>
PROJECT
            if ! (cd "$work" && lime build neko) > "$work/build.log" 2>&1; then
                { echo "OpenFL/Haxe build failed:"; cat "$work/build.log"; } | tee "$log"
                FAILURES=$((FAILURES + 1))
                continue
            fi
            executable="$work/Export/neko/bin/wchnt"
            if [[ ! -f "$executable" ]]; then
                { echo "Build reported success but executable is missing: $executable"; cat "$work/build.log"; } | tee "$log"
                FAILURES=$((FAILURES + 1))
                continue
            fi
            set +e
            (cd "$work/Export/neko/bin" && timeout --foreground "${SMOKE_SECONDS}s" ./wchnt) </dev/null > "$work/run.log" 2>&1
            status=$?
            set -e
            cat "$work/build.log" "$work/run.log" | tee "$log"
            if ((status == 0)); then
                echo "PASS: OpenFL app exited cleanly (log: generated/smoke/$name.log)"
            elif ((status == 124)); then
                echo "PASS: OpenFL app ran for ${SMOKE_SECONDS}s and was stopped (log: generated/smoke/$name.log)"
            else
                echo "FAIL: OpenFL app exited $status (log: generated/smoke/$name.log)"
                FAILURES=$((FAILURES + 1))
            fi
            ;;
        *)
            echo "SKIP: no supported Haxe target detected." | tee "$log"
            SKIPPED=$((SKIPPED + 1))
            ;;
    esac
done

echo
echo "Example smoke run complete: $((TOTAL - SKIPPED - FAILURES)) passed, $FAILURES failed, $SKIPPED live-only/unsupported skipped."
echo "Logs: generated/smoke/"
if ((FAILURES > 0)); then
    exit 1
fi
