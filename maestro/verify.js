// ghost-be/verify.js -- assert what the app actually sent to the network.
//
// env:
//   METHOD        required, e.g. POST
//   PATH          required, exact path, e.g. /v1/checkout
//   COUNT         optional; exact expected number of matching requests.
//                 Omit it to mean "at least one".
//   BODY_CONTAINS optional; substring of the request body. Matched against the
//                 decoded body, so write what your app sends, not its base64.
//   SOFT          optional; "true" records the result without failing the flow.
//                 Use it for a warning-level check -- Maestro's `optional: true`
//                 doesn't stop a throwing runScript from failing the flow.
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
//
// A soft check, for a known bug you want flagged but not fatal:
//   - runScript:
//       file: ghost-be/verify.js
//       env: { METHOD: POST, PATH: /v1/search, BODY_CONTAINS: '"page":3', COUNT: "0", SOFT: "true" }
//   - assertTrue: { condition: "${output.ghostBeVerifyOk == 'true'}", optional: true }

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

// Written before the throw below, so the assertTrue step still has a value to read
// (and so a SOFT check leaves a value to assert on).
output.ghostBeVerifyOk = ok ? 'true' : 'false';
output.ghostBeVerifyCount = String(actual);

if (!ok) {
    var message =
        'ghost-be: expected ' + (expected === null ? 'at least 1' : expected) +
        ' request(s) matching ' + METHOD + ' ' + PATH +
        (typeof BODY_CONTAINS !== 'undefined' && BODY_CONTAINS ? ' containing "' + BODY_CONTAINS + '"' : '') +
        ', but saw ' + actual;

    // SOFT: log instead of throwing, so the flow carries on and an `optional: true`
    // assertTrue on ghostBeVerifyOk reports it as a warning.
    if (typeof SOFT !== 'undefined' && SOFT === 'true') {
        console.log(message + ' (SOFT: not failing the flow)');
    } else {
        throw new Error(message);
    }
}
