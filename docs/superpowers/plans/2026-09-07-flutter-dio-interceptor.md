# Flutter/Dio Interceptor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Flutter apps using `dio` get the same request-mocking behavior Android apps get from `client/`'s `GhostBeInterceptor` and iOS apps get from `ios/GhostBe/`'s `GhostBeURLProtocol`.

**Architecture:** A plain Dart package (`flutter/ghost_be/`, own `pubspec.yaml`) implementing the same wire protocol as the other two clients — build a request envelope, POST it to `ghost-be`'s `/intercept` endpoint, decode the response, and either resolve with a fabricated `Response` (via dio's `handler.resolve(...)`, which — unlike Alamofire's `RequestInterceptor` — can fully substitute a response) or let the request proceed.

**Tech Stack:** Dart 3, `dio`, `package:test`.

**Spec:** [`docs/superpowers/specs/2026-09-07-flutter-dio-interceptor-design.md`](../specs/2026-09-07-flutter-dio-interceptor-design.md)

## Global Constraints

- No pub.dev publishing — git dependency only (spec §7).
- No `http` package support, `dio` only (spec §9).
- No Kotlin/KMP/FFI involvement (spec §3).
- SDK floor `>=3.4.0 <4.0.0` (spec §7) — confirmed compatible: this dev machine already has Dart 3.12.2 stable installed at `/home/portonics/development/flutter/bin/dart`.
- Unlike the iOS/macOS work, **this can be fully developed and verified locally** — Dart/Flutter tooling has no Apple-host constraint. `flutter-check.yml` (Task 3) is a CI regression check mirroring commands already run locally in Tasks 1-2, not the only verification venue.
- `RequestEnvelope`/`ResponseEnvelope` JSON field names must exactly match the Android (`client/src/Envelope.kt`) and iOS (`ios/GhostBe/Sources/GhostBe/Envelope.swift`) implementations: `method`, `url`, `headers`, `body` (base64, nullable) on the request; `intercept`, `status`, `headers`, `body` (base64) on the response.

---

### Task 1: Scaffold the package, implement and test the envelope

**Files:**
- Create: `flutter/ghost_be/pubspec.yaml`
- Create: `flutter/ghost_be/lib/ghost_be.dart`
- Create: `flutter/ghost_be/lib/src/envelope.dart`
- Test: `flutter/ghost_be/test/envelope_test.dart`

**Interfaces:**
- Produces: `RequestEnvelope(method, url, headers, body).toJson()`, `ResponseEnvelope.fromJson(Map<String, dynamic>)` returning either `MockResponseEnvelope(status, headers, body)` or `PassthroughResponseEnvelope()` — consumed by Task 2's `GhostBeInterceptor`.

- [ ] **Step 1: Write `pubspec.yaml`**

```yaml
name: ghost_be
description: A dio interceptor that mocks HTTP responses via ghost-be during development.
version: 0.1.0
environment:
  sdk: '>=3.4.0 <4.0.0'

dependencies:
  dio: ^5.4.0

dev_dependencies:
  test: ^1.25.0
```

- [ ] **Step 2: `cd flutter/ghost_be && dart pub get`**

Run: `dart pub get`
Expected: resolves `dio` and `test` successfully, creates `pubspec.lock`.

- [ ] **Step 3: Write the failing envelope test**

