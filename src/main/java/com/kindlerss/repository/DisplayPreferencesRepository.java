package com.kindlerss.repository;

import com.kindlerss.domain.DisplayPreferences;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Per-account display settings for the accessible edition. */
@Repository
public class DisplayPreferencesRepository {

    private final JdbcTemplate jdbc;

    public DisplayPreferencesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DisplayPreferences> find(long userId) {
        return jdbc.query("SELECT settings FROM display_preferences WHERE user_id = ?",
                        (rs, row) -> DisplayPreferences.decode(rs.getString("settings")), userId)
                .stream().findFirst();
    }

    public void save(long userId, DisplayPreferences preferences) {
        jdbc.update("""
                INSERT INTO display_preferences (user_id, settings) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET settings = EXCLUDED.settings, updated_at = NOW()
                """, userId, preferences.encode());
    }
}
