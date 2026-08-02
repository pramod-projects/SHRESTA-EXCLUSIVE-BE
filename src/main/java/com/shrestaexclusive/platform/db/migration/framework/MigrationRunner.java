package com.shrestaexclusive.platform.db.migration.framework;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Executes a list of TransitionPlans against a JDBC Connection.
 *
 * Direct Java translation of the Haskell walkPlanList / walkPlan in
 * CreditPlatform.Migrations.Migration (euler-credit-db).
 *
 * Key behaviours (matching the Haskell exactly):
 *
 *  1. Per-table version is read from shresta_table_migration_versions before
 *     any transition runs. Rows start absent (treated as version -1).
 *
 *  2. Each Transition carries a fromVersions list. The transition runs only
 *     when the current table version is in that list. Otherwise it is SKIPPED
 *     and the next transition is evaluated — matching Haskell's "skip" log.
 *
 *  3. A final version record is always written even when no transitions ran,
 *     to make the current state visible (matching setTableVersion at TransitionEnd).
 *
 *  4. The plan list is walked in a loop (walkPlanList round). Plans that are
 *     already at their terminal version or made no progress are not re-queued.
 *     This handles potential cross-table dependency ordering.
 *
 *  5. All decisions (current version, perform, skip, finish) are logged to
 *     stdout so the migration run is auditable.
 *
 * Thread-safety: assumes single-threaded Flyway migration context.
 */
public final class MigrationRunner {

    static final String VERSION_TABLE = "shresta_table_migration_versions";

    private MigrationRunner() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Run all given transition plans.
     * Matches: executeTransitionPlanList mainTransitionPlanList
     */
    public static void run(Connection conn, List<TransitionPlan> plans) throws SQLException {
        ensureVersionTableExists(conn);
        walkPlanList(conn, new ArrayList<>(plans));
    }

    // =========================================================================
    // walkPlanList — loop until all plans are finished or progress stalls
    // =========================================================================

    /**
     * Matches: walkPlanList in Haskell.
     *
     * Processes all plans in a single pass. Plans that cannot progress yet
     * (no matching fromVersions for current table version) are re-queued for a
     * second pass, allowing dependency ordering to resolve naturally.
     * If a full pass yields no progress at all, migration fails fast.
     */
    private static void walkPlanList(Connection conn, List<TransitionPlan> pending) throws SQLException {
        boolean anyProgress;
        do {
            anyProgress = false;
            List<TransitionPlan> deferred = new ArrayList<>();
            for (TransitionPlan plan : pending) {
                boolean progressed = walkPlan(conn, plan);
                if (!progressed) {
                    deferred.add(plan);
                } else {
                    anyProgress = true;
                }
            }
            // Re-queue only plans that made no progress; retry if others did
            pending = deferred;
        } while (!pending.isEmpty() && anyProgress);

        if (!pending.isEmpty()) {
            List<String> stuck = pending.stream().map(TransitionPlan::tableName).toList();
            throw new IllegalStateException(
                "Migration stalled — no progress on: " + stuck +
                ". Check version state in " + VERSION_TABLE);
        }
    }

    // =========================================================================
    // walkPlan — apply one TransitionPlan
    // =========================================================================

    /**
     * Matches: walkPlan / go in Haskell.
     *
     * Reads the current table version, iterates over transitions, executes any
     * whose fromVersions contains the current version, skips the rest.
     * Persists the final reached version after processing.
     *
     * @return true if at least one transition was executed (progress happened)
     */
    private static boolean walkPlan(Connection conn, TransitionPlan plan) throws SQLException {
        int current = getTableVersion(conn, plan.tableName());
        log("  Migrate table: " + plan.tableName());
        log("    Current version: " + current);

        boolean progressHappened = false;

        for (Transition t : plan.transitions()) {
            if (t.fromVersions().contains(current)) {
                log("    Transition: " + t.fromVersions() + " -> " + t.toVersion() + ": perform");
                for (String sql : t.sql()) {
                    try (Statement st = conn.createStatement()) {
                        st.execute(sql);
                    }
                }
                setTableVersion(conn, plan.tableName(), t.toVersion());
                current = t.toVersion();
                progressHappened = true;
            } else {
                log("    Transition: " + t.fromVersions() + " -> " + t.toVersion() + ": skip");
            }
        }

        // Always persist final reached version (matches TransitionEnd's setTableVersion)
        setTableVersion(conn, plan.tableName(), current);
        log("    Finished at version: " + current);

        return progressHappened;
    }

    // =========================================================================
    // Version table helpers — match getTableVersion / setTableVersion in Haskell
    // =========================================================================

    private static void ensureVersionTableExists(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS shresta_table_migration_versions (
                    table_name  VARCHAR(120) PRIMARY KEY,
                    version     INTEGER      NOT NULL,
                    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
                )
                """);
        }
    }

    /**
     * Matches: getTableVersion — returns -1 when no row exists (table not yet migrated).
     */
    static int getTableVersion(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version FROM " + VERSION_TABLE + " WHERE table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("version") : -1;
            }
        }
    }

    /**
     * Matches: setTableVersion — upserts the version for a table.
     */
    static void setTableVersion(Connection conn, String tableName, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO shresta_table_migration_versions (table_name, version, applied_at)
                VALUES (?, ?, now())
                ON CONFLICT (table_name) DO UPDATE
                  SET version = EXCLUDED.version, applied_at = now()
                """)) {
            ps.setString(1, tableName);
            ps.setInt(2, version);
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // Logging — matches migrationLog in Haskell
    // =========================================================================

    private static void log(String msg) {
        System.out.println("[shresta-migration] " + msg);
    }
}