```dart
// flutter/ghost_be/test/envelope_test.dart
import 'dart:convert';
import 'package:test/test.dart';
import 'package:ghost_be/src/envelope.dart';

void main() {
  test('RequestEnvelope encodes to the exact wire JSON shape', () {
    final envelope = RequestEnvelope(
      method: 'GET',
      url: 'http://x/v1/users/42',
      headers: {'Accept': 'application/json'},
      body: null,
    );
    final decoded = jsonDecode(jsonEncode(envelope.toJson())) as Map<String, dynamic>;
    expect(decoded, {
      'method': 'GET',
      'url': 'http://x/v1/users/42',
      'headers': {'Accept': 'application/json'},
      'body': null,
    });
  });

  test('ResponseEnvelope.fromJson decodes a mock response', () {
    final json = {
      'intercept': true,
      'status': 201,
      'headers': {'Content-Type': 'application/json'},
      'body': 'eyJvayI6dHJ1ZX0=',
    };
    final decision = ResponseEnvelope.fromJson(json);
    expect(decision, isA<MockResponseEnvelope>());
    final mock = decision as MockResponseEnvelope;
    expect(mock.status, 201);
    expect(mock.headers, {'Content-Type': 'application/json'});
    expect(mock.body, 'eyJvayI6dHJ1ZX0=');
  });

  test('ResponseEnvelope.fromJson decodes a passthrough response', () {
    final decision = ResponseEnvelope.fromJson({'intercept': false});
    expect(decision, isA<PassthroughResponseEnvelope>());
  });

  test('ResponseEnvelope.fromJson treats a missing intercept key as passthrough', () {
    final decision = ResponseEnvelope.fromJson({});
    expect(decision, isA<PassthroughResponseEnvelope>());
  });
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd flutter/ghost_be && dart test test/envelope_test.dart`
Expected: FAIL — `package:ghost_be/src/envelope.dart` doesn't exist yet.

- [ ] **Step 5: Write `envelope.dart`**

```dart
// flutter/ghost_be/lib/src/envelope.dart
class RequestEnvelope {
  final String method;
  final String url;
  final Map<String, String> headers;
  final String? body;

  RequestEnvelope({
    required this.method,
    required this.url,
    required this.headers,
    this.body,
  });

  Map<String, dynamic> toJson() => {
        'method': method,
        'url': url,
        'headers': headers,
        'body': body,
      };
}

sealed class ResponseEnvelope {
  factory ResponseEnvelope.fromJson(Map<String, dynamic> json) {
    final intercept = json['intercept'] as bool? ?? false;
    if (intercept) {
      return MockResponseEnvelope(
        status: json['status'] as int,
        headers: Map<String, String>.from(json['headers'] as Map),
        body: json['body'] as String,
      );
    }
    return PassthroughResponseEnvelope();
  }
}

class MockResponseEnvelope implements ResponseEnvelope {
  final int status;
  final Map<String, String> headers;
  final String body;

  MockResponseEnvelope({
    required this.status,
    required this.headers,
    required this.body,
  });
}

class PassthroughResponseEnvelope implements ResponseEnvelope {}
```

- [ ] **Step 6: Write `lib/ghost_be.dart`** (the package's public entry point — empty export for now, Task 2 adds the interceptor to it)

```dart
// flutter/ghost_be/lib/ghost_be.dart
export 'src/ghost_be_interceptor.dart' show GhostBeInterceptor;
```

This references `src/ghost_be_interceptor.dart`, which doesn't exist until Task 2 — that's fine, `dart test test/envelope_test.dart` (Step 7) only imports `src/envelope.dart` directly, not this file.

- [ ] **Step 7: Run test to verify it passes**

Run: `cd flutter/ghost_be && dart test test/envelope_test.dart`
Expected: PASS — all four cases.

- [ ] **Step 8: Commit**

```bash
git add flutter/ghost_be/pubspec.yaml flutter/ghost_be/pubspec.lock flutter/ghost_be/lib/src/envelope.dart flutter/ghost_be/test/envelope_test.dart
git commit -m "feat: scaffold flutter/ghost_be package, implement envelope encode/decode"
```

(`lib/ghost_be.dart` isn't added yet — it references a file Task 2 creates; add it together with Task 2's commit instead, or `git add` it now if you'd rather commit the (currently broken-if-imported, but nothing imports it yet) export file alongside Step 8. Simplest: hold off, add it in Task 2's commit.)

---

### Task 2: Implement and test `GhostBeInterceptor`

**Files:**
- Create: `flutter/ghost_be/lib/src/ghost_be_interceptor.dart`
- Modify: `flutter/ghost_be/lib/ghost_be.dart` (already written in Task 1 Step 6 — just `git add` it now)
- Test: `flutter/ghost_be/test/ghost_be_interceptor_test.dart`

**Interfaces:**
- Consumes: `RequestEnvelope`, `ResponseEnvelope`/`MockResponseEnvelope`/`PassthroughResponseEnvelope` from Task 1.
- Produces: `GhostBeInterceptor({String baseUrl})` extending `dio`'s `Interceptor` — the package's public API.

- [ ] **Step 1: Write the failing test**

Uses Dart's own `dart:io HttpServer` as the stub — no subprocess needed, and `HttpServer.bind` only completes once actually listening (no polling required, unlike the iOS test's stub-startup wait).

