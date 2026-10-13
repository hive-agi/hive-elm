# hive-elm

Elm build watching as a hive IAddon. It runs `elm make --report=json`, turns
the compiler's report into values, and shouts each new failure on the
hivemind, so a model working on an Elm project hears that the build broke
without having to ask.

```
-- MODULE NOT FOUND -- src/Main.elm:7:8

You are trying to import a `Theme.Tokens` module:

7| import Theme.Tokens as Tokens
          ^^^^^^^^^^^^
I checked the "dependencies" and "source-directories" listed in your elm.json,
but I cannot find it! ...
```

## Commands

The addon contributes the `elm` subdomain to the host's `code` tool.

| Command | What it does |
|---|---|
| `code elm check directory=<root>` | Compile once and return the problems. Tells nobody. |
| `code elm watch start directory=<root> parent=<agent-id>` | Watch the sources. Shout each new failure and each recovery. |
| `code elm watch status directory=<root>` | Build state and the last 50 checks. |
| `code elm watch stop directory=<root>` | Stop the watcher. |
| `code elm doctor directory=<root>` | Which elm would run, whether it runs, and where a failure would go. |

A failure is shouted once. Saving again without fixing it stays quiet; a
different failure, or the same one after a clean build, is news again. A
clean build is shouted only right after a failure.

## How a failure reaches the model

The watcher shouts event type `:build-failed` (and `:build-recovered`) as
agent `elm:<directory name>`. From there the host carries it two ways:

- **Piggyback.** The shout is appended to the next tool result of the reader
  it is addressed to, in the `---HIVEMIND---` block.
- **sixth-sense (`ss`).** On load the addon registers the sense class
  `:sense/build-failed`, with the detail fields `build-tool`, `build-title`,
  `build-file`, `build-line` and `build-problems`. sixth-sense then pushes the
  failure to an idle session instead of waiting for its next tool call.

Both address by parent. Pass `parent=<your agent id>` to `watch start` (or
set it in `hive-elm.edn`) to hear the failures of the project you are working
on. Without a parent, only coordinator readers do.

Neither hive-mcp nor hive-agent is a dependency: both are resolved when
needed. Outside a host, `check` works and the watcher records failures in
its log without delivering them.

## Configuration

None is required. The entry module defaults to `src/Main.elm`, or
`.elm-land/src/Main.elm` for an elm-land project; source directories come
from `elm.json`; the compiler is `node_modules/.bin/elm` when the project
pins one, else `elm` on PATH.

To override, put `hive-elm.edn` at the project root:

```clojure
{:hive.elm/entries ["src/Main.elm" "src/Admin.elm"]
 :hive.elm/command ["npx" "--no-install" "elm"]
 :hive.elm/watch   {:poll-ms 500 :debounce-ms 300}
 :hive.elm/notify  {:parent-id "coordinator:1234"}}
```

The watcher type-checks with `--output=/dev/null`: it writes no artifact, so
it does not race the project's own bundler (elm-land, vite) over an output
file. It does share `elm-stuff/` with it, as two `elm make` runs always do.

## The compiler's report

Read off the compiler source (elm 0.19.3) and measured against 0.19.1:

- A failed build writes one line of JSON to stderr and exits 1. A clean
  build writes nothing and exits 0.
- `{"type": "compile-errors", "errors": [{path, name, problems: [{title, region, message}]}]}`
  for problems in modules. Paths are absolute; lines and columns are 1-based.
- `{"type": "error", path, title, message}` for everything else: a broken
  `elm.json`, a missing file, an import cycle, a dependency problem. `path`
  is `"elm.json"` or null.
- A `message` is a list of strings and styled spans
  `{bold, underline, color, string}`; hive-elm keeps the text and drops the
  styling.
- Flag errors and compiler crashes are plain text, not JSON. hive-elm keeps
  that text verbatim as a single `BUILD FAILED` problem.
- A module that imports a failing module reports nothing, and a syntax error
  is the only problem its module reports, so fixing one failure can reveal
  the next.

## Development

```
clojure -M:test          # unit tests: stub ports, no elm needed
clojure -M:integration   # runs a real elm over a scratch project
```

## Layout

| Namespace | Stratum |
|---|---|
| `hive-elm.schema` | Value objects (malli) |
| `hive-elm.ports` | `IElmCompiler`, `IBuildNotifier`, `IFileStamps`, `IClock` |
| `hive-elm.report`, `hive-elm.watch`, `hive-elm.manifest` | Pure: parse, decide, resolve |
| `hive-elm.watch.supervisor` | Pipeline over the ports |
| `hive-elm.compiler.process`, `hive-elm.stamps.fs`, `hive-elm.notify.*`, `hive-elm.boundary` | Adapters and boundary |
| `hive-elm.addon*` | IAddon and the `code elm` commands |
