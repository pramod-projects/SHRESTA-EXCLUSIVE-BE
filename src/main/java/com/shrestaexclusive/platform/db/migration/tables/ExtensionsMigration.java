package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

/** PostgreSQL extensions required by the schema. Always first in the plan list. */
public final class ExtensionsMigration {

    private ExtensionsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("__extensions__")
            .transition(List.of(-1), 0, List.of(
                "CREATE EXTENSION IF NOT EXISTS \"pgcrypto\"",
                "CREATE EXTENSION IF NOT EXISTS \"pg_trgm\""
            ))
            .build();
    }
}
