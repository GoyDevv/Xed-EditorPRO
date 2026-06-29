# project_runner.sh — project-aware run/build, executed inside the Ubuntu sandbox.
#
# Invoked by com.rk.runner.ProjectRunner. The project type is detected on the
# Kotlin side (ProjectTypeDetector) and passed in, so this script only has to
# check that the required toolchain is present and run/build the project from
# its own root directory.
#
# Args:
#   $1 = project type   (DetectedProjectType enum name: PYTHON, NODE, FABRIC_MOD,
#                         FORGE_MOD, GRADLE, RUST, GO, WEB)
#   $2 = project dir     (absolute path; already the working directory)
#   $3 = entry file      (absolute path of the currently open file, optional)
#
# No `set -e`: we want to surface build/run errors to the user and keep the
# terminal open so the output (and any errors) stay visible.

source "$LOCAL/bin/utils"

TYPE="${1:-UNKNOWN}"
PROJECT_DIR="${2:-$PWD}"
ENTRY="${3:-}"

# Resolve the directory we can actually enter. Shared storage is reliably available at /sdcard
# inside the sandbox, while the canonical /storage/emulated/0 form sometimes isn't, so fall back
# to the /sdcard equivalent before giving up.
enter_dir() {
  cd "$1" 2>/dev/null && return 0
  case "$1" in
    /storage/emulated/0/*) cd "/sdcard/${1#/storage/emulated/0/}" 2>/dev/null && return 0 ;;
    /storage/self/primary/*) cd "/sdcard/${1#/storage/self/primary/}" 2>/dev/null && return 0 ;;
  esac
  return 1
}

if ! enter_dir "$PROJECT_DIR"; then
  error "Cannot enter project directory: $PROJECT_DIR"
  exit 1
fi
PROJECT_DIR="$PWD"

info "Project : $PROJECT_DIR"
info "Type    : $TYPE"

# --- helpers ---------------------------------------------------------------

show_result() {
  local code="$1"
  if [ "$code" -eq 0 ]; then
    printf '\n\033[1;42m  DONE  \033[0m \033[1;32mFinished successfully (exit %s)\033[0m\n' "$code"
  else
    printf '\n\033[1;41m FAILED \033[0m \033[1;31mProcess exited with code %s\033[0m\n' "$code"
  fi
  return "$code"
}

# need <bin> <human-readable name>
need() {
  if ! command_exists "$1"; then
    error "Required tool '$1' ($2) is not installed in the sandbox."
    warn  "Open the Dependencies dialog (the download icon in the editor toolbar) and install it, then run again."
    exit 127
  fi
}

gradle_build() {
  need java "JDK"
  if [ ! -f ./gradlew ]; then
    error "gradlew not found in the project root. This Gradle project is missing its wrapper."
    exit 1
  fi
  # Make the wrapper executable (shared storage / fresh clones often drop the +x bit).
  chmod +x ./gradlew 2>/dev/null
  info "Building with ./gradlew build ..."
  if [ -x ./gradlew ]; then
    ./gradlew build
  else
    # Fallback: run it through bash directly if the exec bit can't be set (noexec mounts).
    bash ./gradlew build
  fi
  show_result $?
}

# Point Gradle at the Android SDK (installed via the Dependencies dialog into $HOME/android-sdk),
# and write local.properties so the Android Gradle plugin can find it.
setup_android_sdk() {
  if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/android-sdk" ]; then
    export ANDROID_HOME="$HOME/android-sdk"
  fi
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  if [ -n "$ANDROID_HOME" ] && [ ! -f local.properties ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties 2>/dev/null || true
  fi
  if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
    warn "Android SDK not found. Open the Dependencies dialog (download icon) and install 'Android SDK', then run again."
  fi
}

# Run gradlew with the given args, honouring the noexec fallback.
run_gradlew() {
  chmod +x ./gradlew 2>/dev/null
  if [ -x ./gradlew ]; then
    ./gradlew "$@"
  else
    bash ./gradlew "$@"
  fi
}

# --- per-type dispatch -----------------------------------------------------

case "$TYPE" in
  PYTHON)
    need python3 "Python 3"
    target="$ENTRY"
    # Fall back to a conventional entry point if the open file isn't a .py file.
    if [ -z "$target" ] || [ ! -f "$target" ] || [ "${target##*.}" != "py" ]; then
      target=""
      for candidate in main.py app.py __main__.py run.py manage.py; do
        if [ -f "$candidate" ]; then target="$candidate"; break; fi
      done
    fi
    if [ -z "$target" ]; then
      error "No Python entry point found (looked for the open file, main.py, app.py, run.py, manage.py)."
      exit 1
    fi
    if [ -s requirements.txt ]; then
      info "Installing dependencies from requirements.txt ..."
      python3 -m pip install -r requirements.txt || warn "pip install failed; running anyway."
    fi
    info "Running: python3 $target"
    python3 "$target"
    show_result $?
    ;;

  NODE)
    need node "Node.js"
    if [ -f package.json ]; then
      if [ ! -d node_modules ]; then
        info "Installing npm dependencies ..."
        npm install || warn "npm install failed; running anyway."
      fi
      if grep -q '"start"' package.json; then
        info "Running: npm start"
        npm start
      else
        info "Running: node index.js"
        node index.js
      fi
    else
      info "Running: node ${ENTRY:-index.js}"
      node "${ENTRY:-index.js}"
    fi
    show_result $?
    ;;

  FABRIC_MOD | FORGE_MOD | GRADLE)
    gradle_build
    ;;

  ANDROID)
    need java "JDK"
    setup_android_sdk
    if [ ! -f ./gradlew ]; then
      error "gradlew not found in the project root. This Android project is missing its wrapper."
      exit 1
    fi
    info "Building APK with ./gradlew assembleDebug ..."
    run_gradlew assembleDebug
    code=$?
    if [ "$code" -eq 0 ]; then
      apk="$(ls -t app/build/outputs/apk/debug/*.apk build/outputs/apk/debug/*.apk 2>/dev/null | head -n1)"
      if [ -n "$apk" ]; then
        apk_abs="$(cd "$(dirname "$apk")" 2>/dev/null && pwd)/$(basename "$apk")"
        info "APK built: $apk_abs"
        info "Installing… (confirm the system prompt)"
      else
        warn "Build succeeded but no APK was found under app/build/outputs/apk/debug/."
      fi
    fi
    show_result $code
    ;;

  SYNC)
    need java "JDK"
    setup_android_sdk
    if [ ! -f ./gradlew ]; then
      error "gradlew not found in the project root."
      exit 1
    fi
    info "Syncing Gradle dependencies (./gradlew --refresh-dependencies) ..."
    run_gradlew --refresh-dependencies tasks
    show_result $?
    ;;

  RUST)
    need cargo "Rust (cargo)"
    info "Running: cargo run"
    cargo run
    show_result $?
    ;;

  GO)
    need go "Go"
    info "Running: go run ."
    go run .
    show_result $?
    ;;

  *)
    error "This project type ($TYPE) is not runnable from the run button."
    exit 1
    ;;
esac
