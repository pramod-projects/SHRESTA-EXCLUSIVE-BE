package com.shrestaexclusive.platform.db.migration.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines a table's full migration history as a sequence of versioned transitions.
 *
 * Analogous to the Haskell TransitionPlan / buildTransitionPlan in
 * CreditPlatform.Migrations.Migration. Each table owns a static transitionPlan()
 * factory that describes every schema change from version -1 (non-existent) onward.
 *
 * Usage (per-table migration class):
 * <pre>
 *   public static TransitionPlan transitionPlan() {
 *       return TransitionPlan.forTable("category_family_config")
 *           // v-1 → v0 : initial CREATE TABLE
 *           .transition(-1, 0, List.of("""
 *               CREATE TABLE category_family_config (
 *                   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *                   ...
 *               )
 *               """))
 *           // v0 → v1 : future ALTER TABLE
 *           // .transition(0, 1, List.of("ALTER TABLE category_family_config ADD COLUMN ..."))
 *           .build();
 *   }
 * </pre>
 *
 * MigrationRunner executes only the transitions whose fromVersion matches the
 * current persisted version for that table, in order.
 */
public final class TransitionPlan {

    private final String tableName;
    private final List<Transition> transitions;

    private TransitionPlan(String tableName, List<Transition> transitions) {
        this.tableName = tableName;
        this.transitions = List.copyOf(transitions);
    }

    public String tableName() {
        return tableName;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    /** Entry point — returns a builder scoped to the given table name. */
    public static Builder forTable(String tableName) {
        return new Builder(tableName);
    }

    public static final class Builder {

        private final String tableName;
        private final List<Transition> transitions = new ArrayList<>();

        private Builder(String tableName) {
            this.tableName = tableName;
        }

        /**
         * Register a migration step.
         *
         * Matches the Haskell: transition fromVersionList toVersion action
         *
         * @param fromVersions the table versions from which this step may run
         *                     (a list so diverged paths can be merged into one target)
         * @param toVersion    the version the table will be at after the step
         * @param sql          ordered list of SQL statements to execute
         */
        public Builder transition(List<Integer> fromVersions, int toVersion, List<String> sql) {
            transitions.add(new Transition(List.copyOf(fromVersions), toVersion, List.copyOf(sql)));
            return this;
        }

        public TransitionPlan build() {
            return new TransitionPlan(tableName, transitions);
        }
    }
}
