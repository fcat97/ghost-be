package ghostbe.server

/**
 * Response sequences: a rule may answer differently on successive matching calls, which
 * is what makes a multi-step journey (poll until ready, fail then succeed, paginate)
 * expressible as rules rather than as test-script logic.
 *
 * Both functions here are pure. Choosing a variant needs only an already-computed call
 * index, so the counter that produces that index lives in `TestSession` and the
 * selection itself stays testable without any mutable state.
 */

/**
 * The ordered response variants a rule can serve: its `responses` list if it has one,
 * otherwise its single `response`.
 *
 * Empty only for a rule declaring neither, which [validateRules] rejects at load time.
 */
fun Rule.responseVariants(): List<ResponseSpec> =
    responses.ifEmpty { listOfNotNull(response) }

/**
 * The variant for the [hit]th (0-based) matching call. The final entry sticks for every
 * later call, so a sequence never runs out -- callers rarely know how many times an app
 * will retry or poll.
 */
fun pickVariant(variants: List<ResponseSpec>, hit: Int): ResponseSpec =
    variants[minOf(hit, variants.lastIndex)]
