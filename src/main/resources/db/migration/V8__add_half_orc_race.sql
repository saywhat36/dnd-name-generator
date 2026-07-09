-- HALF_ORC was added to the Race enum (name/Race.java) but never to the race CHECK
-- constraints, so every existing table that stores a race has silently rejected it since --
-- V1's "a migration adding a race must update both CHECK constraints" note was never honored
-- when the enum grew. The browse UI already renders it (index.html iterates Race.values()),
-- and V7's name_submissions now offers it too, so a half-orc row would fail on insert with a
-- DataIntegrityViolationException. Bring names and generation_log in line (V7 already ships the
-- full nine-race list). Inline column CHECKs from V1 are auto-named <table>_<column>_check.
ALTER TABLE names DROP CONSTRAINT names_race_check;
ALTER TABLE names ADD CONSTRAINT names_race_check CHECK (race IN
    ('ELF', 'DWARF', 'HUMAN', 'HALFLING', 'ORC', 'HALF_ORC', 'GNOME', 'DRAGONBORN', 'TIEFLING'));

ALTER TABLE generation_log DROP CONSTRAINT generation_log_race_check;
ALTER TABLE generation_log ADD CONSTRAINT generation_log_race_check CHECK (race IN
    ('ELF', 'DWARF', 'HUMAN', 'HALFLING', 'ORC', 'HALF_ORC', 'GNOME', 'DRAGONBORN', 'TIEFLING'));
