# AGENTS.md

## tests
- full suite: `./mvnw test`. a pass only counts if the full run passed; quote the `Tests run:` summary line.
- name integration tests `*IntegrationTest`, never `*IT`. surefire only picks up *Test, so *IT classes are silently skipped (ApiControllerIT in PR #122 dropped the count from 621 to 598).
- after adding a test class, check that the total test count went up.
- Kindle is the first-class client. `KindleViewportTest` (e-ink layout at eight Kindle screen sizes), `KindleSendEndToEndIntegrationTest` and `KindleSignInIntegrationTest` run inside `./mvnw test`, so they are part of `./extrablatt-check.sh` and CI. A PR that makes any of them fail is not mergeable: fix the change, do not loosen or skip the test. Changing a Kindle viewport number needs a reason in the PR body.

## tools
- search code and logs with the grep tool, find files with the glob tool, view files with the read tool (use offset/limit for long logs and surefire reports). do not use `cat`, `sed -n`, `head`, `tail`, `grep`, `rg` or `ls -R` in bash to read or search files. trimming a command's own output (`./extrablatt-check.sh 2>&1 | tail -60`) is fine.
- bash is for running commands (`./extrablatt-check.sh`, `./mvnw`, git). do not probe the permission rules with dummy commands.
- create new files (including flyway migrations) with the write tool. never `cat > file`, heredocs or `echo >` into repo files, also not behind `cd ... &&`.
