# Request validation (`boxExpressValidate()` / `Validate`)

Checks `req.body`, `req.query` and `req.params` against a schema before
your handler runs. Values are converted to the types you declare,
defaults are filled in, and a request that doesn't fit is answered with a
`400` listing every problem — so the handler can assume its input is the
right shape.

```js
app.post( "/users",
	boxExpressJSON(),
	boxExpressValidate( {
		body: {
			email: { type: "email", required: true },
			name: { type: "string", required: true, trim: true, maxLength: 80 },
			age: { type: "integer", min: 18 },
			role: { type: "string", oneOf: [ "admin", "member" ], default: "member" }
		},
		query: {
			notify: { type: "boolean", default: false }
		}
	} ),
	( req, res ) => {
		var user = users.create( req.valid.body )     // email, name, age, role — nothing else
		res.status( 201 ).json( user )
	}
)
```

Put it after the body parser: it reads `req.body`, it doesn't parse it.

## What the handler gets

**`req.valid`** holds `body`, `query` and `params` — whichever the schema
covers — containing *only the fields the schema names*, converted. That
makes it safe to hand to a model: a client that adds `"isAdmin": true` to
its JSON doesn't get it into `req.valid.body`.

`req.body` and `req.query` are also updated in place (converted values,
defaults), and keep any other fields unless you say otherwise (`unknown`,
below). `req.params` isn't — read converted route params from
`req.valid.params`.

## What a failure looks like

```
HTTP/1.1 400 Bad Request

{"error":true,"message":"Validation failed","errors":[
  {"in":"body","field":"email","message":"must be a valid email address"},
  {"in":"body","field":"age","message":"must be at least 18"},
  {"in":"query","field":"notify","message":"must be true or false"}
]}
```

Every problem is reported, not just the first. Nested fields are named by
path: `address.city`, `tags[2]`. The handler doesn't run.

## Rules

| Rule | Applies to | Meaning |
|---|---|---|
| `type` | any | One of the types below; `"any"` if omitted |
| `required` | any | The field must be present and not empty |
| `default` | any | Used when the field is missing or empty |
| `min`, `max` | `integer`, `number` | Smallest and largest value |
| `minLength`, `maxLength` | strings | Length in characters |
| `pattern` | strings | A regular expression the value must match |
| `oneOf` | simple values | An array of allowed values, compared exactly |
| `trim` | strings | Strip surrounding whitespace before checking |
| `items` | `array` | Rules for each element |
| `minItems`, `maxItems` | `array` | Number of elements |
| `fields` | `struct` | Rules for a nested object's fields |
| `validate` | any | Your own check — see below |
| `message` | any | Replaces the default message for this field |

| Type | Accepts | `req.valid` holds |
|---|---|---|
| `string` | Any simple value | A string |
| `integer` | A whole number, or a string of digits (`"42"`, `"-7"`) | A number |
| `number` | A number, or a numeric string (`"0.25"`) | A number |
| `boolean` | `true`/`false`, or `"true"`, `"false"`, `"1"`, `"0"`, `"yes"`, `"no"`, `"on"`, `"off"` | A boolean |
| `email` | Something shaped like `name@host.tld` | The string |
| `url` | An `http` or `https` URL with a host | The string |
| `uuid` | A UUID, in the standard form or the one `createUUID()` produces | The string |
| `date` | ISO 8601: `2026-11-15` or `2026-11-15T09:30:00Z` | The string |
| `array` | An array | The array, items converted |
| `struct` | An object | Its declared fields, converted |
| `any` | Anything | The value unchanged |

Conversion is what makes this useful for query strings and form posts,
where everything arrives as text: `?page=2` becomes the number `2`. It's
deliberately strict — `"1e3"`, `" 7"` and `"1.0"` are not integers.

**Missing vs. empty.** A field that's absent, JSON `null`, or an empty
string counts as missing: it gets its `default`, or fails `required`. The
one exception is an optional `string` field, where `""` is a valid value.
Whitespace only counts as empty if `trim` is on.

**Your own check.** `validate` receives the converted value and the
request. Return `true` to accept; return `false`, or a string to use as
the message, to reject:

```js
endDate: { type: "date", required: true, validate: ( value, req ) => {
	return value >= req.body.startDate ? true : "must not be before startDate"
} }
```

It runs after the built-in rules pass, so `value` is already the right
type. Fields are checked independently and in no particular order, so a
cross-field check like this one should read the other field from
`req.body`, not `req.valid`.

## Options

`boxExpressValidate( schema, options )`:

- **`unknown`** — what to do with fields the schema doesn't name, at any
  depth: `"allow"` (default) leaves them on `req.body`/`req.query`;
  `"strip"` removes them; `"reject"` makes each one an error
  (`"is not allowed"`).
- **`status`** — the failure status, `400` by default. `422` is a common
  alternative.
- **`onError`** — `( errors, req, res, next ) => {...}`, called instead
  of sending the default response; for re-rendering a form with messages,
  or your own error format:

  ```js
  boxExpressValidate( schema, { onError: ( errors, req, res, next ) => {
  	res.status( 400 ).render( "signup", { errors: errors, values: req.body } )
  } } )
  ```

## Things worth knowing

- **A mistake in the schema throws when the route is registered** — an
  unknown type or rule name (`requried: true`), an invalid `pattern`. It
  can't silently validate nothing.
- **`email` and `url` check shape, not existence.** `a@b.co` passes. To
  know an address is real, send it a message.
- **`pattern` is a regular expression run on client input**; avoid nested
  quantifiers like `(a+)+`, and set a `maxLength` alongside it.
- **Validate route params here, or constrain them in the path** — see
  [Path patterns](routing.md#path-patterns). A path constraint decides
  *which route matches* (and falls through to a 404); validation answers
  a `400` saying what was wrong.
- `date` values stay strings. Parse them (`parseDateTime()`) where you
  use them.
- Not included: validating headers or cookies, conditional rules ("required
  if another field is set" — use `validate`), and generating API
  documentation from a schema.
