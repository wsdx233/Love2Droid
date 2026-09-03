#!/usr/bin/env bash
set -Eeuo pipefail

# Mirrors ProotInstaller.kt and ProotRuntime.kt with an arm64 Ubuntu guest.
# Host PRoot is used because the APK's arm64 Android executable requires
# /system/bin/linker64 and cannot run directly on a Linux workstation.

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
readonly STATE_DIR="${LOVE2DROID_PROOT_DEBUG_DIR:-$REPO_ROOT/.proot-debug}"
readonly PACKAGE_NAME="top.wsdx233.love2droid"
readonly APP_DATA_DIR="$STATE_DIR/app-data"
readonly APP_FILES_DIR="$APP_DATA_DIR/files"
readonly APP_CACHE_DIR="$APP_DATA_DIR/cache"
readonly RUNTIME_DIR="$APP_FILES_DIR/proot"
readonly ROOTFS_DIR="$RUNTIME_DIR/ubuntu"
readonly HOST_TMP_DIR="$RUNTIME_DIR/tmp"
readonly DOWNLOAD_DIR="$STATE_DIR/downloads"
readonly HOST_PACKAGE_DIR="$DOWNLOAD_DIR/host-packages"
readonly HOST_TOOLS_DIR="$STATE_DIR/host-tools"
readonly ROOTFS_MARKER="$RUNTIME_DIR/.rootfs-extracted"
readonly READY_MARKER="$RUNTIME_DIR/.setup-complete"
readonly PROOT_VERSION="v5.4.0"
readonly PROOT_COMMIT="bd5a5f63d72f8210d8cee76195eb9f0749e5bd70"
readonly PROOT_UTHASH_COMMIT="e493aa90a2833b4655927598f169c31cfcdf7861"
readonly PROOT_SOURCE_DIR="$HOST_TOOLS_DIR/proot-source-$PROOT_VERSION"
readonly LOCAL_PROOT_BIN="$HOST_TOOLS_DIR/bin/proot"

readonly UBUNTU_BASE_FILE="ubuntu-base-24.04.4-base-arm64.tar.gz"
readonly UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/$UBUNTU_BASE_FILE"
readonly UBUNTU_BASE_SHA256="04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
readonly LUA_LS_VERSION="3.19.1"
readonly LUA_LS_FILE="lua-language-server-$LUA_LS_VERSION-linux-arm64.tar.gz"
readonly LUA_LS_URL="https://github.com/LuaLS/lua-language-server/releases/download/$LUA_LS_VERSION/$LUA_LS_FILE"
readonly LUA_LS_SHA256="abd2572e8fc929dc838a81ffb8473c5bce0bf39bfe8edb4b120b3b623176ce83"
readonly OMP_INSTALL_URL="https://omp.sh/install"
readonly BASH_PROMPT_COMMIT="524ee94882449ff56fdd8a3bc7ae718ec78e4ed2"
readonly BASH_PROMPT_URL="https://raw.githubusercontent.com/Freed-Wu/bash-prompt/$BASH_PROMPT_COMMIT/prompt.sh"
readonly BASH_PROMPT_SHA256="2ae4ed25865111035b5e15c53ead9b10af7bcd731d7cb237d04aaf53d74c03e6"

readonly LUA_LS_GUEST_PATH="/opt/lua-language-server/bin/lua-language-server"
readonly OMP_GUEST_PATH="/root/.local/bin/omp"
readonly PROMPT_GUEST_PATH="/root/.local/share/bash-prompt/prompt.sh"
readonly OMP_BASHRC_ENTRY='export PATH="/root/.local/bin:$PATH"'
readonly PROMPT_BASHRC_SOURCE='. /root/.local/share/bash-prompt/prompt.sh'
readonly PROMPT_DIRTRIM_ENTRY='PROMPT_DIRTRIM=1'
readonly PROMPT_PS1_ENTRY='PS1="$(prompt_get_ps1)"'

PROOT_BIN=""
QEMU_BIN=""
HOST_LIBRARY_DIR=""
LINK2SYMLINK_WARNING_SHOWN=0
KILL_ON_EXIT_WARNING_SHOWN=0

log() {
    printf '[proot-debug] %s\n' "$*"
}

