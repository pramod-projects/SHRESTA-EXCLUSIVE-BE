package com.shrestaexclusive.platform.db.migration.framework;

import java.util.List;

/**
 * A single versioned migration step for one table.
 *
 * Matches the Haskell: transition fromVersionList toVersion action
 *
 * fromVersions is a list so a single transition can apply from multiple possible
 * current versions — useful when consolidating diverged migration paths.
 * Example: transition(List.of(-1, 2), 3, ...) means "run this if currently at -1 OR 2".
 *
 * @param fromVersions the set of table versions from which this step may run
 * @param toVersion    the table version after all sql statements complete
 * @param sql          ordered list of SQL statements to execute
 */
public record Transition(List<Integer> fromVersions, int toVersion, List<String> sql) {}