```dart
// flutter/ghost_be/test/ghost_be_interceptor_test.dart
import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:test/test.dart';
import 'package:ghost_be/ghost_be.dart';

Future<HttpServer> _stub(String responseJson) async {
  final server = await HttpServer.bind('127.0.0.1', 0);
  server.listen((request) async {
    request.response.headers.contentType = ContentType.json;
    request.response.write(responseJson);
    await request.response.close();
  });
  return server;
}

void main() {
  test('resolves the mocked response without calling the real backend', () async {
    final ghostBe = await _stub(jsonEncode({
      'intercept': true,
      'status': 201,
      'headers': {'Content-Type': 'application/json'},
      'body': base64Encode(utf8.encode('{"ok":true}')),
    }));
    addTearDown(() => ghostBe.close(force: true));

    final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:${ghostBe.port}'));
    final response = await dio.get<List<int>>(
      'http://example.invalid/v1/users/42',
      options: Options(responseType: ResponseType.bytes),
    );

    expect(response.statusCode, 201);
    expect(utf8.decode(response.data!), '{"ok":true}');
  });

  test('falls through to the real request when ghost-be says passthrough', () async {
    final ghostBe = await _stub(jsonEncode({'intercept': false}));
    addTearDown(() => ghostBe.close(force: true));
    final realBackend = await _stub(jsonEncode({'real': 'backend'}));
    addTearDown(() => realBackend.close(force: true));

    final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:${ghostBe.port}'));
    final response = await dio.get<String>('http://127.0.0.1:${realBackend.port}/v1/users/42');

    expect(response.statusCode, 200);
    expect(response.data, jsonEncode({'real': 'backend'}));
  });

  test('falls through when ghost-be is unreachable', () async {
    final realBackend = await _stub(jsonEncode({'real': 'backend'}));
    addTearDown(() => realBackend.close(force: true));

    // Port 1 refuses connections -- nothing is listening there.
    final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:1'));
    final response = await dio.get<String>('http://127.0.0.1:${realBackend.port}/v1/users/42');

    expect(response.statusCode, 200);
    expect(response.data, jsonEncode({'real': 'backend'}));
  });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd flutter/ghost_be && dart test test/ghost_be_interceptor_test.dart`
Expected: FAIL — `GhostBeInterceptor` doesn't exist yet.

- [ ] **Step 3: Write `ghost_be_interceptor.dart`**

