# Views (`res.render`)

Renders a `.bxm` view from a configured views directory and sends the
result as `text/html`:

```js
app.set( "views", expandPath( "./views" ) )

app.get( "/greet/:name", ( req, res ) => {
	res.render( "greeting", { name: req.params.name, age: 30 } )        // -> views/greeting.bxm
} )
```

`.bxm` is BoxLang's native server-page format (think `.cfm`), run via
`include` + `savecontent`. `views/greeting.bxm`:

```html
<bx:output>
<h1>Hello, #data.name#!</h1>
<p>You are #data.age# years old.</p>
</bx:output>
```

`data` is whatever struct you pass as `render()`'s second argument —
reference its keys as `data.whatever`. `#var#` interpolation only happens
inside a `<bx:output>` block, exactly like `<cfoutput>` in classic CFML —
plain text outside one is left as literal `#...#`, unevaluated. Output
isn't escaped for you: wrap anything that came from a user in
`encodeForHTML()`. A view name with no extension gets `.bxm` appended.
Handlebars (`.hbs`) views were removed in 0.2.20; rendering one throws
`BoxExpress.UnsupportedViewEngine`.

`render()` throws if `app.set("views", ...)` was never called. `view` is
resolved and checked against the views directory's real (symlink-resolved)
path before the file is run — a request for
`res.render(req.query.tpl)` with `tpl=../../etc/passwd` throws instead of
rendering whatever that resolves to, but treat any user input reaching
`render()`'s first argument as something to validate yourself regardless;
this just stops the obvious traversal case.

## `app.locals` / `res.locals`

Two plain structs merged into every `render()` call's data, so values a
route doesn't set explicitly — a site name, the current user, a nonce —
don't have to be threaded through every single `res.render()` call by hand.
`app.locals` is set once, application-wide; `res.locals` is set per request
(typically from middleware) and only lives for that one request/response
cycle. Precedence, low to high: `app.locals`, then `res.locals` (overriding
`app.locals`), then whatever you pass as `render()`'s own `data` argument
(overriding both) — same order Express uses:

```js
app.locals.siteName = "My Site"

app.use( ( req, res, next ) => {
	res.locals.user = req.session.user ?: "guest"
	next()
} )

app.get( "/", ( req, res ) => {
	res.render( "home", {} )   // views/home.bxm sees data.siteName and data.user
} )
```
