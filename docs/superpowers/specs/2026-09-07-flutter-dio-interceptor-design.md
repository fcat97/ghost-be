# GhostBe: Flutter/Dio Interceptor — Design

Status: approved for planning
Date: 2026-09-07

## 1. Purpose

Extend GhostBe's request-mocking capability to Flutter apps that use `dio`,
mirroring what `client/`'s `GhostBeInterceptor` does for Android/OkHttp and
`ios/GhostBe/`'s `GhostBeURLProtocol` does for iOS/Alamofire: ask `ghost-be`
what to do with each request, and either return the response it configures
or let the request proceed to the real backend.

## 2. Why this is simpler than the iOS client

Unlike Alamofire's `RequestInterceptor` (which cannot substitute a fake
response — see the [iOS design doc](2026-09-07-ios-alamofire-interceptor-design.md#2-why-not-requestinterceptor)),
dio's `Interceptor.onRequest(options, handler)` can fully short-circuit the
request chain by calling `handler.resolve(Response(...))`, fabricating a
complete response and skipping the real network call entirely — the same
capability OkHttp's `Interceptor.intercept(chain)` has on Android.
Confirmed against dio's own documented usage pattern before committing to
this design (unlike the iOS case, where the equivalent assumption for
`RequestInterceptor` turned out to be wrong and required a design revision
mid-implementation). No `URLProtocol`-style low-level workaround is needed
here: a plain dio `Interceptor` subclass implements the whole thing.

## 3. Why not Kotlin Multiplatform

Considered and rejected for the same reasons as the iOS client (see
[iOS design doc §4](2026-09-07-ios-alamofire-interceptor-design.md#4-architecture-pure-native-swift-no-shared-kotlin-core)):
Amper has no confirmed, documented mechanism for exporting Kotlin/Native
code to a foreign-language consumer outside its own klib format (proven
for Apple frameworks; a Dart-FFI-consumable C-ABI shared library would
face the same undocumented-tooling-gap risk, for logic small enough --
envelope building, one HTTP POST, JSON encode/decode -- that hand-writing
it natively is less work than making a foreign-function-interface
boundary work at all. Each platform client (Android, iOS, now Flutter)
independently re-implements this same small wire protocol natively.

## 4. Architecture

A plain Dart package at `flutter/ghost_be/` (own `pubspec.yaml`), zero
Kotlin/Native/Flutter-engine-native involvement. Depends on `dio` for the
`Interceptor` base class and its own internal relay call.

```dart
// flutter/ghost_be/lib/src/envelope.dart
class RequestEnvelope {
  final String method;
  final String url;
  final Map<String, String> headers;
  final String? body; // base64

  RequestEnvelope({required this.method, required this.url, required this.headers, this.body});

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
  final String body; // base64
  MockResponseEnvelope({required this.status, required this.headers, required this.body});
}

class PassthroughResponseEnvelope implements ResponseEnvelope {}
```

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

    late final ResponseEnvelope decision;
    try {
      final relayResponse = await _relayClient.post(
        '${baseUrl.replaceAll(RegExp(r"/+$"), "")}/intercept',
        data: envelope.toJson(),
        options: Options(contentType: 'application/json'),
      );
      decision = ResponseEnvelope.fromJson(relayResponse.data as Map<String, dynamic>);
    } catch (_) {
      // ghost-be unreachable -- treat exactly like "no rule matched".
      return handler.next(options);
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
    return utf8.encode(jsonEncode(data)); // best-effort for Map/List bodies
  }
}
```

Public API is just adding the interceptor to any `Dio` instance:

```dart
final dio = Dio()..interceptors.add(GhostBeInterceptor());
```

## 5. Data flow

App issues a request through a `Dio` instance with `GhostBeInterceptor`
added → `onRequest` builds the envelope and POSTs it to `ghost-be`'s
`/intercept` endpoint via an internal `Dio` client → decodes the JSON
response → `MockResponseEnvelope` calls `handler.resolve(...)`, fabricating
the response and skipping the real network call entirely;
`PassthroughResponseEnvelope` (or `ghost-be` unreachable, caught as an
exception around the relay call) calls `handler.next(options)`, letting
dio's normal request pipeline continue exactly as if this interceptor
weren't there.

## 6. Error handling

Same posture as Android/iOS: `ghost-be` unreachable is treated identically
to "no rule matched" — `handler.next(options)`, never a hard failure
surfaced to the app.

## 7. Distribution

Git dependency (not published to pub.dev), consistent with iOS (git-based
SPM) rather than requiring a separate package-registry publish flow:

```yaml
dependencies:
  ghost_be:
    git:
      url: https://github.com/fcat97/ghost-be
      path: flutter/ghost_be
```

`flutter/ghost_be/pubspec.yaml` declares `environment: sdk: '>=3.4.0
<4.0.0'` — a recent-but-not-bleeding-edge stable floor (current stable as
of this design is 3.12.x), a compatibility choice rather than a technical
requirement, matching how the iOS client's "iOS 13" floor was chosen.

## 8. Testing

A `workflow_dispatch`-only `flutter-check.yml` runs on `ubuntu-latest` —
unlike both prior clients, nothing here needs an Apple host at all, since
Dart/Flutter tooling and `dio` have no platform-specific native
dependency. Runs `dart test` against a stub `ghost-be` (the same
`python3 -m http.server`-style subprocess-stub pattern already used for
the iOS XCTest suite) before this is ever wired into a release process
(there is none planned — see §7, this is a source/git dependency with no
release-asset step).

## 9. Non-goals

- No pub.dev publishing.
- No `http` package support — `dio` only (§2's `handler.resolve()`
  capability is dio-specific; the plain `http` package has no interceptor
  concept and would need its own separate `BaseClient`-subclass design,
  out of scope unless requested later).
- No Kotlin/KMP/FFI involvement (§3).
- No Flutter-specific UI/widget code — this is a plain Dart package
  (works in any Dart context using `dio`, not exclusively inside a
  Flutter app), consistent with how the Android/iOS clients are plain
  networking libraries with no UI surface either.

## 10. Open risks carried into implementation

- `_extractBodyBytes`'s handling of `options.data` (§4) covers the common
  cases (`String`, raw bytes, JSON-serializable `Map`/`List`) but dio
  supports other body types (`FormData`, streams) this first pass doesn't
  address — the implementation plan should treat this as a real,
  named gap to either handle or explicitly document as unsupported,
  not silently drop.
- Nothing in this design has been run anywhere yet; `dart analyze`/
  `dart test` in `flutter-check.yml` is the first real verification.
