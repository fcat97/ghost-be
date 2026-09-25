// ghost-be/reset.js -- clear all ghost-be test state.
//
// env:
//   GHOST_BE_URL  optional; defaults to http://127.0.0.1:44678
//
// Usage in a flow:
//   - runScript: ghost-be/reset.js
//
// Clears active scenarios, per-rule call counters and the request journal in one call.
// Run this at the start of every flow: without it, a response sequence would resume
// wherever the previous flow left it, and a verify would count the previous flow's
// requests as well as this one's.
//
// Nothing on disk is touched -- your rules directory is never written to.

var base = (typeof GHOST_BE_URL !== 'undefined' && GHOST_BE_URL) || 'http://127.0.0.1:44678';

// Maestro's http.post rejects a POST with no body ("method POST must have a request
// body"), and the resulting script error hangs `maestro test` instead of failing the
// flow -- so always send an empty one.
var res = http.post(base + '/api/test/reset', { body: '' });

if (!res.ok) {
    throw new Error('ghost-be: reset failed -- ' + res.status + ' ' + res.body);
}

output.ghostBeReady = 'true';
