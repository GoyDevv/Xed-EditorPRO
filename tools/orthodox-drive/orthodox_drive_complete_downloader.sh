#!/usr/bin/env bash
# Orthodox PDF Collection - complete Google Drive downloader
# Windows + Git Bash / MSYS2
# Uses rclone, downloads recursively, retries failures, and verifies the destination.
#
# Optional environment variables for a truly non-interactive OAuth setup:
#   export RCLONE_GOOGLE_CLIENT_ID="....apps.googleusercontent.com"
#   export RCLONE_GOOGLE_CLIENT_SECRET="GOCSPX-...."
#
# The script stores its rclone config locally beside itself in .rclone/rclone.conf.
# Treat that file as a password: it can contain your Google OAuth refresh token.

set -Eeuo pipefail
IFS=$'
	'

REMOTE_NAME="gdrive"
FOLDER_NAME="Orthodox PDF Collection"
MAX_VERIFY_PASSES=3
TRANSFERS="${RCLONE_TRANSFERS:-4}"
CHECKERS="${RCLONE_CHECKERS:-8}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
WORK_DIR="$SCRIPT_DIR/.orthodox-download"
TOOL_DIR="$SCRIPT_DIR/.rclone"
CONFIG_FILE="$TOOL_DIR/rclone.conf"
RCLONE_BIN="$TOOL_DIR/rclone.exe"
LOG_DIR="$WORK_DIR/logs"
mkdir -p "$WORK_DIR" "$TOOL_DIR" "$LOG_DIR"
export RCLONE_CONFIG="$CONFIG_FILE"

SOURCE_SPEC="${REMOTE_NAME},shared_with_me:${FOLDER_NAME}"

cleanup() {
    unset RCLONE_DRIVE_TOKEN AUTH_URL GOOGLE_CLIENT_SECRET 2>/dev/null || true
}
trap cleanup EXIT

die() {
    echo
    echo "ERROR: $*" >&2
    echo "See logs in: $LOG_DIR" >&2
    exit 1
}

info() { echo; echo "==> $*"; }
ok()   { echo "    OK: $*"; }
warn() { echo "    WARNING: $*" >&2; }

require_windows_git_bash() {
    case "${OSTYPE:-}" in
        msys*|mingw*|cygwin*) ;;
        *) die "This .sh is intended for Windows Git Bash/MSYS2. Do not run it in PowerShell directly." ;;
    esac
}

get_arch() {
    local a="${PROCESSOR_ARCHITEW6432:-${PROCESSOR_ARCHITECTURE:-AMD64}}"
    case "${a^^}" in
        ARM64) echo "arm64" ;;
        AMD64|X86_64) echo "amd64" ;;
        X86|I386|I686) echo "386" ;;
        *) echo "amd64" ;;
    esac
}

ensure_rclone() {
    if command -v rclone >/dev/null 2>&1; then
        RCLONE_BIN="$(command -v rclone)"
        ok "Using existing rclone: $("$RCLONE_BIN" version | head -n 1)"
        return
    fi

    if [[ -x "$RCLONE_BIN" ]]; then
        ok "Using bundled rclone: $("$RCLONE_BIN" version | head -n 1)"
        return
    fi

    info "Downloading the current official Windows rclone build..."
    local arch url zip_dir zip_file extracted exe
    arch="$(get_arch)"
    url="https://downloads.rclone.org/rclone-current-windows-${arch}.zip"
    zip_file="$TOOL_DIR/rclone.zip"
    zip_dir="$TOOL_DIR/extracted"

    rm -rf "$zip_dir"
    rm -f "$zip_file"

    if command -v curl.exe >/dev/null 2>&1; then
        curl.exe -fL --retry 5 --retry-delay 2 --connect-timeout 20 -o "$zip_file" "$url" || die "Could not download rclone from $url"
    elif command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '$url' -OutFile '$zip_file'" || die "Could not download rclone from $url"
    else
        die "Neither curl.exe nor powershell.exe is available."
    fi

    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '$zip_file' -DestinationPath '$zip_dir' -Force" || die "Could not extract the rclone ZIP."
    exe="$(find "$zip_dir" -type f -iname 'rclone.exe' -print -quit || true)"
    [[ -n "$exe" ]] || die "rclone.exe was not found after extraction."
    cp -f "$exe" "$RCLONE_BIN"
    chmod +x "$RCLONE_BIN" 2>/dev/null || true
    rm -rf "$zip_dir" "$zip_file"

    ok "Installed bundled rclone: $("$RCLONE_BIN" version | head -n 1)"
}

