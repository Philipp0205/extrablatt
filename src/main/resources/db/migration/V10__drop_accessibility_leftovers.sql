-- The accessibility edition lived in this app until it moved to Klarblatt.
-- Saved-article bookmarks and display_preferences were only used by that edition.
DROP TABLE IF EXISTS display_preferences;

DROP INDEX IF EXISTS idx_articles_saved_at;
ALTER TABLE articles DROP COLUMN IF EXISTS saved_at;
