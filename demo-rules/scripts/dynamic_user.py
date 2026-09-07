#!/usr/bin/env python3
"""Sample ghost-be script rule.

ghost-be runs this as: python3 dynamic_user.py
It writes the request as JSON to this script's stdin, and reads a JSON
response back from stdout. This script exists to document that contract
with a real, runnable example -- see demo-rules/dynamic.yaml for the rule
that wires it up.

Request JSON (read from stdin):
{
  "method": "GET",                          # HTTP method, uppercase
  "url": "http://.../v1/users/42",          # full URL, including query string
  "headers": {"Accept": "application/json"}, # request headers, flattened to strings
  "body": null                              # request body, base64-encoded, or null
}

Response JSON (written to stdout):
{
  "status": 200,                    # HTTP status code to send back
  "headers": {"Content-Type": ...}, # response headers (optional, may be omitted/{})
  "body": "<base64>"                # response body, base64-encoded
}

A non-zero exit code, or stdout that doesn't match this shape, becomes a
500 response describing the failure -- the app still gets a response, it
just won't be this script's.
"""
import base64
import json
import re
import sys


def main() -> None:
    request = json.loads(sys.stdin.read())

    match = re.search(r"/v1/users/(\d+)", request["url"])
    user_id = int(match.group(1)) if match else None

    if user_id == 42:
        payload = {"id": 42, "name": "Ada Lovelace (mocked by ghost-be script)"}
        status = 200
    else:
        payload = {"error": f"no such user: {user_id}"}
        status = 404

    body = base64.b64encode(json.dumps(payload).encode()).decode()

    response = {
        "status": status,
        "headers": {"Content-Type": "application/json"},
        "body": body,
    }
    sys.stdout.write(json.dumps(response))


if __name__ == "__main__":
    main()
