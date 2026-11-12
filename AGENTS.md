# AGENTS.md

## tests
- full suite: `./mvnw test`. a pass only counts if the full run passed; quote the `Tests run:` summary line.
- name integration tests `*IntegrationTest`, never `*IT`. surefire only picks up *Test, so *IT classes are silently skipped (ApiControllerIT in PR #122 dropped the count from 621 to 598).
- after adding a test class, check that the total test count went up.