remote_exists() {
    "$RCLONE_BIN" listremotes 2>/dev/null | sed 's/:$//' | grep -Fxq "$REMOTE_NAME"
}

test_remote() {
    "$RCLONE_BIN" lsd "${REMOTE_NAME}:" --max-depth 1 >/dev/null 2>"$LOG_DIR/remote-test.log"
}

import_local_config_if_present() {
    local candidate="$SCRIPT_DIR/rclone.conf"
    if [[ -f "$candidate" && ! -f "$CONFIG_FILE" ]]; then
        info "Found rclone.conf next to the script; importing it."
        cp -f "$candidate" "$CONFIG_FILE"
        chmod 600 "$CONFIG_FILE" 2>/dev/null || true
        if remote_exists && test_remote; then
            ok "Imported config works."
            return 0
        fi
        warn "The imported config could not be validated. It may be for a different environment."
        rm -f "$CONFIG_FILE"
    fi
    return 1
}

open_url_default_browser() {
    local url="$1"
    export AUTH_URL="$url"
    if command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -ExecutionPolicy Bypass -Command '$u=$env:AUTH_URL; Start-Process -FilePath $u' >/dev/null 2>&1 || true
    elif command -v cmd.exe >/dev/null 2>&1; then
        cmd.exe /c start "" "$url" >/dev/null 2>&1 || true
    else
        warn "Could not find a Windows browser launcher. Open this URL manually:"
        echo "$url"
    fi
}

authorize_and_create_remote() {
    info "Google authorization"
    echo "The script will start rclone's local OAuth callback, capture the URL,"
    echo "open it in your Windows default browser, and wait for Google to return the token."
    echo

    local client_id="${RCLONE_GOOGLE_CLIENT_ID:-}"
    local client_secret="${RCLONE_GOOGLE_CLIENT_SECRET:-}"
    local auth_log="$LOG_DIR/oauth.log"
    local token=""
    local url=""
    local pid=""
    rm -f "$auth_log"

    local -a auth_cmd
    auth_cmd=("$RCLONE_BIN" authorize drive "--auth-no-open-browser")
    if [[ -n "$client_id" || -n "$client_secret" ]]; then
        [[ -n "$client_id" && -n "$client_secret" ]] || die "Both RCLONE_GOOGLE_CLIENT_ID and RCLONE_GOOGLE_CLIENT_SECRET must be set."
        auth_cmd=("$RCLONE_BIN" authorize drive "$client_id" "$client_secret" "--auth-no-open-browser")
        ok "Using the custom Google OAuth client supplied in environment variables."
    else
        warn "No custom Google OAuth client supplied."
        warn "The current rclone docs say the shared Google client is being retired during 2026."
        warn "The script will try it first; if Google rejects it, supply a custom client ID/secret when prompted."
    fi

    "${auth_cmd[@]}" >"$auth_log" 2>&1 &
    pid=$!

    for _ in $(seq 1 180); do
        url="$(grep -oE 'https?://127\.0\.0\.1:[0-9]+/auth(\?state=[^[:space:]]+)?' "$auth_log" | head -n 1 || true)"
        if [[ -n "$url" ]]; then break; fi
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 1
    done

    if [[ -n "$url" ]]; then
        echo
        echo "Opening Google authorization in your default browser..."
        open_url_default_browser "$url"
        echo "Complete the Google approval in the browser. rclone will continue automatically."
    fi

    wait "$pid" || {
        echo
        cat "$auth_log"
        return 2
    }

    token="$(sed -n '/Paste the following into your remote machine --->/{n;s/\r$//;p;q;}' "$auth_log" || true)"
    if [[ -z "$token" ]]; then
        token="$(grep -E '^\{.*"refresh_token".*\}$' "$auth_log" | tail -n 1 || true)"
    fi

    [[ -n "$token" ]] || {
        echo
        cat "$auth_log"
        return 3
    }

    local -a cfg_args
    cfg_args=("$RCLONE_BIN" config create "$REMOTE_NAME" drive scope drive shared_with_me true token "$token")
    if [[ -n "$client_id" ]]; then
        cfg_args+=(client_id "$client_id" client_secret "$client_secret")
    fi

    "${cfg_args[@]}" >"$LOG_DIR/config-create.log" 2>&1 || {
        cat "$LOG_DIR/config-create.log"
        return 4
    }

    ok "Google Drive remote created."
    rm -f "$auth_log" 2>/dev/null || true
    unset token
    return 0
}

