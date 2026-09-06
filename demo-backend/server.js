const http = require('http');

const PORT = process.env.PORT ? Number(process.env.PORT) : 3000;

const routes = {
  'GET /v1/users/42': { id: 42, name: 'Ada Lovelace (real backend)' },
};

const server = http.createServer((req, res) => {
  const key = `${req.method} ${req.url}`;
  const body = routes[key];

  if (!body) {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: `no demo route for ${key}` }));
    return;
  }

  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`demo-backend listening on http://127.0.0.1:${PORT}`);
});