```dart
// flutter/ghost_be/lib/src/ghost_be_interceptor.dart
import 'dart:convert';
import 'package:dio/dio.dart';
import 'envelope.dart';

class GhostBeInterceptor extends Interceptor {
  final String baseUrl;
  final Dio _relayClient = Dio();

  GhostBeInterceptor({this.baseUrl = 'http://127.0.0.1:8787'});

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final bodyBytes = _extractBodyBytes(options.data);
    final envelope = RequestEnvelope(
      method: options.method,
      url: options.uri.toString(),
      headers: options.headers.map((key, value) => MapEntry(key, value.toString())),
      body: bodyBytes != null ? base64Encode(bodyBytes) : null,
    );

    final ResponseEnvelope decision;
    try {
      final relayResponse = await _relayClient.post<Map<String, dynamic>>(
        '${baseUrl.replaceAll(RegExp(r"/+$"), "")}/intercept',
        data: envelope.toJson(),
        options: Options(contentType: 'application/json', responseType: ResponseType.json),
      );
      decision = ResponseEnvelope.fromJson(relayResponse.data!);
    } catch (_) {
      // ghost-be unreachable -- treat exactly like "no rule matched".
      handler.next(options);
      return;
    }

    switch (decision) {
      case PassthroughResponseEnvelope():
        handler.next(options);
      case MockResponseEnvelope(status: final status, headers: final mockHeaders, body: final body):
        handler.resolve(
          Response(
            requestOptions: options,
            statusCode: status,
            headers: Headers.fromMap(mockHeaders.map((k, v) => MapEntry(k, [v]))),
            data: base64Decode(body),
          ),
        );
    }
  }

  List<int>? _extractBodyBytes(dynamic data) {
    if (data == null) return null;
    if (data is String) return utf8.encode(data);
    if (data is List<int>) return data;
    if (data is FormData) {
      // Not yet supported: multipart bodies can't be represented in the
      // envelope's single base64 body field. Send no body rather than
      // throwing -- ghost-be still sees the request's method/url/headers
      // and can match/mock it; only the multipart payload itself is lost
      // if a rule's response happens to echo the request body back.
      return null;
    }
    try {
      return utf8.encode(jsonEncode(data));
    } catch (_) {
      return null;
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd flutter/ghost_be && dart test`
Expected: PASS — all 7 tests (4 from Task 1's `envelope_test.dart`, 3 here).

- [ ] **Step 5: Run static analysis**

Run: `cd flutter/ghost_be && dart analyze`
Expected: "No issues found!" Fix anything it flags before moving on.

- [ ] **Step 6: Commit**

```bash
git add flutter/ghost_be/lib/ flutter/ghost_be/test/ghost_be_interceptor_test.dart
git commit -m "feat: implement GhostBeInterceptor (dio), test mock/passthrough/unreachable"
```

---

### Task 3: CI + documentation

**Files:**
- Create: `.github/workflows/flutter-check.yml`
- Modify: `README.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Write `flutter-check.yml`**

```yaml
# .github/workflows/flutter-check.yml
name: Flutter Check

on:
  workflow_dispatch:

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dart-lang/setup-dart@v1
      - name: Install dependencies
        run: dart pub get
        working-directory: flutter/ghost_be
      - name: Analyze
        run: dart analyze
        working-directory: flutter/ghost_be
      - name: Test
        run: dart test
        working-directory: flutter/ghost_be
```

- [ ] **Step 2: Commit, push, trigger, watch**

```bash
git add .github/workflows/flutter-check.yml
git commit -m "ci: add Flutter Check workflow"
git push origin master
gh workflow run "Flutter Check" -R fcat97/ghost-be --ref master
```

Then watch it with `gh run watch <id> --exit-status`. Since this was already verified locally in Tasks 1-2, this should pass on the first attempt — if it doesn't, the difference between the local environment and `ubuntu-latest` (Dart version, missing `dart-lang/setup-dart` pin, etc.) is itself worth understanding before moving on, not just re-triggering blindly.

- [ ] **Step 3: Add a "Flutter" subsection to README's "Using it in your app"**

Mirror the Android/iOS subsections' structure:

```markdown
### Flutter

Add this repo as a git dependency in `pubspec.yaml`:

\`\`\`yaml
dependencies:
  ghost_be:
    git:
      url: https://github.com/fcat97/ghost-be
      path: flutter/ghost_be
\`\`\`

Add the interceptor to whichever \`Dio\` instance your app uses:

\`\`\`dart
import 'package:dio/dio.dart';
import 'package:ghost_be/ghost_be.dart';

final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:8787'));
\`\`\`
```

- [ ] **Step 4: Add a Flutter variant to AGENTS.md's step 2**

Mirroring the existing Android/iOS instructions there.

- [ ] **Step 5: Commit**

```bash
git add README.md AGENTS.md
git commit -m "docs: document the Flutter/dio ghost_be package"
git push origin master
```
