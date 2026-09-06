#!/usr/bin/env bash
set -euo pipefail

# Railhook CLI installer
# Usage: curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/railhook-cli/install.sh | bash
#
# Pass flags after `--` when piping into bash:
#   curl -fsSL .../install.sh | bash -s -- --with-java
#   curl -fsSL .../install.sh | bash -s -- --uninstall
#
# Environment variables:
#   RAILHOOK_VERSION       — version to install (default: latest release)
#   RAILHOOK_INSTALL_DIR   — where to put the railhook wrapper (default: ~/.local/bin)
#   RAILHOOK_SKIP_JAVA     — set to 1 to skip auto-install of Java entirely (you manage
#                            your own JVM, e.g. via SDKMAN, and `java` may not be on PATH
#                            yet in this non-interactive shell)
#   RAILHOOK_WITH_JAVA     — set to 1 to allow this script to install Java 17 via the OS
#                            package manager (requires sudo). Equivalent to --with-java.
#
# Flags:
#   --with-java   allow installing Java 17 via sudo + the OS package manager. Without
#                 this flag (or RAILHOOK_WITH_JAVA=1), if Java 17+ isn't already on PATH
#                 the script tells you how to install it yourself rather than silently
#                 invoking sudo.
#   --uninstall   remove the railhook wrapper and installed JAR, then exit.
#   -h, --help    show this help and exit.

VERSION="${RAILHOOK_VERSION:-latest}"
INSTALL_DIR="${RAILHOOK_INSTALL_DIR:-$HOME/.local/bin}"
JAR_DIR="$HOME/.local/lib/railhook"
REPO="vadymkykalo/railhook"
WITH_JAVA="${RAILHOOK_WITH_JAVA:-0}"
DO_UNINSTALL=0

# Override points for CI/tests only — point the installer at a local fixture
# server instead of real GitHub, so tests don't depend on (or mutate) actual
# releases. Not meant to be set by end users; not documented above on purpose.
GITHUB_BASE_URL="${RAILHOOK_GITHUB_BASE_URL:-https://github.com}"
GITHUB_API_BASE_URL="${RAILHOOK_GITHUB_API_BASE_URL:-https://api.github.com}"

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[0;33m'
BOLD='\033[1m'
RESET='\033[0m'

info()  { echo -e "${CYAN}▸${RESET} $1"; }
ok()    { echo -e "${GREEN}✓${RESET} $1"; }
warn()  { echo -e "${YELLOW}!${RESET} $1"; }
fail()  { echo -e "${RED}✗${RESET} $1" >&2; exit 1; }

print_help() {
    cat <<'EOF'
Railhook CLI installer

Usage:
  curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/railhook-cli/install.sh | bash
  curl -fsSL .../install.sh | bash -s -- [--with-java] [--uninstall] [-h|--help]

Flags:
  --with-java   allow installing Java 17 via sudo + the OS package manager
  --uninstall   remove the railhook wrapper and installed JAR, then exit
  -h, --help    show this help and exit

Environment variables:
  RAILHOOK_VERSION       version to install (default: latest release)
  RAILHOOK_INSTALL_DIR   wrapper install location (default: ~/.local/bin)
  RAILHOOK_SKIP_JAVA     1 = skip Java auto-install entirely
  RAILHOOK_WITH_JAVA     1 = same as --with-java
EOF
}

for arg in "$@"; do
    case "$arg" in
        --with-java) WITH_JAVA=1 ;;
        --uninstall) DO_UNINSTALL=1 ;;
        -h|--help) print_help; exit 0 ;;
        *) fail "Unknown argument: $arg (see --help)" ;;
    esac
done

# ── Uninstall ─────────────────────────────────────────────────
uninstall() {
    info "Uninstalling railhook..."
    if [ -e "$INSTALL_DIR/railhook" ]; then
        rm -f "$INSTALL_DIR/railhook"
        ok "Removed $INSTALL_DIR/railhook"
    else
        warn "$INSTALL_DIR/railhook not found — nothing to remove there"
    fi

    if [ -d "$JAR_DIR" ]; then
        rm -rf "$JAR_DIR"
        ok "Removed $JAR_DIR"
    fi

    echo ""
    echo "Note: any PATH entry added to your shell profile was left in place"
    echo "(harmless if railhook is gone), and ~/.config/railhook/config.json"
    echo "was left in place — remove it manually if you want a clean slate."
}

