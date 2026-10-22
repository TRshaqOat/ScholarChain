# Prompt

Use the factory-engineer agent for phase-01 repair-04. Fix only this finding:

The terraform MCP server fails at startup. awslabs.terraform-mcp-server@1.0.18 does not cap its mcp dependency, so uvx resolves mcp 2.x, which removed mcp.server.fastmcp (ModuleNotFoundError). In .mcp.json, change the terraform server's uvx args to pin the mcp library to an exact 1.x version: ["--with", "mcp==<VERSION>", "awslabs.terraform-mcp-server@1.0.18"]. Record the mcp pin and the reason in the pinned-versions table in docs/agents.md. Do not change any other server.

Rerun the validator and show the raw output. Present the diff for this change only. Stop for human ratification.

# Response

Repair-04 is ready for your ratification. The factory-engineer changed one line in each of the two files, and the validator passes 231/231 checks. Nothing takes effect until you ratify, commit, and start a new session.

## Diff (repair-04 only)

```diff
--- .mcp.json.pre-repair-04
+++ .mcp.json
@@ -25,7 +25,7 @@
     "terraform": {
       "type": "stdio",
       "command": "uvx",
-      "args": ["awslabs.terraform-mcp-server@1.0.18"],
+      "args": ["--with", "mcp==1.30.0", "awslabs.terraform-mcp-server@1.0.18"],
       "env": {
         "FASTMCP_LOG_LEVEL": "ERROR"
       }
--- docs/agents.md.pre-repair-04
+++ docs/agents.md
@@ -179,7 +179,7 @@
 |---|---|---|
 | `aws-api` | `awslabs.aws-api-mcp-server` | `1.5.6` |
 | `aws-docs` | `awslabs.aws-documentation-mcp-server` | `1.2.2` |
-| `terraform` | `awslabs.terraform-mcp-server` | `1.0.18` |
+| `terraform` | `awslabs.terraform-mcp-server` | `1.0.18`, launched with `--with mcp==1.30.0`. The upstream package requires `mcp[cli]>=1.23.0` with no upper cap, and `mcp` 2.x removed `mcp.server.fastmcp`, which the server imports at startup. |
 | `github` | remote HTTP endpoint `https://api.githubcopilot.com/mcp/` | not a package; the remote service version is controlled by GitHub |
```

I checked the files myself. [.mcp.json:28](.mcp.json#L28) and [docs/agents.md:182](docs/agents.md#L182) contain exactly these lines. The SHA-256 checksums of both files (`212c6134…ccd3e` and `668091e3…eacffb`) match the agent's "after" values. The other servers' entries are unchanged.

## Validator (I reran it; this is the raw output)

`.claude/hooks/validate-agent-files.sh` printed 231 PASS lines and no failures or warnings. These are the lines that bear on this change and the summary:

```
PASS  .mcp.json parses as JSON
PASS  .mcp.json contains no secret-looking literal
PASS  .mcp.json credential fields reference environment variables only
PASS  .claude/hooks/guard-dangerous.sh --self-test passes (guard-dangerous self-test: 304/304 passed)
PASS  .claude/hooks/guard-paths.sh --self-test passes (guard-paths self-test: 127/127 passed)
PASS  .claude/hooks/verify-tests-ran.sh --self-test passes (verify-tests-ran self-test: 11/11 passed)
validate-agent-files: 231/231 checks passed
exit=0
```

## How the version was chosen

1.30.0 is the newest 1.x release listed in the local uv cache's PyPI index, which was refreshed today. That index also lists 2.0.0a1 through 2.3.0. The agent tested the change using environments that were already in the uv cache:

- **mcp 1.30.0:** importing `mcp.server.fastmcp` and the server module worked. Launching the server with stdin closed exited 0.
- **mcp 2.3.0:** the import failed with `ModuleNotFoundError: No module named 'mcp.server.fastmcp'`, which is the original failure.

## Where the agent didn't follow the work order

It says its own agent file forbids network fetches, installing packages, and writing files outside its owned paths. So it skipped three steps I had asked for:

- **No PyPI query:** it skipped `curl` to PyPI and used the uv cache's index instead.
- **No fresh resolution:** it skipped `uvx --with ...` and tested with the existing cached environments.
- **No snapshot copy:** it skipped copying the files to a temp folder. Instead it rebuilt the "before" version of each file and checked it against checksums it recorded before editing.

That was a reasonable choice. The catch is that `uvx --with mcp==1.30.0` hasn't been run from scratch, so other unpinned dependencies could resolve differently on a clean install.

## Before you ratify

1. **One line is now out of date.** [docs/agents.md:208](docs/agents.md#L208), in the MCP servers and trust boundaries table, still says ``stdio, `uvx awslabs.terraform-mcp-server@1.0.18` `` without the `--with mcp==1.30.0` part. I kept it out of scope as you asked. If you want it fixed, I can send a follow-up repair to the factory-engineer.
2. **Other risks the agent flagged:**
   - The server's other dependencies only have minimum versions, so a future major release could break startup in the same way.
   - `aws-api` and `aws-docs` weren't checked for the same mcp 2.x problem. That's out of scope.
3. **Checks to run after you restart:**
   - `uvx --with mcp==1.30.0 --from awslabs.terraform-mcp-server@1.0.18 python -c "import mcp.server.fastmcp, importlib.metadata as m; print(m.version('mcp'))"` should print `1.30.0`.
   - `/mcp` should show `terraform` as connected.

To ratify: record your decision in `docs/agent-runs/phase-01/human-gate.md`, commit, and start a new session. I haven't written anything to `docs/agent-runs/`. I can add the agent's report to `docs/agent-runs/phase-01/repair-04.md` if you want it there.
