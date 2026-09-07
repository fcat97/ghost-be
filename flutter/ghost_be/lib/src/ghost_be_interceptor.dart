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