# ── Detect OS & package manager ──────────────────────────────
detect_os() {
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    PKG_MGR=""

    case "$OS" in
        Linux)
            if command -v apt-get &>/dev/null; then
                PKG_MGR="apt"
            elif command -v dnf &>/dev/null; then
                PKG_MGR="dnf"
            elif command -v yum &>/dev/null; then
                PKG_MGR="yum"
            elif command -v pacman &>/dev/null; then
                PKG_MGR="pacman"
            fi
            ;;
        Darwin)
            if command -v brew &>/dev/null; then
                PKG_MGR="brew"
            fi
            ;;
    esac
    ok "Detected $OS ($ARCH), package manager: ${PKG_MGR:-none}"
}

# ── Java version detection ───────────────────────────────────
# Handles both the common quoted form (`... version "17.0.9" ...`) and
# unquoted output some JVM builds emit, plus the old 1.x versioning scheme
# (`1.8.0_x` means Java 8).
detect_java_major_version() {
    local raw ver major
    raw=$(java -version 2>&1 | head -1)

    ver=$(echo "$raw" | grep -oE '"[0-9]+([._][0-9]+)*"' | head -1 | tr -d '"')
    if [ -z "$ver" ]; then
        ver=$(echo "$raw" | grep -oE '[0-9]+([._][0-9]+)*' | head -1)
    fi
    if [ -z "$ver" ]; then
        echo ""
        return
    fi

    major=$(echo "$ver" | cut -d'.' -f1)
    if [ "$major" = "1" ]; then
        # old scheme: "1.8.0_x" -> Java 8
        major=$(echo "$ver" | cut -d'.' -f2)
    fi
    echo "$major"
}

# ── Ensure Java 17+ ─────────────────────────────────────────
ensure_java() {
    if command -v java &>/dev/null; then
        JAVA_VER="$(detect_java_major_version)"
        if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
            ok "Java $JAVA_VER already installed"
            return
        fi
        warn "Found Java ${JAVA_VER:-(unrecognized version)}, but 17+ is required"
    fi

    if [ "${RAILHOOK_SKIP_JAVA:-0}" = "1" ]; then
        warn "RAILHOOK_SKIP_JAVA=1 — skipping Java auto-install."
        warn "Make sure a Java 17+ 'java' is on PATH before running railhook."
        return
    fi

    if [ "$WITH_JAVA" != "1" ]; then
        fail "Java 17+ was not found on PATH, and this script will not silently run sudo for you.
    Install Java 17 yourself, e.g.:
    Ubuntu/Debian:  sudo apt install openjdk-17-jre-headless
    Fedora/RHEL:    sudo dnf install java-17-openjdk-headless
    Arch:           sudo pacman -S jre17-openjdk-headless
    macOS:          brew install openjdk@17

    ...or re-run with --with-java (or RAILHOOK_WITH_JAVA=1) to let this script
    install it for you via sudo + your OS package manager.

    If you manage Java yourself and it just isn't on PATH in this shell yet
    (e.g. SDKMAN before a profile reload), set RAILHOOK_SKIP_JAVA=1 instead."
    fi

    info "Installing Java 17 (headless runtime) — this will use sudo..."

    case "$PKG_MGR" in
        apt)
            sudo apt-get update -qq
            sudo apt-get install -y -qq openjdk-17-jre-headless
            ;;
        dnf)
            sudo dnf install -y -q java-17-openjdk-headless
            ;;
        yum)
            sudo yum install -y -q java-17-openjdk-headless
            ;;
        pacman)
            sudo pacman -Sy --noconfirm jre17-openjdk-headless
            ;;
        brew)
            brew install --quiet openjdk@17
            # brew doesn't link by default
            sudo ln -sfn "$(brew --prefix openjdk@17)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-17.jdk 2>/dev/null || true
            ;;
        *)
            fail "No supported package manager found. Install Java 17+ manually:
    Ubuntu/Debian:  sudo apt install openjdk-17-jre-headless
    Fedora/RHEL:    sudo dnf install java-17-openjdk-headless
    Arch:           sudo pacman -S jre17-openjdk-headless
    macOS:          brew install openjdk@17"
            ;;
    esac

    # Verify
    if ! command -v java &>/dev/null; then
        fail "Java installation succeeded but 'java' not found in PATH. Restart your terminal and try again."
    fi
    JAVA_VER="$(detect_java_major_version)"
    ok "Java $JAVA_VER installed"
}

