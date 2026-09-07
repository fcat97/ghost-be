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
