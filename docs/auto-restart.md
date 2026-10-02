# Auto-restart on file change (`app.set("reloadOnChange", true)`)

```js
app.set( "reloadOnChange", true )
```

Dev-only convenience — watches the current working directory (recursively,
skipping `boxlang_modules/`, `node_modules/`, `.git`, and other dot-dirs) for
`.bx`/`.bxs` changes and restarts the process on save. Registration is a
one-time recursive snapshot taken at startup — a directory created *after*
the server starts won't be picked up until the next restart.

`.bxm` view files aren't watched — `res.render()` re-reads a `.bxm` template
from disk on every request, so an edit already shows up on the next request
with no restart needed; only actual `.bx`/`.bxs` source, which BoxLang
compiles once and caches, needs a fresh process to pick up a change.

There's no hot reload inside a running JVM once BoxLang has compiled your
classes, so a restart replaces the whole process: launch a replacement by
replaying the exact original JVM invocation (via `ProcessHandle.current()`,
so it works whatever the entry script is named or however it was launched),
then close the current server and exit — same net effect as running under
`entr -r` or `nodemon`, just without the extra dependency. Launching the
replacement is attempted *before* closing the current server, not after —
if it fails for any reason (a bad reconstructed command, a transient OS
resource limit), that's caught, logged, and the current server is left
running rather than dying with nothing to replace it.

Separate from `app.set("env", "development")` — use either independently.
