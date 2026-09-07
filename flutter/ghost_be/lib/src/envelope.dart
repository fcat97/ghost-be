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
