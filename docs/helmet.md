# Security headers (`boxExpressHelmet()` / `Helmet`)

Applies a set of response headers that harden common attack surfaces —
clickjacking, MIME-sniffing, referrer leakage, cross-origin reads —
mirroring the npm [`helmet`](https://github.com/helmetjs/helmet) package's
most commonly used defaults, with no configuration needed:

```js
app.use( boxExpressHelmet() )
```

| Header | Default |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `SAMEORIGIN` |
| `X-DNS-Prefetch-Control` | `off` |
| `Referrer-Policy` | `no-referrer` |
| `X-Permitted-Cross-Domain-Policies` | `none` |
| `Cross-Origin-Opener-Policy` | `same-origin` |
| `Cross-Origin-Resource-Policy` | `same-origin` |
| `Strict-Transport-Security` | *(off by default — opt in)* |
| `Content-Security-Policy` | *(off by default — opt in)* |

Every option takes three shapes: omitted (the default above), `false`
(skip that header entirely), or an exact string to use instead:

```js
app.use( boxExpressHelmet( {
	frameOptions: "DENY",              // override the default value
	referrerPolicy: false,             // skip this header entirely
	hsts: true,                        // opt in, using the built-in default value
	contentSecurityPolicy: "default-src 'self'"   // opt in, with your own policy
} ) )
```

`hsts` and `contentSecurityPolicy` are opt-in, not on by default like the
rest: `Strict-Transport-Security` only makes sense over an actually-secure
connection — BoxExpress's own `HttpServer` never terminates TLS itself (see
`req.secure` under [Request](request.md)) — so turning it on unconditionally could advertise a
guarantee the app doesn't meet. A generic default `Content-Security-Policy`
is exactly the kind of thing that breaks a real app's own inline
scripts/styles or asset domains if applied blindly, so it needs the app's
own policy string rather than a one-size-fits-all default.

BoxExpress never sets an `X-Powered-By` header in the first place (unlike
Express), so there's nothing here to remove the way `helmet` does.
