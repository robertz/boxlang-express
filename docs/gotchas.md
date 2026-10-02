# BoxLang gotchas worth knowing

`server`, `application`, `session`, `request`, `url`, `form`, `cookie`, and
`static` are reserved BoxLang scope names — assigning a variable one of those
names silently shadows the built-in scope instead of erroring at parse time,
which produces confusing runtime errors. This codebase avoids all of them
(e.g. `undertowServer` instead of `server`, `formData` instead of `form`, the
static-file middleware class is named `StaticFiles` rather than `Static`).

`JSONSerialize()` (and therefore `res.json()`) only serializes literal
structs — not BoxLang class instances, even though `isStruct()` returns
`true` for one and its properties are freely readable via dot access.
`req` is a `Request` instance, so `res.json(req)` silently returns `{}`
rather than an error, no matter how many of `req.method`/`req.path`/
`req.query`/etc. actually have values — `property` declarations and a
`getMemento()` method don't change this. Build a plain struct out of the
fields you want instead:

```js
res.json( { method: req.method, path: req.path, query: req.query, params: req.params, body: req.body } )
```

The same applies to `res`, or any other class instance from this codebase
(or your own) — pass `res.json()` a struct, never an object.