setup_remote() {
    if remote_exists && test_remote; then
        ok "Existing gdrive remote works."
        return
    fi

    if [[ -f "$CONFIG_FILE" ]]; then
        warn "Existing bundled config is present but could not be validated."
        rm -f "$CONFIG_FILE"
    fi

    if authorize_and_create_remote; then
        :
    else
        local rc=$?
        echo
        warn "Automatic OAuth attempt failed (code $rc)."
        echo
        read -r -p "Enter Google OAuth Client ID (or press Enter to abort): " client_id
        [[ -n "$client_id" ]] || die "No client ID supplied."
        read -r -s -p "Enter Google OAuth Client Secret: " client_secret
        echo
        [[ -n "$client_secret" ]] || die "No client secret supplied."

        export RCLONE_GOOGLE_CLIENT_ID="$client_id"
        export RCLONE_GOOGLE_CLIENT_SECRET="$client_secret"

        authorize_and_create_remote || {
            echo
            cat "$LOG_DIR/oauth.log" 2>/dev/null || true
            die "Google OAuth setup failed."
        }
        unset client_secret
    fi

    remote_exists || die "rclone remote '$REMOTE_NAME' was not created."
    test_remote || {
        cat "$LOG_DIR/remote-test.log" 2>/dev/null || true
        die "The new Google Drive remote could not be validated."
    }
}

get_desktop_dir() {
    local win=""
    win="$(powershell.exe -NoProfile -Command "[Environment]::GetFolderPath('Desktop')" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
    if [[ -n "$win" ]] && command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$win"
    else
        echo "$HOME/Desktop"
    fi
}

choose_destination() {
    local default_dest
    default_dest="${DEST_DIR:-$(get_desktop_dir)/Orthodox PDF Collection}"
    DEST_DIR="$default_dest"
    mkdir -p "$DEST_DIR" || die "Cannot create destination: $DEST_DIR"
    echo
    echo "Destination: $DEST_DIR"
}

validate_source() {
    info "Checking that the exact shared folder is visible..."
    "$RCLONE_BIN" lsf "$SOURCE_SPEC" --files-only --max-depth 1 >/dev/null 2>"$LOG_DIR/source-test.log" || {
        echo
        cat "$LOG_DIR/source-test.log"
        echo
        echo "Top-level Shared with me folders detected:"
        "$RCLONE_BIN" lsd "${REMOTE_NAME},shared_with_me:" --max-depth 1 || true
        die "Could not open '$FOLDER_NAME' in Shared with me."
    }
    ok "Folder is reachable through Google Drive API."
}

create_manifests() {
    info "Creating source inventory..."
    "$RCLONE_BIN" lsf "$SOURCE_SPEC" -R --files-only >"$WORK_DIR/source_files.txt" || die "Could not build the source file inventory."

    local total pdfs
    total="$(wc -l <"$WORK_DIR/source_files.txt" | tr -d ' ')"
    pdfs="$(grep -Eui '\.pdf$' "$WORK_DIR/source_files.txt" | wc -l | tr -d ' ' || true)"

    echo "    Source files: $total"
    echo "    PDF files:    $pdfs"

    if [[ "$total" -eq 0 ]]; then die "The folder appears empty."; fi

    "$RCLONE_BIN" size "$SOURCE_SPEC" >"$WORK_DIR/source_size.txt" 2>&1 || true
    cat "$WORK_DIR/source_size.txt"
}

download_once() {
    local log="$LOG_DIR/copy.log"
    echo
    echo "Starting recursive download. Existing identical files are skipped."
    echo "You can stop safely with Ctrl+C and rerun this script later."
    echo
    "$RCLONE_BIN" copy "$SOURCE_SPEC" "$DEST_DIR"         --transfers "$TRANSFERS"         --checkers "$CHECKERS"         --retries 10         --low-level-retries 20         --retries-sleep 10s         --stats 15s         --stats-one-line         --fast-list         --create-empty-src-dirs         --drive-stop-on-download-limit         --log-file "$log"         --log-level INFO         -P
}

