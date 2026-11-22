#!/bin/bash
# Tests pcopy.sh on a made-up tree (CI): deep and shallow directories, empty ones, links, odd names, a hard link
# inside one piece, modes and owners, and compares the result with the source.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
work=$T
mkdir -p "$T/os/root" "$T/sysos/root/System/Library/Caches/com.apple.dyld" "$T/appos/root/Applications" "$T/root"
cd "$T/os/root"
mkdir -p System/Library/Frameworks/A.framework/Versions/A System/Library/PrivateFrameworks/B.framework usr/lib usr/bin private/var/mobile private/preboot bin sbin Applications/Foo.app emptytop System/Empty deep/a/b/c/d/e
for i in $(seq 1 40); do mkdir -p "System/Library/PrivateFrameworks/F$i.framework"; head -c $((i * 1000)) /dev/urandom > "System/Library/PrivateFrameworks/F$i.framework/F$i"; done
head -c 100000 /dev/urandom > usr/lib/libx.dylib
ln usr/lib/libx.dylib usr/lib/libx-hard.dylib
ln -s libx.dylib usr/lib/libx-link.dylib
ln usr/lib/libx.dylib System/Library/Frameworks/A.framework/libx-cross
ln usr/lib/libx.dylib System/Library/PrivateFrameworks/F7.framework/libx-cross2
ln -s /private/var linkdir
echo hi > "usr/bin/with space"
printf 'x' > $'usr/bin/new\nline'
echo u > "usr/bin/ünï"
echo r > rootfile
chmod 0755 usr/bin; chmod 0555 System/Library/Frameworks; chmod 0700 private/var/mobile
head -c 1000000 /dev/zero > deep/a/b/c/d/e/zeros
chown -R 501:501 private/var/mobile || true
echo c > "$T/sysos/root/System/Library/Caches/com.apple.dyld/cache"
echo a > "$T/appos/root/Applications/app"
sudo chown -R root:root "$T/os" || true
. "$HERE/pcopy.sh" || true
# the plain way, for the comparison
copy() { sudo mkdir -p "$2"; sudo rsync -aH --numeric-ids "$1/" "$2/"; }
mkdir -p "$T/ref"
copy "$T/os/root" "$T/ref"
copy "$T/sysos/root" "$T/ref/private/preboot/Cryptexes/OS"
copy "$T/appos/root" "$T/ref/private/preboot/Cryptexes/App"
echo "== differences against the plain copy (nothing below the line means equal)"
d=$(sudo rsync -naic --omit-dir-times --numeric-ids "$T/ref/" "$T/root/" || true)
echo "$d"
[ -z "$d" ] || { echo "DIFFERENT"; exit 1; }
echo "== hard link group across pieces:"
n=$(stat -c %i "$T/root/usr/lib/libx.dylib" "$T/root/usr/lib/libx-hard.dylib" "$T/root/System/Library/Frameworks/A.framework/libx-cross" "$T/root/System/Library/PrivateFrameworks/F7.framework/libx-cross2" | sort -u | wc -l)
[ "$n" = 1 ] || { echo "LINKS NOT KEPT ($n inodes)"; exit 1; }
echo "kept: one inode"
ls -ld "$T/root" "$T/root/System/Library/Frameworks" "$T/root/private/var/mobile"
echo "PCOPY TEST OK"
