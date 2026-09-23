// ghost-be/journal.js -- print what the app sent, for debugging a failing flow.
//
// env (all optional):
//   METHOD        filter by HTTP method
//   PATH          filter by exact path
//   BODY_CONTAINS filter by a substring of the decoded body
//   LIMIT         how many entries to print (default 20)
//   GHOST_BE_URL  optional; defaults to http://127.0.0.1:44678
//
// Usage in a flow:
//   - runScript: ghost-be/journal.js
//
// Asserts nothing and never fails a flow -- drop it in above a failing verify step to
// see what the app actually sent. Output goes to the Maestro console.

var base = (typeof GHOST_BE_URL !== 'undefined' && GHOST_BE_URL) || 'http://127.0.0.1:44678';

var query = ['limit=' + ((typeof LIMIT !== 'undefined' && LIMIT) || '20')];
if (typeof METHOD !== 'undefined' && METHOD) query.push('method=' + encodeURIComponent(METHOD));
if (typeof PATH !== 'undefined' && PATH) query.push('path=' + encodeURIComponent(PATH));
if (typeof BODY_CONTAINS !== 'undefined' && BODY_CONTAINS) {
    query.push('bodyContains=' + encodeURIComponent(BODY_CONTAINS));
}

var res = http.get(base + '/api/test/journal?' + query.join('&'));
if (!res.ok) {
    console.log('ghost-be: journal unavailable -- ' + res.status + ' ' + res.body);
} else {
    var data = json(res.body);
    console.log('ghost-be: ' + data.count + ' matching request(s), showing ' + data.entries.length);
    for (var i = 0; i < data.entries.length; i++) {
        var e = data.entries[i];
        console.log(
            '  #' + e.seq + ' ' + e.method + ' ' + e.path +
            ' -> ' + (e.matchedRule === null ? 'passthrough' : e.matchedRule + ' ' + e.status) +
            (e.body ? ' body=' + e.body : '')
        );
    }
}
