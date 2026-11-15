# AGENTS.md

## tests
- full suite: `./mvnw test`. a pass only counts if the full run passed; quote the `Tests run:` summary line.
- name integration tests `*IntegrationTest`, never `*IT`. surefire only picks up *Test, so *IT classes are silently skipped (ApiControllerIT in PR #122 dropped the count from 621 to 598).
- after adding a test class, check that the total test count went up.

## tools
- search code and logs with the grep tool, find files with the glob tool, view files with the read tool (use offset/limit for long logs and surefire reports). do not use `cat`, `sed -n`, `head`, `tail`, `grep`, `rg` or `ls -R` in bash to read or search files. trimming a command's own output (`./extrablatt-check.sh 2>&1 | tail -60`) is fine.
- bash is for running commands (`./extrablatt-check.sh`, `./mvnw`, git). do not probe the permission rules with dummy commands.
- create new files (including flyway migrations) with the write tool. never `cat > file`, heredocs or `echo >` into repo files, also not behind `cd ... &&`.

## changelog
- a PR that changes what a reader sees adds one entry at the top of `releases` in `src/main/resources/changelog.json` (newest first, do not rewrite older releases). it feeds Settings, Changelog and the what's-new popup. skip chores, CI and refactors.
- plain, honest copy: say what changed for someone reading on a Kindle or phone. no invented numbers, no marketing words. fields: `id` (date, plus a slug if the day has two), `date` ("15 November 2026"), `title`, `highlights` (2-3 lines), `changes` (the detail).
