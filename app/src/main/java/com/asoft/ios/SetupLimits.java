package com.asoft.ios;

/**
 * Every wait limit of the phone's setup, in one place. A limit is a number of minutes of NO sign of life (or a
 * ceiling on the whole wait), never the length of a healthy run: a phone that is slow but still working is let
 * finish. The S25's own times, for the scale: iOS 16 first start 23 min; iOS 26 DataMigration 50-60 min (62 on
 * 2026-11-09, 58 on a Xiaomi 24129PN74G); notes/KNOWN-ISSUES.md.
 */
final class SetupLimits {
    private SetupLimits() {
    }

    static final long MIN = 60_000L;

    // -- iOS 16, first start (PhoneSetup.firstStart) --------------------------------------------------------

    /**
     * No console text at all this long after the boot began = it is not running (#23: no serial.txt ever, the
     * first report). A TCG-only phone prints its first kernel lines within a few minutes; 30 is generous.
     */
    static final long FIRST_START_NO_CONSOLE_MS = 30 * MIN;

    /**
     * The console showed nothing it had not shown before, no milestone, for this long. #59 (Helio G99) was still
     * printing new `exec ...` lines after 2 h, so new text counts; a console that only repeats itself for an hour
     * is a loop or a hang. A guess: no report has shown a silent but healthy first start longer than a few minutes.
     */
    static final long FIRST_START_QUIET_MS = 60 * MIN;

    /**
     * Whatever the signs, stop here. 8 h = 20 times the S25's 23 min; the two slowest reports (#59, #23) were
     * past 2 h, and 2 h was the old limit.
     */
    static final long FIRST_START_CEILING_MS = 16 * 60 * MIN;   // s22u fork: 8 h was hit at 95 % on an Exynos 2200 that still started new daemons

    // -- iOS 26, DataMigration (PhoneSetup26.iosWallpaper) ---------------------------------------------------
    // All three count from the last answer of iOS's shell, so they only run once it has gone silent. A silent shell
    // can come back by itself: #19 after about 13 min, one K18 phone after 69 (k18-stall.md fact 9), and the S25's
    // setup of 2026-11-14 after 26 min with the pid counter racing all the while (past 13,850): DataMigration
    // finished inside that churn and the disk was good. Boots that never came back were silent for 125 min (#55)
    // and 146 min (the HONOR) when the old 3 h limit ended them. So a racing pid counter is no proof of a dead
    // boot, and the two limits that a guest with a moving console can reach are 120 min: longer than every
    // silence that ended well, shorter than every one that did not.

    /**
     * Shell silent and the console's pid counter racing (k18-stall.md fact 3, 4: launchd respawns bash every
     * ~10 s, pids 24,746 to 25,302 in #55's tail, 66,012 on the HONOR, 26,049 on the S25's failed boot of
     * 2026-11-08; a healthy boot ends near 1,250). It names the stall in the log and for the user; it ends the
     * boot no sooner than plain silence does (it was 40 min, which would have cut the S25's boot of 2026-11-14
     * off 14 min after it had finished by itself).
     */
    static final long MIGRATION_CHURN_SILENT_MS = 120 * MIN;

    /**
     * Shell silent and no sign of life: no line the console has not printed before, no higher pid, no new frame
     * (#65: only our own polls echoed back). This is the rule that ends a guest that is not running at all; a
     * churn keeps starting programs, so its pids rise and it is ended by the limit above.
     */
    static final long MIGRATION_DEAD_SILENT_MS = 60 * MIN;

    /** Shell silent with no other explanation; the old 3 h limit counted from the start, this from the last answer. */
    static final long MIGRATION_SILENT_MS = 120 * MIN;

    /** Counted as racing: the console printed a pid this high (healthy boots end near 1,250)... */
    static final int CHURN_PID = 5_000;
    /** ...and it rose this much since the shell last answered. */
    static final int CHURN_RISE = 1_000;

    /**
     * The shell answers but DataMigration is not done: a slow phone. 5 h = about 5 times the S25 (the old limit
     * was 3 h and only stalled guests reached it). A chosen number, not measured.
     */
    static final long MIGRATION_CEILING_MS = 5 * 60 * MIN;

    /** The same stall this many times in a row: say so and ask for a report instead of looping. */
    static final int STALLS_BEFORE_REPORT = 3;
}