# ── Resolve version ──────────────────────────────────────────
resolve_version() {
    if [ "$VERSION" = "latest" ]; then
        info "Resolving latest release..."
        VERSION=$(curl -fsSL "$GITHUB_API_BASE_URL/repos/$REPO/releases/latest" 2>/dev/null \
            | grep '"tag_name"' | head -1 | cut -d'"' -f4) || true
        if [ -z "$VERSION" ]; then
            VERSION="2.2.1"
            warn "No release found yet — using development build ($VERSION)"
            return
        fi
    fi
    ok "Version: $VERSION"
}

# ── Checksum verification ────────────────────────────────────
# Every real release publishes SHA256SUMS alongside railhook-cli.jar (see
# .github/workflows/release-cli.yml). Refuse to install a JAR we can't
# verify against it — this is a curl-pipe installer, integrity checking is
# table stakes, not optional.
verify_checksum() {
    local tag="$1"
    local sums_url="$GITHUB_BASE_URL/$REPO/releases/download/$tag/SHA256SUMS"
    local sums_file
    sums_file="$(mktemp)"

    if ! curl -fsSL -o "$sums_file" "$sums_url" 2>/dev/null; then
        rm -f "$sums_file"
        fail "Could not download SHA256SUMS for $tag ($sums_url) — refusing to install an unverified JAR."
    fi

    local expected
    expected=$(grep 'railhook-cli\.jar' "$sums_file" | awk '{print $1}' | head -1)
    rm -f "$sums_file"
    if [ -z "$expected" ]; then
        fail "SHA256SUMS for $tag does not list railhook-cli.jar — refusing to install an unverified JAR."
    fi

    local actual
    if command -v sha256sum &>/dev/null; then
        actual=$(sha256sum "$JAR_FILE" | awk '{print $1}')
    elif command -v shasum &>/dev/null; then
        actual=$(shasum -a 256 "$JAR_FILE" | awk '{print $1}')
    else
        fail "Neither sha256sum nor shasum is available — cannot verify JAR integrity. Install one and re-run."
    fi

    if [ "$expected" != "$actual" ]; then
        rm -f "$JAR_FILE"
        fail "Checksum mismatch for railhook-cli.jar — refusing to run it.
    expected: $expected
    actual:   $actual
    The downloaded JAR does not match the published SHA256SUMS and has been
    deleted. This could mean a corrupted download or a tampered release —
    try again, and report it if it persists."
    fi

    ok "Checksum verified (sha256)"
}

# ── Download JAR ─────────────────────────────────────────────
download_jar() {
    mkdir -p "$JAR_DIR"
    JAR_FILE="$JAR_DIR/railhook-cli.jar"

    if echo "$VERSION" | grep -q "SNAPSHOT"; then
        # SNAPSHOT — try a local build first (only meaningful when this script
        # is run directly from a checked-out repo, e.g. `./install.sh`; under
        # `curl | bash` there is no local checkout, so this always falls
        # through to the nightly-artifact attempt below).
        for candidate in \
            "$(pwd)/railhook-cli/target/railhook-cli-${VERSION}.jar" \
            "$(pwd)/target/railhook-cli-${VERSION}.jar"; do
            if [ -f "$candidate" ]; then
                cp "$candidate" "$JAR_FILE"
                ok "Copied local build from $candidate (unsigned local dev build — not checksum-verified)"
                return
            fi
        done

        # Try CI artifact download
        ARTIFACT_URL="$GITHUB_BASE_URL/$REPO/releases/download/nightly/railhook-cli-${VERSION}.jar"
        info "Trying nightly build: $ARTIFACT_URL"
        if curl -fsSL -o "$JAR_FILE" "$ARTIFACT_URL" 2>/dev/null; then
            ok "Downloaded nightly build"
            if ! verify_checksum "nightly" 2>/dev/null; then
                warn "No SHA256SUMS published for the nightly build — proceeding without checksum verification."
            fi
            return
        fi

        fail "No pre-built JAR available yet. Build from source:
    git clone https://github.com/$REPO.git
    cd railhook
    mvn clean package -pl railhook-cli -am -DskipTests
    ./railhook-cli/install.sh"
    else
        DOWNLOAD_URL="$GITHUB_BASE_URL/$REPO/releases/download/$VERSION/railhook-cli.jar"
        info "Downloading railhook-cli $VERSION..."
        if ! curl -fsSL -o "$JAR_FILE" "$DOWNLOAD_URL"; then
            fail "Download failed. Check: https://github.com/$REPO/releases"
        fi
        ok "Downloaded to $JAR_FILE ($(du -h "$JAR_FILE" | cut -f1))"
        verify_checksum "$VERSION"
    fi
}

