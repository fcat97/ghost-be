package ghostbe.server

private fun parseUrl(url: String): Pair<String, Map<String, String>> {
    val withoutScheme = url.substringAfter("://")
    val pathAndQuery = withoutScheme.substringAfter('/', missingDelimiterValue = "")
    val fullPath = "/" + pathAndQuery.substringBefore('?')
    val queryString = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    val query = if (queryString.isEmpty()) emptyMap() else queryString.split('&').associate {
        val parts = it.split('=', limit = 2)
        parts[0] to parts.getOrElse(1) { "" }
    }
    return fullPath to query
}

private fun pathMatchesPattern(pattern: String, actual: String): Boolean {
    val patternSegments = pattern.trim('/').split('/')
    val actualSegments = actual.trim('/').split('/')
    if (patternSegments.size != actualSegments.size) return false
    return patternSegments.zip(actualSegments).all { (p, a) ->
        (p.startsWith("{") && p.endsWith("}")) || p == a
    }
}

fun matchRule(envelope: RequestEnvelope, rules: List<Rule>): Rule? {
    val (path, query) = parseUrl(envelope.url)
    val lowerCaseHeaders = envelope.headers.mapKeys { it.key.lowercase() }

    return rules.firstOrNull { rule ->
        val match = rule.match
        if (!match.method.equals(envelope.method, ignoreCase = true)) return@firstOrNull false

        val pathOk = when {
            match.path != null -> match.path == path
            match.pathPattern != null -> pathMatchesPattern(match.pathPattern, path)
            else -> false
        }
        if (!pathOk) return@firstOrNull false

        val queryOk = match.query.all { (k, v) -> query[k] == v }
        if (!queryOk) return@firstOrNull false

        match.headers.all { (k, v) -> lowerCaseHeaders[k.lowercase()] == v }
    }
}
