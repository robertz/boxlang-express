# CSRF protection (`boxExpressCsrf()` / `Csrf`)

Mirrors the classic [`csurf`](https://github.com/expressjs/csurf) package's
session-based token strategy. The token lives in `req.session`, not its own
cookie, so this must be registered *after* `boxExpressSession()`:

```js
app.use( boxExpressSession() )
app.use( boxExpressUrlencoded() )   // before Csrf if reading the token from a form field
app.use( boxExpressCsrf() )

app.get( "/form", ( req, res ) => {
	res.render( "form", { csrfToken: req.csrfToken() } )
} )
```

```html
<!-- views/form.bxm -->
<form method="POST" action="/form">
	<input type="hidden" name="_csrf" value="#data.csrfToken#">
	...
</form>
```

```js
app.post( "/form", ( req, res ) => {
	// A missing/mismatched token never reaches this handler — it gets a
	// 403 from the middleware first.
	res.send( "ok" )
} )
```

"Safe" methods (`GET`/`HEAD`/`OPTIONS` by default, override with
`options.ignoreMethods`) never validate. Every other method must
submit a matching token: read from `req.body[fieldName]` first
(`options.fieldName`, default `"_csrf"`), falling back to a request header
(`options.headerName`, default `"X-CSRF-Token"`) for non-form (JSON/AJAX)
clients that can't rely on a hidden form field. Calling
`boxExpressCsrf()` before `req.session` exists (i.e. before
`boxExpressSession()`) throws immediately rather than silently doing
nothing — a misconfiguration here should be loud, not a security hole that
only shows up in production.

## When the token is created

The token is minted the first time `req.csrfToken()` is called for a
session, and stored in `req.session.csrfToken`. A request that never calls
it leaves the session untouched, so with
`boxExpressSession( { saveUninitialized: false } )` a health check, an API
call or a crawler hit creates no session and gets no cookie even with both
middlewares mounted app-wide.

Before 0.2.30 the token was written to the session on every request, which
made every anonymous request a stored session and `saveUninitialized:
false` had no effect.

An unsafe request whose session holds no token is rejected with `403`
without one being minted: a client has to load a page that calls
`req.csrfToken()` before it can post. Code that read
`req.session.csrfToken` directly should call `req.csrfToken()` instead.
