# Auto-restart on file change (`app.set("reloadOnChange", true)`)

```js
app.set( "reloadOnChange", true )
```

Dev-only convenience: restarts your app whenever a `.bx` or `.bxs` file
changes. It watches the entry script's directory recursively, skipping
`boxlang_modules/`, `node_modules/`, `target/`, `build/`, `dist/` and
dot-directories. The watch list is a snapshot taken at startup, so a
directory created later isn't watched until the next restart.

`.bxm` view files aren't watched. `res.render()` re-reads a template from
disk on every request, so a view edit shows up on the next request without
a restart. Only `.bx`/`.bxs` source, which BoxLang compiles once and caches,
needs a fresh process.

Separate from `app.set("env", "development")`; use either on its own.

## How it works

BoxLang can't reload compiled classes inside a running JVM, so a restart
means a new process. With `reloadOnChange` on, the process you start
becomes a **supervisor**, and your app runs in a **child process**:

1. Your script runs up to `app.listen()`. In the supervisor, `listen()`
   doesn't bind the port. It starts the child with the same command line
   (whatever the script is named, bvm-managed `boxlang`, a raw `java -jar`,
   custom JVM flags) and starts watching files.
2. The child runs your script again, from the top, and serves requests
   as normal.
3. On a change, the supervisor logs
   `[reloadOnChange] <path> changed — restarting...`, stops the child with
   `SIGTERM`, waits for it to exit (force-killing it after 10 seconds),
   and starts a new one. The old server has always released the port
   before the new one binds it.
4. If the child exits on its own (a syntax error in what you just saved,
   a startup exception, `app.close()`), the supervisor logs
   `[reloadOnChange] app exited with code N — waiting for a file change to
   restart.` and starts it again on your next save. Fix the file and it
   comes back.

The supervisor is the process your shell, IDE or container started, and
it stays alive for the whole session:

- **Ctrl+C** stops both; the terminal sends it to the supervisor and the
  child together.
- **`SIGTERM` to the supervisor** (`kill`, `docker stop`, an IDE's stop
  button) stops the child first, then exits.
- **If the supervisor is killed outright** (`kill -9`), the child notices
  within a second and exits, so no server is left running in the
  background.
- **In a container**, the supervisor can be PID 1: the container keeps
  running across restarts.

What runs where:

- Code **after** `app.listen()` never runs in the supervisor, because
  `listen()` blocks there.
- Code **before** `app.listen()` runs in both processes. Registering
  routes and middleware there is harmless. `app.schedule()` jobs and the
  cluster manager are shut down in the supervisor so they only run in the
  child. Anything else with side effects at the top of your script (a
  migration, a log line) runs once more, in the supervisor, at startup;
  move it after the routes or guard it if that matters.
- The `listen()` callback runs only in the child.
- The child stops immediately on a restart instead of waiting for
  in-flight requests, so an open SSE stream doesn't hold up every save.
  Set `app.set( "shutdownTimeoutMs", ms )` to drain instead.

Dev mode runs two JVMs, so expect roughly double the memory of a plain
`boxlang app.bxs`.

## Using an external watcher instead

Any restart tool that runs your app as its own child process works with
BoxExpress unchanged. Leave `reloadOnChange` off and let the tool do the
watching:

```bash
watchexec -r -e bx,bxs -- boxlang app.bxs
```

```bash
nodemon -e bx,bxs --exec boxlang app.bxs
```

```bash
find . -name '*.bx' -o -name '*.bxs' | entr -r boxlang app.bxs
```

Reasons to prefer one:

- **Finer control** over what's watched (globs, ignore files, several
  directories) and when it restarts (debounce, clear screen, notifications).
- **One JVM**, and your script runs once per restart instead of twice.
- **Your team already uses one** for other languages.

`SIGTERM` from these tools runs BoxExpress's normal graceful shutdown (see
[health-and-shutdown.md](health-and-shutdown.md)), so in-flight requests
finish first.