# ── Create wrapper script ────────────────────────────────────
create_wrapper() {
    mkdir -p "$INSTALL_DIR"
    WRAPPER="$INSTALL_DIR/railhook"

    cat > "$WRAPPER" << 'WRAPPER_EOF'
#!/usr/bin/env bash
set -euo pipefail
JAR_DIR="$HOME/.local/lib/railhook"
JAR_FILE="$JAR_DIR/railhook-cli.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "railhook: $JAR_FILE not found. Re-run the installer:" >&2
    echo "  curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/railhook-cli/install.sh | bash" >&2
    exit 1
fi

# shellcheck disable=SC2086
# JAVA_OPTS is intentionally unquoted so a caller can pass multiple options
# (e.g. JAVA_OPTS="-Xmx512m -Dfoo=bar") without building an array here.
exec java ${JAVA_OPTS:-} -jar "$JAR_FILE" "$@"
WRAPPER_EOF

    chmod +x "$WRAPPER"
    ok "Wrapper installed at $WRAPPER"
}

# ── Ensure PATH ──────────────────────────────────────────────
ensure_path() {
    if echo "$PATH" | tr ':' '\n' | grep -qx "$INSTALL_DIR"; then
        return
    fi

    SHELL_NAME="$(basename "${SHELL:-bash}" 2>/dev/null || echo bash)"
    case "$SHELL_NAME" in
        zsh)  PROFILE="$HOME/.zshrc" ;;
        fish) PROFILE="$HOME/.config/fish/config.fish" ;;
        *)    PROFILE="$HOME/.bashrc" ;;
    esac

    mkdir -p "$(dirname "$PROFILE")"
    touch "$PROFILE"

    if [ "$SHELL_NAME" = "fish" ]; then
        if ! grep -q "fish_add_path.*$INSTALL_DIR" "$PROFILE" 2>/dev/null; then
            {
                echo ""
                echo "# Railhook CLI"
                echo "fish_add_path $INSTALL_DIR"
            } >> "$PROFILE"
            ok "Added $INSTALL_DIR to PATH in $PROFILE (via fish_add_path)"
            warn "Run: source $PROFILE  (or restart your shell)"
        fi
        return
    fi

    if ! grep -qxF "export PATH=\"$INSTALL_DIR:\$PATH\"" "$PROFILE" 2>/dev/null; then
        {
            echo ""
            echo "# Railhook CLI"
            echo "export PATH=\"$INSTALL_DIR:\$PATH\""
        } >> "$PROFILE"
        ok "Added $INSTALL_DIR to PATH in $PROFILE"
        warn "Run: source $PROFILE  (or restart your shell)"
    fi
}

# ── Main ─────────────────────────────────────────────────────
main() {
    if [ "$DO_UNINSTALL" = "1" ]; then
        uninstall
        exit 0
    fi

    echo ""
    echo -e "${BOLD}${CYAN}  ╔═══════════════════════════════════╗${RESET}"
    echo -e "${BOLD}${CYAN}  ║   Railhook CLI Installer          ║${RESET}"
    echo -e "${BOLD}${CYAN}  ╚═══════════════════════════════════╝${RESET}"
    echo ""

    detect_os
    ensure_java
    resolve_version
    download_jar
    create_wrapper
    ensure_path

    echo ""
    # Only claim success once railhook is genuinely runnable as a bare
    # command on the CURRENT PATH. If ensure_path just appended to a shell
    # profile, that doesn't take effect until the shell is reloaded — this
    # process still won't see it, and users need to be told that plainly
    # instead of an unconditional "Installation complete!".
    if command -v railhook &>/dev/null; then
        echo -e "${GREEN}${BOLD}  Installation complete!${RESET}"
        echo ""
        echo "  Quick start:"
        echo "    railhook login              # Authenticate with your server"
        echo "    railhook listen 3000        # Start tunnel → localhost:3000"
        echo "    railhook tunnels status     # Check active tunnels"
        echo "    railhook config show        # View configuration"
        echo ""
        echo "  Config: ~/.config/railhook/config.json"
        echo "  Docs:   https://github.com/$REPO#cli-commands"
    else
        echo -e "${YELLOW}${BOLD}  railhook is installed but not yet on your PATH.${RESET}"
        echo ""
        echo "  Start a new shell (or run the export below), then verify:"
        echo "    export PATH=\"$INSTALL_DIR:\$PATH\""
        echo "    railhook --version"
    fi
    echo ""
    echo "  Uninstall: curl -fsSL https://raw.githubusercontent.com/$REPO/main/railhook-cli/install.sh | bash -s -- --uninstall"
    echo ""
}

main "$@"
