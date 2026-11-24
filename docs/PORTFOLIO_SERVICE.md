# Running Code Graph Search as a portfolio service

Code Graph Search can index every repository that one or more GitHub accounts own,
keep the index current as you push, and serve it to people, agents and other services
with access control. It covers the first two phases of the portfolio code graph design: the
service itself and cross-repo dependencies. Contracts and concepts come later.

## What it does

- **Lists repositories** for each owner in `portfolio.owners` through the GitHub API.
  With a token for that account, private repos are included. Forks are skipped unless
  `includeForks` is set.
- **Records each repo** in the registry with its visibility, clone URL, default branch
  and last push time.
- **Clones each repo** under `portfolio.mirrorDir` as a partial clone (all commits and
  trees; file contents fetched on checkout).
- **Indexes by commit.** The first index is full. After a push, only the files changed
  between the indexed commit and the new one are re-parsed, and deleted files are
  removed. If the old commit is gone (force push), it falls back to a full index.
- **Stays current** with a sync every `syncIntervalMinutes` and on GitHub `push`
  webhooks. `repository` events (created, renamed, made public or private) trigger a
  full sync so visibility changes apply straight away.

## Who sees what

Every request runs as a caller:

| Caller | Sees | Can change the index |
|---|---|---|
| No token, `auth.anonymous: public` | public repos | no |
| Token with `scope: public` | public repos | no |
| Token with `scope: all` | public and private repos | no |
| Token with `admin: true` | per its scope | yes |
| MCP over stdio, or auth disabled | everything | yes |

The rule is enforced in one place, `ScopedGraphService`, which every REST route and
MCP tool reads through:

- Search filters by repo inside Lucene, so counts and limits are correct.
- An element from a repo the caller can't see looks like it does not exist.
- Edges into hidden repos are dropped, path searches never pass through a hidden node,
  and similarity is computed over visible neighbours only.
- A repo whose visibility is unknown is treated as private.

Admin-only: `POST`/`DELETE` under `/api/repos`, `/api/browse` (lists the server's
directories), `POST /api/portfolio/*`, and the MCP tools `add_repo`, `remove_repo`,
`reindex_repo`, `start_watching` and `stop_watching`.

The server refuses to start with `portfolio.enabled` and `auth.enabled: false`, unless
`auth.allowInsecure: true` is set explicitly.

## Configuration

See the `auth` and `portfolio` sections of `config-example.yaml`, and
`deploy/config.portfolio.yaml` for the container defaults.

Tokens are compared by SHA-256, so a config file can hold the digest
(`echo -n "$TOKEN" | shasum -a 256`) rather than the token, or name an environment
variable with `tokenEnv`.

The GitHub token needs read access to repository contents and metadata. It is passed
to git through environment-based config, never on the command line or written to
`.git/config`.

## Dependencies across repos

After every index of a repo, its build manifests are read (nothing is executed) and
the portfolio dependency graph is rebuilt:

- **Maven** (parents, properties, dependency management, in-repo BOMs), **Gradle**
  (Groovy and Kotlin DSL, version catalogs, `project(...)`), **Go** (`require`,
  `replace`, including `=> ../sibling` checkouts), **npm** (workspaces, `file:`),
  **Cargo** (workspace inheritance) and **Python** (`pyproject.toml`, requirements).
- A dependency on an artifact another repo produces, or on a sibling checkout, is an
  edge between the two repos. Everything else counts as external.
- Code is linked across those edges: Java calls, `extends`, `implements`, `new` and
  imports that point at a type in a library repo are connected to the real element,
  so `get_callers` on a library method also lists callers in other repos. Only repos
  in the dependency closure are searched.

MCP tools: `repo_dependencies`, `repo_dependents`, `dependency_layers` (release
order and cycles), `drift_report`, `find_artifact_users`, `portfolio_dependency_stats`.
All of them leave out repos the caller can't see.

## Endpoints

| Route | What |
|---|---|
| `GET /api/whoami` | The caller: name, whether it sees private repos, admin |
| `GET /api/portfolio/status` | Owners, last sync report, queued and running index jobs |
| `POST /api/portfolio/sync` | Sync now (admin) |
| `POST /api/portfolio/repos/{id}/refresh` | Fetch and re-index one repo (admin) |
| `POST /api/hooks/github` | GitHub webhook; verified with `X-Hub-Signature-256` |
| `GET /api/repos` | Repos the caller can see, with visibility, source and indexed commit |
| `GET /api/deps/repos/{id}` | A repo's modules, produced artifacts, dependencies, dependents and externals |
| `GET /api/deps/edges` | All repo-to-repo dependency edges |
| `GET /api/deps/layers?prefix=` | Layer (release) order and cycles |
| `GET /api/deps/drift` | Consumers on a different version than the producer |
| `GET /api/deps/artifacts?key=` | Producers and users of one artifact |

## Deploying

```bash
docker build -t code-graph-search .
kubectl apply -f deploy/k8s/codegraph.yaml   # create codegraph-secrets first; see the file
```

Run a single replica: RocksDB and Lucene lock the data volume.

On spark (microk8s, arm64), `scripts/deploy-spark.sh` builds the image on the host,
imports it into microk8s, and applies the manifest with the image pinned to the
commit and the Service on NodePort 30930.

To get push webhooks, add a webhook to each repo (or the account) pointing at
`https://<host>/api/hooks/github`, content type `application/json`, with the secret
from `CODEGRAPH_WEBHOOK_SECRET`.

## Local repos still work

Repos listed under `repos:` and repos added at runtime through the API or MCP are
indexed from local paths as before. Runtime-added repos are now kept in the registry,
so they are indexed and watched again after a restart. A local repo that is a git
checkout is caught up at startup with the commits and uncommitted edits made while the
server was down.
