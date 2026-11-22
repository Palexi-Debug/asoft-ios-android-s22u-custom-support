# --- S22U fork: parallel copy (replaces the three sequential rsyncs of copy below) ---
# The three rsyncs ran one after the other, each one reading through apfs-fuse (a user-space file system) and
# writing HFS+: in the emulated helper VM that used about two of its eight cores (0.9 MB/s on a Galaxy S22 Ultra,
# 3 h 40 min). Here the OS tree is cut into pieces at depth 3 (a list of paths per rsync, --files-from), N rsyncs
# run at once, and the two cryptexes copy beside them. Hard links between pieces are not kept (each piece is
# one rsync); the content is the same, only a file linked in two pieces is stored twice.
# Any failure: the plain sequential copy below runs instead (rsync is incremental, so it finishes what is there).
pcopy() {
	local n=${ASOFT_COPY_JOBS:-5} i rc=0 p pids=()
	local lists="$work/lists"
	mkdir -p "$lists"
	sudo mkdir -p "$work/root/private/preboot/Cryptexes/OS" "$work/root/private/preboot/Cryptexes/App" || return 1
	python3 - "$work/os/root" "$lists" "$n" <<'PYEOF' || return 1
import os, sys
src, out, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
units = []
def walk(rel, depth):
    full = os.path.join(src, rel) if rel else src
    with os.scandir(full) as it:
        ents = sorted(it, key=lambda e: e.name)
    if not ents and rel:
        units.append(rel)           # an empty directory is a piece of its own
        return
    for e in ents:
        r = os.path.join(rel, e.name) if rel else e.name
        if depth < 3 and e.is_dir(follow_symlinks=False):
            walk(r, depth + 1)
        else:
            units.append(r)         # a file, a link, or a directory at depth 3 (rsync takes all below it)
walk('', 1)
index = {u: i for i, u in enumerate(units)}
parent = list(range(len(units)))
def find(x):
    while parent[x] != x:
        parent[x] = parent[parent[x]]
        x = parent[x]
    return x
def unit_of(rel):
    return os.path.join(*rel.split(os.sep)[:3])
# hard links: all files of one link group must go through the same rsync (-H only sees what it copies),
# so the pieces that share an inode are joined
seen = {}
def scan(full, rel):
    with os.scandir(full) as it:
        for e in it:
            r = os.path.join(rel, e.name) if rel else e.name
            try:
                if e.is_dir(follow_symlinks=False):
                    scan(e.path, r)
                elif not e.is_symlink():
                    st = e.stat(follow_symlinks=False)
                    if st.st_nlink > 1:
                        u = index.get(unit_of(r))
                        if u is None:
                            continue
                        k = (st.st_dev, st.st_ino)
                        if k in seen:
                            parent[find(u)] = find(seen[k])
                        else:
                            seen[k] = u
            except OSError:
                pass
scan(src, '')
groups = {}
for i in range(len(units)):
    groups.setdefault(find(i), []).append(i)
files = [open(os.path.join(out, str(i)), 'wb') for i in range(n)]
load = [0] * n
for k, members in enumerate(sorted(groups.values(), key=lambda m: -len(m))):
    # the biggest groups first, each to the list with the fewest pieces so far
    j = min(range(n), key=lambda i: load[i])
    load[j] += len(members)
    for m in members:
        files[j].write(units[m].encode('utf-8', 'surrogateescape') + b'\0')
for f in files:
    f.close()
print('parallel copy: %d pieces (%d linked groups joined) in %d lists' % (len(units), sum(1 for m in groups.values() if len(m) > 1), n))
PYEOF
	for ((i = 0; i < n; i++)); do
		echo "copying part $((i + 1))/$n of $work/os/root"
		sudo rsync -aHr --numeric-ids --from0 --files-from="$lists/$i" "$work/os/root/" "$work/root/" &
		pids+=($!)
	done
	echo "copying $work/sysos/root -> /private/preboot/Cryptexes/OS"
	sudo rsync -aH --numeric-ids "$work/sysos/root/" "$work/root/private/preboot/Cryptexes/OS/" &
	pids+=($!)
	echo "copying $work/appos/root -> /private/preboot/Cryptexes/App"
	sudo rsync -aH --numeric-ids "$work/appos/root/" "$work/root/private/preboot/Cryptexes/App/" &
	pids+=($!)
	for p in "${pids[@]}"; do
		wait "$p" || rc=1
	done
	# the volume's root directory itself (its owner, mode and times)
	sudo rsync -ad --numeric-ids "$work/os/root/" "$work/root/" || rc=1
	rm -rf "$lists"
	return $rc
}
if ! pcopy; then
	echo "parallel copy failed: the plain copy finishes it"
	copy "$work/os/root" "$work/root"
	copy "$work/sysos/root" "$work/root/private/preboot/Cryptexes/OS"
	copy "$work/appos/root" "$work/root/private/preboot/Cryptexes/App"
fi
# --- end of the parallel copy ---