verify_once() {
    local report="$WORK_DIR/check_combined.txt"
    rm -f "$report"

    "$RCLONE_BIN" check "$SOURCE_SPEC" "$DEST_DIR"         --one-way         --combined "$report"         --checkers "$CHECKERS"         --fast-list         >"$LOG_DIR/check.log" 2>&1 || true

    local missing different errors matches
    missing="$(grep -Ec '^\+ ' "$report" 2>/dev/null || true)"
    different="$(grep -Ec '^\* ' "$report" 2>/dev/null || true)"
    errors="$(grep -Ec '^! ' "$report" 2>/dev/null || true)"
    matches="$(grep -Ec '^= ' "$report" 2>/dev/null || true)"

    echo
    echo "Verification:"
    echo "  Matching:     $matches"
    echo "  Missing:      $missing"
    echo "  Different:    $different"
    echo "  Errors:       $errors"

    if [[ "$missing" -eq 0 && "$different" -eq 0 && "$errors" -eq 0 ]]; then return 0; fi

    echo
    if [[ "$missing" -gt 0 || "$different" -gt 0 ]]; then
        echo "Problem paths are saved in:"
        echo "  $report"
        echo
        echo "Re-copying unresolved files..."
    fi
    return 1
}

retry_from_report() {
    local report="$WORK_DIR/check_combined.txt"
    local retry_list="$WORK_DIR/retry_files.txt"
    : >"$retry_list"

    awk '/^[+*] / {sub(/^[+*] /,""); print}' "$report" >"$retry_list" || true

    if [[ ! -s "$retry_list" ]]; then return 0; fi

    echo
    echo "Retry list:"
    sed -n '1,20p' "$retry_list"
    if [[ "$(wc -l <"$retry_list" | tr -d ' ')" -gt 20 ]]; then
        echo "... (full list is in $retry_list)"
    fi

    "$RCLONE_BIN" copy "$SOURCE_SPEC" "$DEST_DIR"         --files-from-raw "$retry_list"         --transfers "$TRANSFERS"         --checkers "$CHECKERS"         --retries 10         --low-level-retries 20         --retries-sleep 10s         --stats 15s         --stats-one-line         --drive-stop-on-download-limit         --log-file "$LOG_DIR/retry.log"         --log-level INFO         -P || true
}

final_reports() {
    info "Generating final local inventory..."
    "$RCLONE_BIN" lsf "$DEST_DIR" -R --files-only >"$WORK_DIR/dest_files.txt" 2>"$LOG_DIR/dest-lsf.log" || true

    local src_count dst_count src_pdfs dst_pdfs
    src_count="$(wc -l <"$WORK_DIR/source_files.txt" | tr -d ' ')"
    dst_count="$(wc -l <"$WORK_DIR/dest_files.txt" | tr -d ' ')"
    src_pdfs="$(grep -Eui '\.pdf$' "$WORK_DIR/source_files.txt" | wc -l | tr -d ' ' || true)"
    dst_pdfs="$(grep -Eui '\.pdf$' "$WORK_DIR/dest_files.txt" | wc -l | tr -d ' ' || true)"

    echo
    echo "FINAL INVENTORY"
    echo "  Source files: $src_count"
    echo "  Local files:  $dst_count"
    echo "  Source PDFs:  $src_pdfs"
    echo "  Local PDFs:   $dst_pdfs"
    echo
    echo "Reports:"
    echo "  Source list:  $WORK_DIR/source_files.txt"
    echo "  Dest list:    $WORK_DIR/dest_files.txt"
    echo "  Check report: $WORK_DIR/check_combined.txt"
    echo "  Logs:         $LOG_DIR"
}

main() {
    require_windows_git_bash
    ensure_rclone

    import_local_config_if_present || true
    setup_remote

    choose_destination
    validate_source
    create_manifests

    info "Downloading..."
    download_once || warn "The initial copy returned a non-zero exit code. Verification/retry will handle unresolved files."

    local pass=1
    while (( pass <= MAX_VERIFY_PASSES )); do
        info "Verification pass $pass/$MAX_VERIFY_PASSES"
        if verify_once; then
            ok "Everything in the source matched the destination."
            final_reports
            echo
            echo "DONE. The folder is complete according to rclone's source-to-destination check."
            exit 0
        fi

        if (( pass < MAX_VERIFY_PASSES )); then retry_from_report; fi
        ((pass++))
    done

    final_reports
    echo
    echo "The script could not reach a clean verification result after $MAX_VERIFY_PASSES passes."
    echo "Nothing in the destination was deleted."
    echo "Open $WORK_DIR/check_combined.txt and $LOG_DIR/check.log for the exact problem files."
    exit 2
}

main "$@"
