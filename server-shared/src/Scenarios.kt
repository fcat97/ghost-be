package ghostbe.server

/**
 * Scenario selection, kept as a pure pre-filter over the rule list.
 *
 * A rule with no `scenario` is "baseline" and always a candidate. A rule tagged with a
 * scenario is inert until that scenario is active, and takes precedence over baseline
 * rules once it is.
 *
 * Precedence is expressed as a *reordering* rather than as a new matching rule, so
 * [matchRule] stays pure and untouched: putting the active-scenario rules first means
 * its existing first-match-wins behaviour produces "tagged beats baseline" for free.
 * That in turn keeps rule matching free of side effects, which matters because
 * `/api/test/state` (and any future "what would match this?" preview) has to ask which
 * rule matches *without* consuming a response-sequence slot.
 */

/** Every scenario name any rule declares: distinct, sorted, with untagged rules dropped. */
fun scenarioNames(rules: List<Rule>): List<String> =
    rules.mapNotNull { it.scenario }.distinct().sorted()

/**
 * The rules eligible to match, ordered so active-scenario rules are tried before
 * baseline ones. Rules tagged with an inactive scenario are dropped entirely.
 *
 * Within each group the caller's load order is preserved, so two simultaneously-active
 * scenarios tagging the same endpoint resolve by load order -- deterministic, and the
 * same tiebreak rules already use.
 */
fun activeRules(rules: List<Rule>, active: Set<String>): List<Rule> {
    val tagged = rules.filter { it.scenario != null && it.scenario in active }
    val baseline = rules.filter { it.scenario == null }
    return tagged + baseline
}
