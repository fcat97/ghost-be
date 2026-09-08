/// Lets a QA tester point [GhostBeInterceptor] at a specific ghost-be instance
/// by tapping a deep link/app link -- no adb, no LAN discovery, no code change.
///
/// Hook [captureFromUri] into whichever deep-link package the app already uses
/// (e.g. `app_links`, `uni_links`) at startup and on every incoming link:
///
/// ```dart
/// final appLinks = AppLinks();
/// appLinks.uriLinkStream.listen(GhostBe.captureFromUri);
/// final initialUri = await appLinks.getInitialAppLink();
/// if (initialUri != null) GhostBe.captureFromUri(initialUri);
/// ```
///
/// A link like `myapp://open?ghostBe=192.168.1.5:44678` then overrides every
/// [GhostBeInterceptor]'s configured `baseUrl` for the rest of this process's
/// life -- there's nothing to persist, since the app being killed is exactly
/// when a QA session naturally ends too.
class GhostBe {
  GhostBe._();

  static String? _overrideBaseUrl;

  static void captureFromUri(Uri uri) {
    final value = uri.queryParameters['ghostBe'];
    if (value != null && value.isNotEmpty) {
      _overrideBaseUrl = 'http://$value';
    }
  }

  /// Falls back to whatever `baseUrl` each [GhostBeInterceptor] was constructed with.
  static void clearCapturedServer() {
    _overrideBaseUrl = null;
  }

  static String? get overrideBaseUrl => _overrideBaseUrl;
}
