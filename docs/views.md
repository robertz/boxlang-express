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

## Layouts

A layout is an ordinary view that wraps another one — the page shell
(`<html>`, navigation, footer) written once instead of at the top and
bottom of every view. Name one for the whole app:

```js
app.set( "views", expandPath( "./views" ) )
app.set( "view layout", "layouts/main" )      // views/layouts/main.bxm
```

`res.render()` then renders the view first, and renders the layout with
the view's HTML available as `data.body`:

```html
<!-- views/layouts/main.bxm -->
<bx:output>
<!doctype html>
<html>
<head><title>#encodeForHTML( data.title ?: "My Site" )#</title></head>
<body>
  #partial( "partials/nav" )#
  <main>#data.body#</main>
</body>
</html>
</bx:output>
```

`data.body` is already-rendered HTML, so output it as is. The layout sees
the same `data` as the view (`app.locals`, `res.locals` and what was
passed to `render()`).

Because the view runs before the layout, a view can hand values up to it
by setting them on `data` — a page title, a body class, a flag for extra
scripts:

```html
<!-- views/report.bxm -->
<bx:set data.title = "Quarterly report">
<bx:output><h1>#encodeForHTML( data.report.name )#</h1> ...</bx:output>
```

One render can choose differently with a `layout` key in its data (or in
`res.locals`):

```js
res.render( "login", { layout: "layouts/bare" } )   // a different layout
res.render( "fragment", { layout: false } )         // none — just the view
```

With no `"view layout"` set, nothing changes: a view is rendered alone
unless a render names a layout itself. **`layout` is now a reserved key in
render data** — a view that was being passed its own `layout` value for
some other purpose needs that key renamed.

## Partials

`partial( view, data )` renders another view and returns its HTML, for
the pieces shared between pages. Call it inside a view or layout:

```html
<bx:output>
#partial( "partials/nav" )#
<bx:loop array="#data.orders#" index="order">
	#partial( "partials/order-row", { order: order } )#
</bx:loop>
</bx:output>
```

A partial sees everything the calling view does, plus what you pass it;
what you pass is gone again when it returns, so it can't leak into the
rest of the page. It resolves against the views directory with the same
containment check as `render()`, and a partial can call `partial()`
itself.

## Rendering to a string

`res.renderToString( view, data )` returns what `res.render()` would send
and sends nothing: an HTML email body, a fragment inside a JSON response,
output to cache.

```js
app.post( "/orders", ( req, res ) => {
	var order = orders.create( req.body )
	mailer.send( order.email, "Your order", res.renderToString( "emails/receipt", { order: order, layout: "layouts/email" } ) )
	res.json( { id: order.id, summary: res.renderToString( "partials/order-row", { order: order, layout: false } ) } )
} )
```

It merges `app.locals`, `res.locals` and `data` exactly as `render()` does
and follows the same layout rules, so with a `"view layout"` set pass
`layout: false` for a bare fragment. It can be called any number of times
and does not count as a terminal method. `partial()` is still the one to
use inside a view.

`<bx:include template="partials/nav.bxm">` also works, as it always has —
it's plain BoxLang. The differences: an include's path is relative to the
file it's written in rather than to the views directory, and it can't be
handed data of its own.

There are no named blocks or sections beyond `data.body`. Setting a value
on `data` in the view, as above, covers the usual cases (title, extra
head content as a string); for more than that, pass the layout a partial
name to render.
