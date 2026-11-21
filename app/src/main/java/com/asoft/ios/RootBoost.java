package com.asoft.ios;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Optional extras for a ROOTED phone (S22 Ultra fork). Without root (no "su", or the user refuses the grant) every
 * call does nothing and costs nothing after the first check.
 *
 * Why: a crash record of a first setup on a Galaxy S22 Ultra (Exynos 2200) showed
 *  - the helper VM running on "cpuset 0-2" only, the three slowest cores, for 8 hours (the iOS start itself got the
 *    fast cores 4-7), and
 *  - Android's memory killer ending the app (LOW_MEMORY) during the first start of iOS.
 * With root this does two things to QEMU and its stub processes (and the app):
 *  1. moves every thread into the "top-app" cpuset (all cores, as a visible app has them);
 *  2. lowers oom_score_adj, so that the memory killer takes other apps first.
 * Nothing here is needed for the app to work, and none of it is tested on a phone yet: the result of every try is
 * written to the app's log ("root boost: ...") so the next report shows what happened.
 *
 * Never call from the UI thread: "su" can wait for the user's grant dialog (the {@link #async} variants are for
 * callers that must not block).
 */
final class RootBoost {
    private RootBoost() {}

    private static final Object LOCK = new Object();
    /** 0 = not asked yet, 1 = root works, -1 = no root (asked again after RETRY_MS). */
    private static int state;
    private static long askedAt;
    private static final long RETRY_MS = 10 * 60_000L;
    private static final int OOM_APP = -500, OOM_QEMU = -500;

    /** The cached answer without asking "su": 1 root works, -1 no root, 0 not checked yet. Safe on the UI thread. */
    static int state() {
        synchronized (LOCK) {
            return state;
        }
    }

    private static boolean probed;

    /** Once per process, on a thread of its own: asks "su" so that state() is known (a rooted phone shows its grant dialog now). */
    static void probeOnce(Context c) {
        synchronized (LOCK) {
            if (probed) {
                return;
            }
            probed = true;
        }
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> available(app), "root-probe");
        t.setDaemon(true);
        t.start();
    }

    static boolean works() {
        return state() == 1;
    }

    /** Forgets a "no root" answer (the user may have granted it since), so that the next available() asks again. */
    static void recheck() {
        synchronized (LOCK) {
            if (state == -1) {
                state = 0;
            }
        }
    }

    /** Whether "su" gives uid 0 here. Cached; may block up to ~20 s for a grant dialog. Not on the UI thread. */
    static boolean available(Context c) {
        synchronized (LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (state == 1 || state == -1 && now - askedAt < RETRY_MS) {
                return state == 1;
            }
            askedAt = now;
            String out = run("id -u", 20_000);
            state = out != null && out.trim().equals("0") ? 1 : -1;
            AppLog.note(c, "root boost: " + (state == 1 ? "root is available" : "no root (" + (out == null ? "su failed" : out.trim()) + ")"));
            return state == 1;
        }
    }

    /** Runs @script as root in "su -c"; the output, or null when su could not be run or timed out. */
    static String run(String script, int timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            final Process pp = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(pp.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        synchronized (sb) {
                            if (sb.length() < 8192) {
                                sb.append(l).append('\n');
                            }
                        }
                    }
                } catch (IOException e) {
                    // ends with the process
                }
            }, "root-boost-read");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(1000);
            synchronized (sb) {
                return sb.toString();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /**
     * QEMU @pid and everything below it (the native engine's stubs): all threads into the fastest cpuset the phone
     * has, oom_score_adj down. Returns what the shell printed (the cores and the score afterwards), or null without root.
     */
    static String boost(Context c, int pid, String what) {
        if (pid <= 0 || !available(c)) {
            return null;
        }
        int self = android.os.Process.myPid();
        String script = ""
                + "CS=/dev/cpuset/top-app; [ -w $CS/tasks ] || CS=/dev/cpuset/foreground; [ -w $CS/tasks ] || CS=;\n"
                + "pids=\"" + pid + "\"; frontier=\"" + pid + "\";\n"
                // children, three generations deep (QEMU -> stubs -> their helpers)
                + "for g in 1 2 3; do next=; for p in $frontier; do\n"
                + "  k=$(ps -A -o PID= -o PPID= | while read a b; do [ \"$b\" = \"$p\" ] && echo $a; done); next=\"$next $k\"; done;\n"
                + "  pids=\"$pids $next\"; frontier=\"$next\"; done\n"
                + "for p in $pids; do\n"
                + "  [ -d /proc/$p ] || continue\n"
                + "  [ -n \"$CS\" ] && for t in /proc/$p/task/*; do echo ${t##*/} > $CS/tasks 2>/dev/null; done\n"
                + "  echo " + OOM_QEMU + " > /proc/$p/oom_score_adj 2>/dev/null\n"
                + "done\n"
                + "echo " + OOM_APP + " > /proc/" + self + "/oom_score_adj 2>/dev/null\n"
                + "echo \"cpuset=${CS:-none} qemu: $(grep Cpus_allowed_list /proc/" + pid + "/status 2>/dev/null) oom=$(cat /proc/" + pid
                + "/oom_score_adj 2>/dev/null) app-oom=$(cat /proc/" + self + "/oom_score_adj 2>/dev/null) procs=$(echo $pids | wc -w)\"\n";
        String out = run(script, 15_000);
        String line = out == null ? "su failed or timed out" : out.trim().replace('\n', ' ');
        AppLog.note(c, "root boost (" + what + "): " + line);
        return line;
    }

    /** {@link #boost} on a thread of its own, for callers that hold locks or must not wait on "su". */
    static void async(Context c, int pid, String what) {
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> boost(app, pid, what), "root-boost");
        t.setDaemon(true);
        t.start();
    }

    /** "am kill-all" as root, as the wireless-adb shell does it; true when it ran. Not on the UI thread. */
    static boolean killAll(Context c, int timeoutMs) {
        if (!available(c)) {
            return false;
        }
        String out = run("am kill-all", timeoutMs);
        return out != null;
    }
}
