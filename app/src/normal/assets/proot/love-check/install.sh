#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive
# Ubuntu Base excludes man pages, but love's postinst registers its man page
# with update-alternatives. Preserve only LÖVE's pages, not all documentation.
printf '%s\n' 'path-include=/usr/share/man/man6' \
    'path-include=/usr/share/man/man6/love*' \
    > /etc/dpkg/dpkg.cfg.d/love2droid-love-check
apt-get update
# A previous install may already have unpacked love without its man page.
# Configuring again cannot restore excluded files; re-unpack just this package.
if [ -f /usr/bin/love-11.5 ] && [ ! -f /usr/share/man/man6/love-11.5.6.gz ]; then
    # apt --reinstall can fail with "No file name" for a half-configured package.
    # Download the pinned archive, restore its payload, then let apt configure it.
    (
        repair_dir="$(mktemp -d)"
        trap 'rm -rf "$repair_dir"' 0
        cd "$repair_dir"
        apt-get download love=11.5-1build1
        dpkg --unpack ./love_11.5-1build1_*.deb
    )
fi
apt-get install -y --no-install-recommends \
    love=11.5-1build1 luajit libluajit-5.1-2 python3 \
    xvfb libgl1-mesa-dri libglx-mesa0
# Do not accept a different engine version or mark a merely installed stack ready.
test "$(dpkg-query -W -f='${Version}' love)" = '11.5-1build1'
/usr/local/bin/love-check doctor --frames 3 --timeout 60
