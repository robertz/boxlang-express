# CORS (`boxExpressCors()` / `Cors`)

Cross-Origin Resource Sharing, mirroring the npm [`cors`](https://github.com/expressjs/cors)
package's most commonly used options. With no options, reflects whatever
`Origin` the request sent (or `*` if there wasn't one) — permissive by
default, same as the npm package:

```js
app.use( boxExpressCors() )
app.use( boxExpressCors( { origin: "https://example.com" } ) )
app.use( boxExpressCors( { origin: [ "https://a.com", "https://b.com" ], credentials: true } ) )
```

| Option | Default | Effect |
|---|---|---|
| `origin` | `true` | `true` reflects the request's `Origin`; `false` disables CORS entirely; a string allows only that exact origin (or `"*"` verbatim); an array allows any origin in the list |
| `methods` | `GET,HEAD,PUT,PATCH,POST,DELETE` | `Access-Control-Allow-Methods` on a preflight response |
| `allowedHeaders` | *(reflects the preflight's own request)* | `Access-Control-Allow-Headers` on a preflight response |
| `exposedHeaders` | *(none)* | `Access-Control-Expose-Headers` on every response |
| `credentials` | `false` | sets `Access-Control-Allow-Credentials: true` when `true`. Requires an explicit `origin` (a string or array): combined with `origin: true` or `"*"` it throws, since reflecting any origin with credentials lets every website make authenticated requests as your visitors |
| `maxAge` | *(none)* | `Access-Control-Max-Age` (seconds) on a preflight response |
| `preflightContinue` | `false` | call `next()` for a preflight instead of answering it directly |
| `optionsSuccessStatus` | `204` | status code for a handled preflight |

A CORS preflight — an `OPTIONS` request carrying
`Access-Control-Request-Method` — is answered directly by this middleware
(status `204`, the relevant headers, no body) rather than falling through
to the router, since nothing would otherwise be registered to handle
`OPTIONS` on an arbitrary route. Pass `{ preflightContinue: true }` if a
later handler needs to see the preflight request itself instead.
