#!/usr/bin/env python3
"""patch-helper-initrd.py IN.gz OUT.gz [PCOPY.sh]

Rewrites asoft/mkiosroot.sh inside the helper VM's initramfs (a gzip'd "newc" cpio archive): its three
sequential `copy` calls become tools/helper-patch/pcopy.sh (parallel rsyncs, plain copy as the fallback).
Every other entry is copied byte for byte (device nodes, owners and modes stay, which unpacking and
repacking as a normal user would lose). If the three lines are not found (a newer helper image), nothing
is changed and the file is copied as it is: exit status 3 says so.
"""
import gzip, os, sys

OLD = (b'copy "$work/os/root" "$work/root"\n'
       b'copy "$work/sysos/root" "$work/root/private/preboot/Cryptexes/OS"\n'
       b'copy "$work/appos/root" "$work/root/private/preboot/Cryptexes/App"\n')


def pad4(n):
    return (4 - n % 4) % 4


def main():
    src, dst = sys.argv[1], sys.argv[2]
    pc = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(os.path.abspath(__file__)), 'helper-patch', 'pcopy.sh')
    new = open(pc, 'rb').read()
    raw = gzip.open(src, 'rb').read()
    out = bytearray()
    pos = 0
    changed = 0
    while pos + 110 <= len(raw):
        if raw[pos:pos + 6] != b'070701':
            raise SystemExit('not a newc cpio at %d' % pos)
        f = [int(raw[pos + 6 + 8 * i:pos + 14 + 8 * i], 16) for i in range(13)]
        filesize, namesize = f[6], f[11]
        name_at = pos + 110
        name = raw[name_at:name_at + namesize - 1].decode('utf-8', 'surrogateescape')
        data_at = name_at + namesize + pad4(110 + namesize)
        data = raw[data_at:data_at + filesize]
        end = data_at + filesize + pad4(filesize)
        if name.rstrip('/').endswith('asoft/mkiosroot.sh') and OLD in data:
            data = data.replace(OLD, new, 1)
            changed += 1
            hdr = bytearray(raw[pos:name_at])
            hdr[6 + 8 * 6:6 + 8 * 7] = b'%08X' % len(data)
            out += hdr + raw[name_at:data_at] + data + b'\0' * pad4(len(data))
        else:
            out += raw[pos:end]
        pos = end
        if name == 'TRAILER!!!':
            out += raw[pos:]            # whatever follows the trailer (padding)
            break
    with gzip.open(dst, 'wb', compresslevel=9) as g:
        g.write(bytes(out))
    print('patched %d entr%s, %d -> %d bytes (unpacked)' % (changed, 'y' if changed == 1 else 'ies', len(raw), len(out)))
    return 0 if changed else 3


if __name__ == '__main__':
    sys.exit(main())
