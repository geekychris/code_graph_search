# Project stats

Code Graph counts every repo it indexes: files and lines (code, comment, blank) by
**language** and by **kind**. You can see one repo's numbers, or roll them up across the
portfolio by repo, family, language, kind, visibility or tag, broken down by a second
dimension and filtered.

- **UI:** a repo's **Stats** tab, and the top-level **Stats** page (group, break down, measure, filter; click a group to drill in).
- **REST:** `GET /api/repos/{id}/stats`, `GET /api/stats`.
- **MCP:** `project_stats`.

## When repos are counted

Stats are stored per repo as the repo fact `stats`, at the commit that was indexed.
- **Portfolio repos** (synced from GitHub) are counted at the end of each index job.
- **On sync:** a repo whose stats are missing, from another commit, or from older counting rules (`StatsScanner.REVISION`) is queued. Its job doesn't re-parse anything when the commit hasn't changed; it only counts. So the first start with stats counts every repo once.
- **Local repos** (configured by path) are counted after each index, at the checkout's `HEAD`.

Counting reads files only. On this repo it takes about a second.

## What's counted

- **Which files:** every file git tracks at the indexed commit (`git ls-files`), so ignored and untracked build output never counts. A directory that isn't a git checkout is walked instead.
- **Skipped as vendored or build output:** files under `node_modules`, `bower_components`, `vendor`, `third_party`, `thirdparty`, `.venv`, `venv`, `site-packages`, `__pycache__`, `.tox`, `dist`, `target`, `.idea`, `.gradle`, `Pods`, `.yarn`, `.pnpm-store`, `Carthage`, `.next`, `.nuxt` and `.terraform`. They're reported as `excluded`, a count only.
- **Size only:** files with a binary extension, files containing a NUL byte, and files over 4 MB are counted by file and size, with no lines.

### Languages

Languages come from the extension or file name (`app/.../stats/FileClassifier.java`).
About 90 are known, including:
- the usual: Java, Go, TypeScript, Python, C, C++, Rust;
- markup and config: HTML, CSS, XML, JSON, YAML;
- build files: Dockerfile, Makefile, CMake, Gradle;
- the retro ones in this portfolio: 68k Assembly (`.s`, `.asm`, `.i`, `.68k`), AmigaE (`.e`), BASIC, Pascal.

Unknown text is **Other**, and binary files are **Binary**.

### Kinds

Decided in this order: the first rule that matches wins.

| Kind | Rule |
|---|---|
| `generated` | lockfiles (`package-lock.json`, `go.sum`, `Cargo.lock`, …), `*.min.*`, protobuf output (`*.pb.go`, `*_pb2.py`), `*.generated.*`, `generated/` directories |
| `asset` | binary files (images, audio, fonts, archives, disk images, …), and SVG |
| `ci` | `.github/workflows/`, `.circleci/`, `.gitlab-ci.yml`, `Jenkinsfile`, `.travis.yml`, … |
| `test` | under `test/`, `tests/`, `__tests__/`, `spec/`, `src/test/`, `testdata/`, `e2e/`, `fixtures/`, …, or named like a test (`*Test.java`, `*_test.go`, `test_*.py`, `*.spec.ts`, `*_spec.rb`, …) |
| `docs` | Markdown, AsciiDoc, reStructuredText, plain text and TeX; README, LICENSE, CHANGELOG and the like; prose under `docs/` (code there stays source) |
| `build` | `pom.xml`, `build.gradle*`, `package.json`, `go.mod`, `Cargo.toml`, `Makefile`, `CMakeLists.txt`, `Dockerfile*`, `docker-compose*.yml`, `pyproject.toml`, `requirements*.txt`, `tsconfig.json`, bundler configs, … |
| `config` | JSON, YAML, TOML, INI, properties, XML, HCL, Nix, ignore lists (Kubernetes manifests land here) |
| `data` | CSV/TSV, patches |
| `source` | everything else in a known language |
| `other` | unknown text |

### How lines are counted

Each line is **blank** (whitespace only), **comment** or **code**:
- **Comments:** a line is a comment when, trimmed, it starts with the language's comment marker (`//`, `#`, `--`, `;`, `%`, `'`/`REM`, …), or lies inside a block comment (`/* */`, `<!-- -->`, `{- -}`, `(* *)`, …) that started at the beginning of a line.
- **Code:** a line with code and a trailing comment counts as code.
- **Assembly:** a `*` in the first column is a comment too.
- **Prose and data** (Markdown, JSON, CSV) have no comments: every non-blank line counts as code.

This is the same approach as cloc and scc, without a parser. On this repository it agrees
with cloc to within 2 lines in 19,000 for Java, and exactly for TypeScript, Markdown and
YAML. Strings that contain comment markers can be miscounted in rare cases.

## Rolling up

Every number is summed from the same matrix, repo × language × kind, so the views always
agree. A rollup takes:

| Parameter | Values | Default |
|---|---|---|
| `group` | `repo`, `family`, `language`, `kind`, `visibility`, `tag`, `none` | `family` |
| `breakdown` | the same, inside each group | `none` |
| `metric` | `code`, `lines`, `files`, `comment`, `blank`, `bytes`: orders groups and breakdown parts | `code` |
| `repo`, `family`, `language`, `kind`, `visibility`, `tag` | comma-separated filters, ANDed | none |
| `limit` | breakdown parts per group; the rest are summed as `(other)` | 12 |

- **Family and tags** come from each repo's card (repo-atlas or session-written).
- **Tags:** a repo with several tags appears in each tag's group, but `totals` counts it once.
- **Filters on language and kind** filter the matrix cells, so "kind=test, group=family" means test code per family.
- **Visibility:** rollups only include repos the caller can see, so a public token's totals never include private repos.

```sh
# test vs source code per family
curl -s -H "Authorization: Bearer $T" 'http://localhost:8080/api/stats?group=family&breakdown=kind&kind=source,test'
# Java code per repo
curl -s -H "Authorization: Bearer $T" 'http://localhost:8080/api/stats?group=repo&language=Java'
# languages across everything, by files
curl -s -H "Authorization: Bearer $T" 'http://localhost:8080/api/stats?group=language&metric=files'
```

Response:

```json
{"query": {...}, "totals": {"files": 0, "lines": 0, "code": 0, "comment": 0, "blank": 0, "bytes": 0}, "repos": 42,
 "groups": [{"key": "games", "counts": {...}, "repos": 9, "breakdown": [{"key": "C", "counts": {...}}, ...]}]}
```

`GET /api/repos/{id}/stats` returns:
- `totals`, `byLanguage`, `byKind`, and `cells` (language → kind → counts);
- `byDir`: the 30 largest top-level directories;
- `largest`: the 15 largest source and test files;
- `excluded`, and the `commit` it was counted at.

## Changing the rules

Edit `FileClassifier` (languages, kinds), `LineCounter` (comment syntax) or `StatsScanner`
(excluded directories, size limit), then bump `StatsScanner.REVISION`. On the next sync,
every repo is counted again.
