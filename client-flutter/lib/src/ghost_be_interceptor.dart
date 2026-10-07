import 'dart:convert';
import 'package:dio/dio.dart';
import 'envelope.dart';
import 'ghost_be.dart';

class GhostBeInterceptor extends Interceptor {
  final String baseUrl;
  final Dio _relayClient = Dio();

  /// Decodes mocked bodies into whatever the request's [ResponseType] expects.
  ///
  /// Resolving from `onRequest` skips Dio's own transform step, so the
  /// interceptor runs it itself. Pass the app's `dio.transformer` if it
  /// customizes JSON decoding; the default matches Dio's own default.
  final Transformer transformer;

  GhostBeInterceptor({this.baseUrl = 'http://127.0.0.1:44678', Transformer? transformer})
      : transformer = transformer ?? BackgroundTransformer();

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final bodyBytes = _extractBodyBytes(options.data);
    final envelope = RequestEnvelope(
      method: options.method,
      url: options.uri.toString(),
      headers: options.headers.map((key, value) => MapEntry(key, value.toString())),
      body: bodyBytes != null ? base64Encode(bodyBytes) : null,
    );

    // Re-read the override on every request rather than caching it at construction --
    // a deep link can arrive any time after this interceptor is already built.
    final effectiveBaseUrl = GhostBe.overrideBaseUrl ?? baseUrl;

    final ResponseEnvelope decision;
    try {
      final relayResponse = await _relayClient.post<Map<String, dynamic>>(
        '${effectiveBaseUrl.replaceAll(RegExp(r"/+$"), "")}/intercept',
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
        final headers = mockHeaders.map((k, v) => MapEntry(k.toLowerCase(), [v]));
        final dynamic data;
        try {
          data = await transformer.transformResponse(
            options,
            ResponseBody(
              Stream.value(base64Decode(body)),
              status,
              headers: headers,
            ),
          );
        } catch (e, st) {
          handler.reject(
            DioException(requestOptions: options, error: e, stackTrace: st),
            true,
          );
          return;
        }
        handler.resolve(
          Response(
            requestOptions: options,
            statusCode: status,
            headers: Headers.fromMap(headers),
            data: data,
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
