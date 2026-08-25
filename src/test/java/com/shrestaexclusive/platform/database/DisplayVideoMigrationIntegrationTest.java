package com.shrestaexclusive.platform.database;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.framework.Transition;
import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import com.shrestaexclusive.platform.db.migration.tables.MediaAssetsMigration;

@Testcontainers
class DisplayVideoMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta")
            .withPassword("shresta");

    @Test
    void versionThreeAddsDisplayVideoWithoutWeakeningOtherMediaConstraints() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String schema = "display_video_upgrade_" + UUID.randomUUID().toString().replace("-", "");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema + ", public");

            List<Transition> transitions = MediaAssetsMigration.transitionPlan().transitions();
            TransitionPlan versionTwoPlan = TransitionPlan.forTable("media_assets")
                    .transition(transitions.get(0).fromVersions(), transitions.get(0).toVersion(), transitions.get(0).sql())
                    .transition(transitions.get(1).fromVersions(), transitions.get(1).toVersion(), transitions.get(1).sql())
                    .transition(transitions.get(2).fromVersions(), transitions.get(2).toVersion(), transitions.get(2).sql())
                    .build();
            MigrationRunner.run(connection, List.of(versionTwoPlan));

            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            assertThatThrownBy(() -> insertDisplayVideo(jdbc)).hasMessageContaining("chk_media_asset_media_type");

            MigrationRunner.run(connection, List.of(MediaAssetsMigration.transitionPlan()));

            assertThat(insertDisplayVideo(jdbc)).isOne();
            assertThat(jdbc.sql("""
                    SELECT version FROM shresta_table_migration_versions
                    WHERE table_name = 'media_assets'
                    """).query(Integer.class).single()).isEqualTo(3);
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private int insertDisplayVideo(JdbcClient jdbc) {
        UUID id = UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO media_assets (
                    id, asset_key, asset_url, alt_text, width_px, height_px,
                    usage_type, content_type, byte_size, status, media_type
                ) VALUES (:id, :assetKey, :assetUrl, 'Brand film', 1, 1,
                          'display', 'video/mp4', 100, 'READY', 'DISPLAY_VIDEO')
                """)
                .param("id", id)
                .param("assetKey", "media-" + id.toString().replace("-", ""))
                .param("assetUrl", "display/videos/" + id + ".mp4")
                .update();
    }
}
