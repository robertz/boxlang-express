# File uploads (`boxExpressUpload()` / `Multipart`)

Opt-in `multipart/form-data` parsing, mirroring [multer](https://github.com/expressjs/multer)'s
basic usage:

```js
app.use( boxExpressUpload() )                                       // in-memory only
app.use( boxExpressUpload( { dest: expandPath( "./uploads" ) } ) )  // also saved to disk
```

or via the underlying class:

```js
multipart = new bxModules.boxexpress.models.middleware.Multipart()
app.use( multipart.upload( { dest: expandPath( "./uploads" ) } ) )
```

Non-file fields land in `req.body`, same as the other body parsers. File
fields land in `req.files`, a struct keyed by field name, each value an
**array** of file structs (a field can carry more than one file):

```js
app.post( "/upload", boxExpressUpload( { dest: expandPath( "./uploads" ) } ), ( req, res ) => {
	res.json( { note: req.body.note, avatar: req.files.avatar[ 1 ] } )
	// avatar: { fieldName, filename, contentType, size, buffer, path }
} )
```

A field sent more than once (a multi-select, checkboxes sharing a name) is
an array in `req.body`, in the order sent; a field sent once is a string —
the same rule as [query strings](request.md#query-strings).

A part with no `name` in its `Content-Disposition` is answered with a
400 (`Malformed multipart body`).

Every file struct always has `fieldName`, `filename` (the name the client
sent — never trust it as a disk path), `contentType`, `size`, and `buffer`
(the raw bytes, in memory). `path` is only present when `dest` was given —
the file is saved there under a generated UUID name, never the client's own
filename, so there's nothing for a malicious filename to path-traverse or
collide with. The extension is kept only if it's 1–10 letters or digits
(lower-cased); anything else is dropped. Don't put `dest` inside a
directory `boxExpressStatic()` serves: an uploaded `.html` or `.svg` would
then be served back as a page on your origin. Like the other parsers, the whole body is capped at 10MB by
default — override with `{ limit: bytes }`; an oversized upload gets a `413`
before your handler runs.

The number of parts is capped too, since 10MB is room for tens of
thousands of tiny ones — each a struct in memory and, with `dest`, a file
on disk:

| Option | Default | |
|---|---|---|
| `limit` | `10485760` | Size of the whole body, in bytes |
| `maxFiles` | `100` | File parts in one request, across every field |
| `maxFields` | `1000` | Non-file fields in one request |

A request over either count gets a `413`
(`{ error: true, message: "Upload has more than 100 files" }`) and nothing
is written to `dest`. A file input left empty isn't counted. Set a route
that takes one avatar to `maxFiles: 1`; `0` turns either limit off.
