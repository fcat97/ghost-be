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
  tearDown(GhostBe.clearCapturedServer);

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

  group('decodes the mocked body according to the request\'s ResponseType', () {
    late HttpServer ghostBe;
    late Dio dio;

    setUp(() async {
      ghostBe = await _stub(jsonEncode({
        'intercept': true,
        'status': 200,
        'headers': {'Content-Type': 'application/json'},
        'body': base64Encode(utf8.encode('{"ok":true}')),
      }));
      dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:${ghostBe.port}'));
    });
    tearDown(() => ghostBe.close(force: true));

    test('json', () async {
      final response = await dio.get<Map<String, dynamic>>(
        'http://example.invalid/dashboard',
        options: Options(responseType: ResponseType.json),
      );
      expect(response.data, {'ok': true});
    });

    test('plain', () async {
      final response = await dio.get<String>(
        'http://example.invalid/dashboard',
        options: Options(responseType: ResponseType.plain),
      );
      expect(response.data, '{"ok":true}');
    });

    test('stream', () async {
      final response = await dio.get<ResponseBody>(
        'http://example.invalid/dashboard',
        options: Options(responseType: ResponseType.stream),
      );
      final bytes = await response.data!.stream.expand((chunk) => chunk).toList();
      expect(utf8.decode(bytes), '{"ok":true}');
    });
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

  test('deep-link captured server overrides the interceptor\'s configured baseUrl', () async {
    final ghostBe = await _stub(jsonEncode({
      'intercept': true,
      'status': 201,
      'headers': {'Content-Type': 'application/json'},
      'body': base64Encode(utf8.encode('{"ok":true}')),
    }));
    addTearDown(() => ghostBe.close(force: true));

    // Constructed pointing at a dead port -- the deep link is what actually gets used.
    final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:1'));
    GhostBe.captureFromUri(Uri.parse('myapp://open?ghostBe=127.0.0.1:${ghostBe.port}'));

    final response = await dio.get<List<int>>(
      'http://example.invalid/v1/users/42',
      options: Options(responseType: ResponseType.bytes),
    );

    expect(response.statusCode, 201);
    expect(utf8.decode(response.data!), '{"ok":true}');
  });

  test('clearCapturedServer falls back to the interceptor\'s configured baseUrl', () async {
    final ghostBe = await _stub(jsonEncode({
      'intercept': true,
      'status': 201,
      'headers': {'Content-Type': 'application/json'},
      'body': base64Encode(utf8.encode('{"ok":true}')),
    }));
    addTearDown(() => ghostBe.close(force: true));

    GhostBe.captureFromUri(Uri.parse('myapp://open?ghostBe=127.0.0.1:1')); // dead port
    GhostBe.clearCapturedServer();

    final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:${ghostBe.port}'));
    final response = await dio.get<List<int>>(
      'http://example.invalid/v1/users/42',
      options: Options(responseType: ResponseType.bytes),
    );

    expect(response.statusCode, 201);
    expect(utf8.decode(response.data!), '{"ok":true}');
  });
}