fail() {
    printf '[proot-debug] error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage:
  tools/proot-debug.sh setup
  tools/proot-debug.sh verify
  tools/proot-debug.sh shell [guest-working-directory]
  tools/proot-debug.sh exec <guest-working-directory> <command> [arguments...]
  tools/proot-debug.sh paths
  tools/proot-debug.sh host-proot [proot arguments...]

Commands:
  setup       Download local host tools, build the arm64 Ubuntu environment,
              install LuaLS, omp and bash-prompt, then smoke-test it.
  verify      Print host tool versions and exercise the installed guest.
  shell       Open the same login shell shape used by the app terminal.
              The default working directory is this repository.
  exec        Run one guest command with the app's clean environment.
  paths       Print all local state paths.
  host-proot  Invoke the locally downloaded host PRoot directly.

Environment:
  LOVE2DROID_PROOT_DEBUG_DIR  Override the ignored state directory.
  LOVE2DROID_PROOT_VERBOSE    PRoot verbosity level passed with -v.
  LOVE2DROID_PROOT_LINK2SYMLINK=1
                                Enable the Android launch flag explicitly.
  QEMU_STRACE=1               Trace guest syscalls through QEMU.

The ignored state defaults to .proot-debug/. No archive, rootfs, downloaded
binary, cache, or guest-created file is stored by Git.
EOF
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required host command is missing: $1"
}

find_executable() {
    local candidate
    for candidate in "$@"; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

ensure_host_tools() {
    require_command apt-get
    require_command dpkg-deb

    mkdir -p "$HOST_PACKAGE_DIR" "$HOST_TOOLS_DIR" "$HOST_TMP_DIR"
    local qemu_candidate
    qemu_candidate="$(find_executable \
        "$HOST_TOOLS_DIR/usr/bin/qemu-aarch64-static" \
        "$HOST_TOOLS_DIR/usr/libexec/qemu-binfmt/aarch64-binfmt-P" || true)"
    if [[ -z "$qemu_candidate" || ! -f "$HOST_TOOLS_DIR/usr/include/talloc.h" ]]; then
        log "downloading local QEMU and PRoot build dependencies"
        local package
        for package in libtalloc2 libtalloc-dev qemu-user-static; do
            if ! compgen -G "$HOST_PACKAGE_DIR/${package}_*.deb" >/dev/null; then
                (
                    cd "$HOST_PACKAGE_DIR"
                    apt-get download "$package"
                )
            fi
        done
        rm -rf "$HOST_TOOLS_DIR/usr"
        local archive
        for archive in \
            "$HOST_PACKAGE_DIR"/libtalloc2_*.deb \
            "$HOST_PACKAGE_DIR"/libtalloc-dev_*.deb \
            "$HOST_PACKAGE_DIR"/qemu-user-static_*.deb; do
            dpkg-deb --extract "$archive" "$HOST_TOOLS_DIR"
        done
    fi

    QEMU_BIN="$(find_executable \
        "$HOST_TOOLS_DIR/usr/bin/qemu-aarch64-static" \
        "$HOST_TOOLS_DIR/usr/libexec/qemu-binfmt/aarch64-binfmt-P")" ||
        fail "qemu-aarch64-static was not found after package extraction"
    local talloc_library
    talloc_library="$(find "$HOST_TOOLS_DIR/usr/lib" -type f -name 'libtalloc.so.*' -print -quit)"
    [[ -n "$talloc_library" ]] || fail "libtalloc was not found after package extraction"
    HOST_LIBRARY_DIR="$(dirname -- "$talloc_library")"

    if [[ ! -x "$LOCAL_PROOT_BIN" ]]; then
        require_command gcc
        require_command git
        require_command make
        require_command pkg-config
        log "building host PRoot $PROOT_VERSION"
        rm -rf "$PROOT_SOURCE_DIR"
        git clone \
            --depth 1 \
            --branch "$PROOT_VERSION" \
            --recurse-submodules \
            --shallow-submodules \
            https://github.com/proot-me/proot.git \
            "$PROOT_SOURCE_DIR"
        [[ "$(git -C "$PROOT_SOURCE_DIR" rev-parse HEAD)" == "$PROOT_COMMIT" ]] ||
            fail "unexpected PRoot commit"
        [[ "$(git -C "$PROOT_SOURCE_DIR/lib/uthash" rev-parse HEAD)" == "$PROOT_UTHASH_COMMIT" ]] ||
            fail "unexpected PRoot uthash commit"
        env \
            PKG_CONFIG_PATH="$HOST_LIBRARY_DIR/pkgconfig" \
            PKG_CONFIG_SYSROOT_DIR="$HOST_TOOLS_DIR" \
            make -C "$PROOT_SOURCE_DIR/src" -j2
        mkdir -p "$(dirname -- "$LOCAL_PROOT_BIN")"
        cp "$PROOT_SOURCE_DIR/src/proot" "$LOCAL_PROOT_BIN"
        chmod 0755 "$LOCAL_PROOT_BIN"
    fi
    PROOT_BIN="$LOCAL_PROOT_BIN"
}

run_host_proot() {
    env \
        TMPDIR="$HOST_TMP_DIR" \
        PROOT_TMP_DIR="$HOST_TMP_DIR" \
        LD_LIBRARY_PATH="$HOST_LIBRARY_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$PROOT_BIN" "$@"
}

run_qemu_direct() {
    local guest_executable=$1
    shift
    "$QEMU_BIN" -L "$ROOTFS_DIR" "$ROOTFS_DIR$guest_executable" "$@"
}

append_optional_proot_flags() {
    local help_text
    help_text="$(run_host_proot --help 2>&1 || true)"
    case "${LOVE2DROID_PROOT_LINK2SYMLINK:-0}" in
        0)
            if (( LINK2SYMLINK_WARNING_SHOWN == 0 )); then
                log "host ext4 uses real hard links; set LOVE2DROID_PROOT_LINK2SYMLINK=1 to debug the app flag"
                LINK2SYMLINK_WARNING_SHOWN=1
            fi
            ;;
        1)
            [[ "$help_text" == *"--link2symlink"* ]] || fail "host PRoot lacks --link2symlink"
            PROOT_ARGS+=(--link2symlink)
            ;;
        *)
            fail "LOVE2DROID_PROOT_LINK2SYMLINK must be 0 or 1"
            ;;
    esac
    if [[ "$help_text" == *"--kill-on-exit"* ]]; then
        PROOT_ARGS+=(--kill-on-exit)
    elif (( KILL_ON_EXIT_WARNING_SHOWN == 0 )); then
        log "host PRoot lacks Android fork option --kill-on-exit; foreground process cleanup remains host-managed"
        KILL_ON_EXIT_WARNING_SHOWN=1
    fi
    if [[ -n "${LOVE2DROID_PROOT_VERBOSE:-}" ]]; then
        [[ "$LOVE2DROID_PROOT_VERBOSE" =~ ^[0-9]+$ ]] ||
            fail "LOVE2DROID_PROOT_VERBOSE must be a non-negative integer"
        PROOT_ARGS+=(-v "$LOVE2DROID_PROOT_VERBOSE")
    fi
}

