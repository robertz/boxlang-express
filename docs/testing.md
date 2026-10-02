# Testing your app without a server (`app.inject()`)

`app.inject( options )` runs one request through the app — routing,
middleware, handlers, error handling — on the calling thread, without
opening a port, and returns what the app produced:

```js
var res = app.inject( { method: "POST", url: "/users", body: { name: "ada" } } )
expect( res.statusCode ).toBe( 201 )
expect( res.json().name ).toBe( "ada" )
```

A bare string is a `GET`: `app.inject( "/health/ready" )`. Options:
`method` (default `"GET"`); `url` (path plus optional query string —
encoded characters in the path are decoded, as for a real request);
`query` (a struct merged into the query string, URL-encoded; an array
value repeats the key); `headers`; `body` (a struct or array is sent as
JSON with the matching `Content-Type`, a string or binary value as-is);
`form` (a struct, sent urlencoded); `cookies` (a struct, sent as the
`Cookie` header); `ip` (what `req.ip` sees, default `127.0.0.1`); and
`log` (print the usual request log line, off by default).

The result has `statusCode`, `body` (a string), `headers` (a struct with
lower-cased names), `json()`, `header( name )` (case-insensitive, `""` if
absent), and the cookies the response set: `cookies` (name to value) and
`setCookies` (each raw `Set-Cookie` line). A response's `cookies` is shaped
to be passed straight back in, which carries a session across calls:

```js
var login = app.inject( { method: "POST", url: "/login", form: { user: "ada" } } )
var me    = app.inject( { url: "/me", cookies: login.cookies } )
```

The request goes through the same `Request`/`Response` objects, router
and error handling as one arriving over a socket, so a `404`, a thrown
handler becoming a `500`, an oversized body becoming a `413`, and a
response header refused for containing a newline all behave the same. Two
things it can't do: `req.rawExchange()` is `null` (there is no Undertow
behind an injected request), and `app.ws()` routes can't be reached this
way — those need a real server and a WebSocket client.
