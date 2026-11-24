# Error handling

If no route matches, a default `404` JSON response is sent. If a handler
throws (or calls `next(err)`) and no error-handling middleware is registered,
a default `500` JSON response is sent — with a generic `"Internal Server
Error"` message, not the real exception message, since that can otherwise
leak internal details (file paths, driver errors, etc.) to an unauthenticated
client. The real message is still logged to stdout either way. Opt in to
seeing it in the response too — for local development, never in
production — with:

```js
app.set( "env", "development" )
```

## Errors after the response is sent

Once a response has gone out there is nothing left for an error handler to
do, so an error thrown (or passed to `next(err)`) after `res.send()` and
friends skips error-handling middleware. Since 0.2.30 it is logged to
stdout instead of disappearing:

```
[2026-11-23 20:02:26] Error after the response was sent, GET /orders: audit write failed
```

```js
app.get( "/orders", ( req, res ) => {
	res.json( orders )
	auditLog.write( req )   // a throw here is logged; the client already has its 200
} )
```

The response is not changed. The same line appears when a client hangs up
in the middle of a file download or an SSE stream, since that surfaces as
an error after the headers were sent. A request that hit
`requestTimeoutMs` is logged as a timeout, not here.
