// ghost-be/verify.js -- assert what the app actually sent to the network.
//
// env:
//   METHOD        required, e.g. POST
//   PATH          required, exact path, e.g. /v1/checkout
//   COUNT         optional; exact expected number of matching requests.
//                 Omit it to mean "at least one".
//   BODY_CONTAINS optional; substring of the request body. Matched against the
//                 decoded body, so write what your app sends, not its base64.
//   GHOST_BE_URL  optional; defaults to http://127.0.0.1:44678
//
// Usage in a flow:
//   - runScript:
//       file: ghost-be/verify.js
//       env: { METHOD: POST, PATH: /v1/checkout, COUNT: "1" }
//   - assertTrue: ${output.ghostBeVerifyOk == 'true'}
//
// Compare against the string 'true' as shown. Values on `output` cross step boundaries
// as strings, and a non-empty string is truthy -- a bare `${output.ghostBeVerifyOk}`
// would pass even when the check failed.

var base = (typeof GHOST_BE_URL !== 'undefined' && GHOST_BE_URL) || 'http://127.0.0.1:44678';

var query = [
    'method=' + encodeURIComponent(METHOD),
    'path=' + encodeURIComponent(PATH)
];
if (typeof BODY_CONTAINS !== 'undefined' && BODY_CONTAINS) {
    query.push('bodyContains=' + encodeURIComponent(BODY_CONTAINS));
}

var res = http.get(base + '/api/test/journal/count?' + query.join('&'));
if (!res.ok) {
    throw new Error('ghost-be: journal query failed -- ' + res.status + ' ' + res.body);
}

var actual = parseInt(res.body, 10);
var expected = (typeof COUNT !== 'undefined' && COUNT !== '') ? parseInt(COUNT, 10) : null;
var ok = (expected === null) ? actual > 0 : actual === expected;

// Written before the throw below, so the assertTrue step still has a value to read.
output.ghostBeVerifyOk = ok ? 'true' : 'false';
output.ghostBeVerifyCount = String(actual);

if (!ok) {
    throw new Error(
        'ghost-be: expected ' + (expected === null ? 'at least 1' : expected) +
        ' request(s) matching ' + METHOD + ' ' + PATH +
        (typeof BODY_CONTAINS !== 'undefined' && BODY_CONTAINS ? ' containing "' + BODY_CONTAINS + '"' : '') +
        ', but saw ' + actual
    );
}
