# Demo recording notes (human, Nov 21, 2026)

What was actually recorded, where it differs from demo-script-source.md (which was written before Part B):

- Part A (A0 to A11) was recorded on Nov 19 during cloud window 3. The voiceovers for A5 to A11 were recorded in iMovie on Nov 20, because the microphone was off from A5 on.
- Part B: B1 to B10 and B12 were recorded on Nov 21. B11 is recorded during the fresh-clone verification (Phase 17 Step F), after the README exists.
- B5 says "work orders and rulings" without a number (Phase 15 has 19 rulings) and may close with the code review 5, repair-03, code review 6 loop.
- B6 used `./scripts/check.sh 2>&1 | grep -E '==> (OK|FAIL)|Tests: succeeded'`, so each gate shows as it passes (not `| tail -40`).
- B9 showed the run IDs in the pipeline record rather than the S3 console, because the bucket name contains the account ID.
- B12 printed 33 passed, then 12 passed and 2 pending; the 2 pending are verifier limits documented in docs/evaluation.md.
- Assembly: the final order is the table in the source. Export at 1080p, quality Medium (compress "Better Quality"), not High. Upload with a restricted link; the human adds the link to the README before the Phase 17 gate.
