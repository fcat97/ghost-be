// ghost-be/scenario.js -- make exactly the named scenarios active.
//
// env:
//   SCENARIOS     comma-separated scenario names. An empty string clears all of them.
//   GHOST_BE_URL  optional; defaults to http://127.0.0.1:44678
//
// Usage in a flow:
//   - runScript:
//       file: ghost-be/scenario.js
//       env: { SCENARIOS: checkout-fails }
//
// This is a full replacement, not an add: whatever you list becomes the entire active
// set, so one step puts ghost-be in an exactly known state.

var base = (typeof GHOST_BE_URL !== 'undefined' && GHOST_BE_URL) || 'http://127.0.0.1:44678';

var names = ((typeof SCENARIOS !== 'undefined' && SCENARIOS) || '')
    .split(',')
    .map(function (s) { return s.trim(); })
    .filter(function (s) { return s.length > 0; });

var res = http.put(base + '/api/test/scenarios', {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active: names })
});

// A 400 means a name here matches no rule ghost-be has loaded -- almost always a typo.
// The response body lists the names that ARE available, so surface it verbatim rather
// than swallowing it: a silently-ignored scenario gives you a green test that asserts
// nothing.
if (!res.ok) {
    throw new Error(
        'ghost-be: could not activate [' + names.join(', ') + '] -- ' + res.status + ' ' + res.body
    );
}

output.ghostBeScenarios = names.join(',');
