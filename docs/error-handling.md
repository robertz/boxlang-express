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