add_bind_if_present() {
    local source=$1
    local destination=${2:-$1}
    if [[ -e "$source" ]]; then
        if [[ "$source" == "$destination" ]]; then
            PROOT_ARGS+=(-b "$source")
        else
            PROOT_ARGS+=(-b "$source:$destination")
        fi
    fi
}

prepare_proot_args() {
    local guest_working_directory=$1
    [[ "$guest_working_directory" == /* ]] || fail "guest working directory must be absolute"
    [[ -d "$ROOTFS_DIR" ]] || fail "rootfs is missing; run setup first"

    mkdir -p "$APP_FILES_DIR" "$APP_CACHE_DIR" "$HOST_TMP_DIR"
    chmod 0777 "$HOST_TMP_DIR"

    PROOT_ARGS=(-q "$QEMU_BIN" --root-id -r "$ROOTFS_DIR")
    append_optional_proot_flags

    add_bind_if_present /dev
    add_bind_if_present /proc
    add_bind_if_present /sys

    # Model Context.filesDir/cacheDir and both Android app-data spellings.
    PROOT_ARGS+=(-b "$APP_FILES_DIR:/data/user/0/$PACKAGE_NAME/files")
    PROOT_ARGS+=(-b "$APP_DATA_DIR:/data/user/0/$PACKAGE_NAME")
    PROOT_ARGS+=(-b "$APP_CACHE_DIR:/data/user/0/$PACKAGE_NAME/cache")
    PROOT_ARGS+=(-b "$APP_FILES_DIR:/data/data/$PACKAGE_NAME/files")
    PROOT_ARGS+=(-b "$APP_CACHE_DIR:/data/data/$PACKAGE_NAME/cache")

    add_bind_if_present /storage
    add_bind_if_present /sdcard
    add_bind_if_present /mnt
    # /root is a guest-only fallback. Binding the workstation's /root here
    # would hide the guest home and usually make it unreadable.
    if [[ "$guest_working_directory" != /root && -d "$guest_working_directory" ]]; then
        add_bind_if_present "$guest_working_directory"
    fi
    add_bind_if_present /dev/urandom /dev/random
    add_bind_if_present /proc/self/fd /dev/fd
    PROOT_ARGS+=(-b "$HOST_TMP_DIR:/tmp")
    PROOT_ARGS+=(-b "$HOST_TMP_DIR:/var/tmp")
    add_bind_if_present "$ROOTFS_DIR/tmp" /dev/shm
    add_bind_if_present "$ROOTFS_DIR/proc/sys/crypto/fips_enabled" /proc/sys/crypto/fips_enabled
    PROOT_ARGS+=(-w "$guest_working_directory")
}

run_guest_with_term() {
    local guest_working_directory=$1
    local terminal_type=$2
    shift 2
    prepare_proot_args "$guest_working_directory"
    run_host_proot "${PROOT_ARGS[@]}" \
        /usr/bin/env -i \
        HOME=/root \
        LANG=C.UTF-8 \
        PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        XDG_DATA_HOME=/root/.local/share \
        XDG_CACHE_HOME=/root/.cache \
        TMPDIR=/tmp \
        R2_NOCOLOR=1 \
        TERM="$terminal_type" \
        GIT_CONFIG_COUNT=1 \
        GIT_CONFIG_KEY_0=core.createObject \
        GIT_CONFIG_VALUE_0=rename \
        "$@"
}

run_guest() {
    run_guest_with_term /root dumb "$@"
}

download_checked() {
    local url=$1
    local expected_sha256=$2
    local destination=$3
    local actual_sha256

    mkdir -p "$(dirname -- "$destination")"
    if [[ -f "$destination" ]]; then
        actual_sha256="$(sha256sum "$destination" | cut -d ' ' -f 1)"
        if [[ "$actual_sha256" == "$expected_sha256" ]]; then
            log "reusing $(basename -- "$destination")"
            return
        fi
        rm -f "$destination"
    fi

    log "downloading $url"
    local partial="$destination.part"
    rm -f "$partial"
    curl --fail --location --retry 3 "$url" -o "$partial"
    actual_sha256="$(sha256sum "$partial" | cut -d ' ' -f 1)"
    [[ "$actual_sha256" == "$expected_sha256" ]] || {
        rm -f "$partial"
        fail "SHA-256 mismatch for $(basename -- "$destination")"
    }
    mv -f "$partial" "$destination"
}

guest_group_exists() {
    local expected_gid=$1
    local name password gid members
    while IFS=: read -r name password gid members; do
        [[ "$gid" == "$expected_gid" ]] && return 0
    done <"$ROOTFS_DIR/etc/group"
    return 1
}

configure_guest_groups() {
    local -a group_ids
    local gid
    read -r -a group_ids <<<"$(id -G)"
    for gid in "${group_ids[@]}"; do
        if ! guest_group_exists "$gid"; then
            printf 'host_gid_%s:x:%s:\n' "$gid" "$gid" >>"$ROOTFS_DIR/etc/group"
        fi
    done
}

check_guest_groups() {
    local -a group_ids
    local gid
    read -r -a group_ids <<<"$(id -G)"
    for gid in "${group_ids[@]}"; do
        guest_group_exists "$gid" || fail "guest /etc/group is missing host group ID $gid"
    done
}

configure_rootfs() {
    mkdir -p \
        "$ROOTFS_DIR/etc/apt/apt.conf.d" \
        "$ROOTFS_DIR/etc/dpkg/dpkg.cfg.d" \
        "$ROOTFS_DIR/proc/sys/crypto" \
        "$ROOTFS_DIR/tmp" \
        "$ROOTFS_DIR/var/tmp" \
        "$ROOTFS_DIR/dev/shm" \
        "$ROOTFS_DIR/root/.cache" \
        "$ROOTFS_DIR/root/.local/share"
    printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\noptions use-vc timeout:2 attempts:2\n' >"$ROOTFS_DIR/etc/resolv.conf"
    printf '127.0.0.1 localhost\n::1 localhost\n' >"$ROOTFS_DIR/etc/hosts"
    configure_guest_groups
    printf 'APT::Sandbox::User "root";\n' >"$ROOTFS_DIR/etc/apt/apt.conf.d/99proot-nosandbox"
    printf 'force-unsafe-io\n' >"$ROOTFS_DIR/etc/dpkg/dpkg.cfg.d/force-unsafe-io"
    printf '0\n' >"$ROOTFS_DIR/proc/sys/crypto/fips_enabled"
    chmod 0777 "$ROOTFS_DIR/tmp" "$ROOTFS_DIR/var/tmp" "$HOST_TMP_DIR"
}

repair_rootfs_permissions() {
    local directory executable
    for directory in \
        "$ROOTFS_DIR" \
        "$ROOTFS_DIR/usr" \
        "$ROOTFS_DIR/usr/bin" \
        "$ROOTFS_DIR/usr/sbin" \
        "$ROOTFS_DIR/usr/lib" \
        "$ROOTFS_DIR/usr/lib/aarch64-linux-gnu"; do
        [[ ! -e "$directory" ]] || chmod 0755 "$directory"
    done
    for directory in "$ROOTFS_DIR/usr/bin" "$ROOTFS_DIR/usr/sbin"; do
        [[ ! -d "$directory" ]] || while IFS= read -r -d '' executable; do
            chmod 0755 "$executable"
        done < <(find "$directory" -type f -print0)
    done
    for executable in \
        usr/bin/env \
        usr/bin/dash \
        usr/bin/bash \
        usr/bin/apt-get \
        usr/bin/curl \
        usr/bin/tar \
        usr/bin/sha256sum \
        usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1; do
        [[ ! -e "$ROOTFS_DIR/$executable" ]] || chmod 0755 "$ROOTFS_DIR/$executable"
    done
}

install_rootfs() {
    if [[ -f "$ROOTFS_MARKER" && -d "$ROOTFS_DIR/bin" ]]; then
        log "reusing extracted Ubuntu rootfs"
        configure_rootfs
        repair_rootfs_permissions
        return
    fi

    local archive="$DOWNLOAD_DIR/$UBUNTU_BASE_FILE"
    download_checked "$UBUNTU_BASE_URL" "$UBUNTU_BASE_SHA256" "$archive"
    log "extracting Ubuntu Base 24.04.4 arm64"
    rm -rf "$ROOTFS_DIR"
    mkdir -p "$ROOTFS_DIR" "$HOST_TMP_DIR"
    tar --extract --gzip --file "$archive" --directory "$ROOTFS_DIR" --no-same-owner
    chmod -R u+rwX "$ROOTFS_DIR"
    configure_rootfs
    repair_rootfs_permissions
    printf 'ubuntu=24.04.4\nsha256=%s\n' "$UBUNTU_BASE_SHA256" >"$ROOTFS_MARKER"
}

install_lua_language_server() {
    if [[ -f "$ROOTFS_DIR${LUA_LS_GUEST_PATH}" ]]; then
        log "reusing Lua Language Server"
        return
    fi
    log "installing Lua Language Server $LUA_LS_VERSION"
    run_guest /bin/bash -l -c "$(cat <<EOF
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl tar
rm -rf /opt/lua-language-server
mkdir -p /opt/lua-language-server
curl --fail --location --retry 3 '$LUA_LS_URL' -o '/tmp/$LUA_LS_FILE'
echo '$LUA_LS_SHA256  /tmp/$LUA_LS_FILE' | sha256sum -c -
tar -xzf '/tmp/$LUA_LS_FILE' -C /opt/lua-language-server
rm -f '/tmp/$LUA_LS_FILE'
chmod 0755 '$LUA_LS_GUEST_PATH'
test -x '$LUA_LS_GUEST_PATH'
EOF
)"
}

install_omp() {
    if [[ -f "$ROOTFS_DIR${OMP_GUEST_PATH}" ]] &&
        [[ -f "$ROOTFS_DIR/root/.bashrc" ]] &&
        grep -Fqx "$OMP_BASHRC_ENTRY" "$ROOTFS_DIR/root/.bashrc"; then
        log "reusing omp"
        return
    fi

    # The Bun-based omp binary uses a large fixed-address ELF layout that
    # collides with PRoot's loader under QEMU. Perform omp.sh's arm64 binary
    # branch on the host, then validate the result with direct QEMU.
    local omp_host_path="$ROOTFS_DIR${OMP_GUEST_PATH}"
    if [[ ! -x "$omp_host_path" ]]; then
        log "installing omp from $OMP_INSTALL_URL"
        local release_json latest omp_partial
        release_json="$(curl -fsSL --connect-timeout 10 --max-time 60 \
            https://api.github.com/repos/can1357/oh-my-pi/releases/latest)"
        latest="$(printf '%s\n' "$release_json" |
            sed -nE 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p')"
        [[ -n "$latest" ]] || fail "failed to resolve the latest omp release"
        omp_partial="$omp_host_path.part"
        mkdir -p "$(dirname -- "$omp_host_path")"
        rm -f "$omp_partial"
        curl -fsSL --connect-timeout 10 --speed-limit 1024 --speed-time 30 \
            "https://github.com/can1357/oh-my-pi/releases/download/$latest/omp-linux-arm64" \
            -o "$omp_partial"
        chmod 0755 "$omp_partial"
        mv -f "$omp_partial" "$omp_host_path"
    else
        log "reusing downloaded omp binary"
    fi
    run_qemu_direct "$OMP_GUEST_PATH" --version >/dev/null

    touch "$ROOTFS_DIR/root/.bashrc"
    if ! grep -Fqx "$OMP_BASHRC_ENTRY" "$ROOTFS_DIR/root/.bashrc"; then
        printf '\n%s\n' "$OMP_BASHRC_ENTRY" >>"$ROOTFS_DIR/root/.bashrc"
    fi
}

install_bash_prompt() {
    if [[ -s "$ROOTFS_DIR${PROMPT_GUEST_PATH}" ]] &&
        [[ -f "$ROOTFS_DIR/root/.bashrc" ]] &&
        grep -Fqx "$PROMPT_BASHRC_SOURCE" "$ROOTFS_DIR/root/.bashrc" &&
        grep -Fqx "$PROMPT_DIRTRIM_ENTRY" "$ROOTFS_DIR/root/.bashrc" &&
        grep -Fqx "$PROMPT_PS1_ENTRY" "$ROOTFS_DIR/root/.bashrc"; then
        log "reusing bash-prompt"
        return
    fi

    log "installing bash-prompt"
    run_guest /bin/bash -l -c "$(cat <<'EOF'
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ncurses-bin
EOF
)"

    local prompt_host_path="$ROOTFS_DIR${PROMPT_GUEST_PATH}"
    download_checked "$BASH_PROMPT_URL" "$BASH_PROMPT_SHA256" "$prompt_host_path"
    bash -n "$prompt_host_path"
    chmod 0644 "$prompt_host_path"
    touch "$ROOTFS_DIR/root/.bashrc"
    if ! grep -Fqx "$PROMPT_BASHRC_SOURCE" "$ROOTFS_DIR/root/.bashrc"; then
        printf '\n%s\n' "$PROMPT_BASHRC_SOURCE" >>"$ROOTFS_DIR/root/.bashrc"
    fi
    if ! grep -Fqx "$PROMPT_DIRTRIM_ENTRY" "$ROOTFS_DIR/root/.bashrc"; then
        printf '%s\n' "$PROMPT_DIRTRIM_ENTRY" >>"$ROOTFS_DIR/root/.bashrc"
    fi
    if ! grep -Fqx "$PROMPT_PS1_ENTRY" "$ROOTFS_DIR/root/.bashrc"; then
        printf '%s\n' "$PROMPT_PS1_ENTRY" >>"$ROOTFS_DIR/root/.bashrc"
    fi
}

check_ready_files() {
    [[ -d "$ROOTFS_DIR" ]] || fail "Ubuntu rootfs is missing"
    [[ -x "$ROOTFS_DIR${LUA_LS_GUEST_PATH}" ]] || fail "LuaLS is missing"
    [[ -x "$ROOTFS_DIR${OMP_GUEST_PATH}" ]] || fail "omp is missing"
    [[ -s "$ROOTFS_DIR${PROMPT_GUEST_PATH}" ]] || fail "bash-prompt is missing"
    [[ -f "$ROOTFS_DIR/root/.bashrc" ]] || fail "/root/.bashrc is missing"
    grep -Fqx "$OMP_BASHRC_ENTRY" "$ROOTFS_DIR/root/.bashrc" || fail "omp PATH entry is missing"
    grep -Fqx "$PROMPT_BASHRC_SOURCE" "$ROOTFS_DIR/root/.bashrc" || fail "bash-prompt source entry is missing"
    grep -Fqx "$PROMPT_DIRTRIM_ENTRY" "$ROOTFS_DIR/root/.bashrc" || fail "PROMPT_DIRTRIM entry is missing"
    grep -Fqx "$PROMPT_PS1_ENTRY" "$ROOTFS_DIR/root/.bashrc" || fail "PS1 entry is missing"
    check_guest_groups
}

smoke_guest() {
    log "smoke-testing arm64 guest"
    run_guest /bin/bash -c "$(cat <<'EOF'
set -eu
test "$(uname -m)" = aarch64
test "$HOME" = /root
test "$LANG" = C.UTF-8
test "$TMPDIR" = /tmp
test "$GIT_CONFIG_KEY_0" = core.createObject
test "$GIT_CONFIG_VALUE_0" = rename
printf 'guest-arch=%s\n' "$(uname -m)"
printf 'guest-os=%s\n' "$(. /etc/os-release && printf '%s' "$PRETTY_NAME")"
printf 'bash=%s\n' "${BASH_VERSION}"
printf 'lua-language-server='
/opt/lua-language-server/bin/lua-language-server --version
EOF
)"
    # Bun's large fixed-address ELF collides with PRoot's loader under QEMU.
    # Direct QEMU still validates the exact arm64 binary installed for Android.
    printf 'omp-qemu-direct='
    run_qemu_direct "$OMP_GUEST_PATH" --version
}

write_ready_marker() {
    cat >"$READY_MARKER" <<EOF
ubuntu=24.04.4
lua-language-server=$LUA_LS_VERSION
omp=/root/.local/bin/omp
bash-prompt=$BASH_PROMPT_COMMIT
abi=arm64-v8a
EOF
}

setup_environment() {
    require_command curl
    require_command sha256sum
    require_command tar
    ensure_host_tools
    mkdir -p "$APP_FILES_DIR" "$APP_CACHE_DIR" "$RUNTIME_DIR" "$HOST_TMP_DIR" "$DOWNLOAD_DIR"
    install_rootfs
    install_lua_language_server
    install_omp
    install_bash_prompt
    repair_rootfs_permissions
    check_ready_files
    smoke_guest
    write_ready_marker
    rm -f "$DOWNLOAD_DIR/$UBUNTU_BASE_FILE"
    log "environment ready: $STATE_DIR"
}

verify_environment() {
    ensure_host_tools
    check_ready_files
    run_host_proot --version >/dev/null
    printf 'host-proot=%s (%s)\n' "$PROOT_VERSION" "${PROOT_COMMIT:0:12}"
    printf 'host-qemu=%s\n' "$("$QEMU_BIN" --version | sed -n '1p')"
    smoke_guest
    [[ -f "$READY_MARKER" ]] || fail "setup marker is missing"
}

print_paths() {
    cat <<EOF
state=$STATE_DIR
host-tools=$HOST_TOOLS_DIR
app-data=$APP_DATA_DIR
rootfs=$ROOTFS_DIR
host-tmp=$HOST_TMP_DIR
ready-marker=$READY_MARKER
EOF
}

main() {
    local action=${1:-}
    case "$action" in
        setup)
            [[ $# -eq 1 ]] || fail "setup takes no arguments"
            setup_environment
            ;;
        verify)
            [[ $# -eq 1 ]] || fail "verify takes no arguments"
            verify_environment
            ;;
        shell)
            [[ $# -le 2 ]] || fail "shell accepts at most one guest working directory"
            ensure_host_tools
            check_ready_files
            local shell_working_directory=${2:-$REPO_ROOT}
            run_guest_with_term "$shell_working_directory" xterm-256color /bin/bash -l
            ;;
        exec)
            [[ $# -ge 3 ]] || fail "exec requires a guest working directory and command"
            ensure_host_tools
            check_ready_files
            local exec_working_directory=$2
            shift 2
            run_guest_with_term "$exec_working_directory" dumb "$@"
            ;;
        paths)
            [[ $# -eq 1 ]] || fail "paths takes no arguments"
            print_paths
            ;;
        host-proot)
            shift
            ensure_host_tools
            run_host_proot "$@"
            ;;
        -h|--help|help)
            usage
            ;;
        '')
            usage >&2
            exit 2
            ;;
        *)
            usage >&2
            fail "unknown command: $action"
            ;;
    esac
}

main "$@"
