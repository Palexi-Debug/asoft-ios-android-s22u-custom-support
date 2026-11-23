#!/usr/bin/env python3
"""fetch_member.py URL MEMBER OUT: one member of a remote zip (HTTP range requests), decompressed into OUT."""
import io, sys, urllib.request, zipfile

class Remote(io.RawIOBase):
    def __init__(self, url, chunk=16 << 20):
        self.url, self.chunk, self.pos = url, chunk, 0
        req = urllib.request.Request(url, method='HEAD')
        self.size = int(urllib.request.urlopen(req).headers['Content-Length'])
        self.buf, self.off = b'', 0
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.pos
    def seek(self, o, w=0):
        self.pos = o if w == 0 else self.pos + o if w == 1 else self.size + o
        return self.pos
    def readinto(self, b):
        n = len(b)
        if n == 0 or self.pos >= self.size:
            return 0
        if not (self.off <= self.pos < self.off + len(self.buf)):
            end = min(self.size, self.pos + max(n, self.chunk)) - 1
            req = urllib.request.Request(self.url, headers={'Range': 'bytes=%d-%d' % (self.pos, end)})
            self.buf, self.off = urllib.request.urlopen(req).read(), self.pos
        s = self.pos - self.off
        d = self.buf[s:s + n]
        b[:len(d)] = d
        self.pos += len(d)
        return len(d)

url, member, out = sys.argv[1:4]
zf = zipfile.ZipFile(io.BufferedReader(Remote(url), 1 << 20))
info = zf.getinfo(member)
print('member', member, info.file_size, 'bytes (zip', info.compress_size, ')', flush=True)
with zf.open(info) as src, open(out, 'wb') as dst:
    done = 0
    while True:
        c = src.read(8 << 20)
        if not c:
            break
        dst.write(c)
        done += len(c)
        if done % (256 << 20) < (8 << 20):
            print('  %d MB' % (done >> 20), flush=True)
print('done', done)
