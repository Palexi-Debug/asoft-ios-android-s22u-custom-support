#!/system/bin/sh
# Copies the finished iOS 16 install of the normal ASoftware app (com.asoft.ios) into the Dev build of the
# fork (com.asoft.ios.dev), so the Dev app does not need its own 8-hour setup. Needs root.
#
# Normally the Dev app runs this itself: Settings > "Copy iOS from the normal app (root)" (NO_STOP_DST=1).
# By hand: install the Dev APK, open it once (that creates its data folder), then in a root shell:
#   su -c 'sh /sdcard/Download/copy-ios-to-dev.sh'
#
# NOT TESTED ON A PHONE. What it relies on (from the app's source): iOS counts as installed when
# files/ios16/disk.qcow2 and files/ios16/payload.bin exist (Installer.installed). The copy is made next to the
# target and moved into place at the end, so an interrupted run leaves the Dev app as it was.

SRC_PKG=com.asoft.ios
DST_PKG=com.asoft.ios.dev
SRC=/data/data/$SRC_PKG/files/ios16
DST_FILES=/data/data/$DST_PKG/files

if [ "$(id -u)" != "0" ]; then
    echo "Run as root: su -c 'sh $0'"
    exit 1
fi
if [ ! -f "$SRC/disk.qcow2" ] || [ ! -f "$SRC/payload.bin" ]; then
    echo "No finished iOS 16 install in $SRC_PKG (disk.qcow2 and payload.bin are needed). Nothing copied."
    exit 1
fi
if [ ! -d "/data/data/$DST_PKG" ]; then
    echo "Install the Dev app ($DST_PKG) and open it once, then run this again."
    exit 1
fi

# both apps off, so that nothing writes while the files are copied
am force-stop $SRC_PKG
[ -n "$NO_STOP_DST" ] || am force-stop $DST_PKG     # the app itself runs this: it must not stop itself

need_kb=$(du -sk "$SRC" | cut -f1)
set -- $(df -k /data | tail -n 1)
avail_kb=$4
echo "iOS folder: $((need_kb / 1024)) MB, free on /data: $((avail_kb / 1024)) MB"
if [ "$avail_kb" -lt "$((need_kb + 2097152))" ]; then
    echo "Not enough free space (needs the folder's size plus 2 GB). Nothing copied."
    exit 1
fi

mkdir -p "$DST_FILES" || exit 1
rm -rf "$DST_FILES/ios16.tmp"
echo "Copying ... (some minutes)"
cp -a "$SRC" "$DST_FILES/ios16.tmp" || { echo "Copy failed"; rm -rf "$DST_FILES/ios16.tmp"; exit 1; }

U=$(stat -c %u "/data/data/$DST_PKG")
G=$(stat -c %g "/data/data/$DST_PKG")
chown -R "$U:$G" "$DST_FILES/ios16.tmp"
restorecon -R "$DST_FILES/ios16.tmp" 2>/dev/null

rm -rf "$DST_FILES/ios16"
mv "$DST_FILES/ios16.tmp" "$DST_FILES/ios16" || exit 1

ls -l "$DST_FILES/ios16" | head -20
echo "Done. Open the Dev app: it should list iOS 16 as installed. The first start (iOS sets itself up) still has to run there."
