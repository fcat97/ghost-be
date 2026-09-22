package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ScenariosTest {
    private fun rule(name: String, scenario: String? = null, enabled: Boolean = true, path: String = "/v1/checkout") =
        Rule(
            name = name,
            match = MatchSpec("POST", path = path),
            response = ResponseSpec(file = "r.json", status = 200),
            enabled = enabled,
            scenario = scenario
        )

    private val envelope = RequestEnvelope("POST", "https://api.example.com/v1/checkout", emptyMap(), null)

    @Test
    fun `an untagged rule is a candidate when no scenario is active`() {
        val rules = listOf(rule("baseline"))
        assertEquals(listOf("baseline"), activeRules(rules, emptySet()).map { it.name })
    }

    @Test
    fun `a tagged rule is not a candidate while its scenario is inactive`() {
        val rules = listOf(rule("declined", scenario = "checkout-fails"), rule("baseline"))
        assertEquals(listOf("baseline"), activeRules(rules, emptySet()).map { it.name })
    }

    @Test
    fun `a tagged rule beats an untagged one even when the untagged rule is declared first`() {
        // Load order must NOT decide here -- this is the whole point of the pre-filter,
        // and it is the property that silently regresses.
        val rules = listOf(rule("baseline"), rule("declined", scenario = "checkout-fails"))
        val candidates = activeRules(rules, setOf("checkout-fails"))
        assertEquals("declined", matchRule(envelope, candidates)?.name)
    }

    @Test
    fun `the baseline rule wins again once the scenario is deactivated`() {
        val rules = listOf(rule("baseline"), rule("declined", scenario = "checkout-fails"))
        assertEquals("declined", matchRule(envelope, activeRules(rules, setOf("checkout-fails")))?.name)
        assertEquals("baseline", matchRule(envelope, activeRules(rules, emptySet()))?.name)
    }

    @Test
    fun `two active scenarios on different endpoints both match`() {
        val rules = listOf(
            rule("declined", scenario = "checkout-fails", path = "/v1/checkout"),
            rule("empty", scenario = "empty-cart", path = "/v1/cart")
        )
        val candidates = activeRules(rules, setOf("checkout-fails", "empty-cart"))
        assertEquals("declined", matchRule(envelope, candidates)?.name)
        val cart = RequestEnvelope("POST", "https://api.example.com/v1/cart", emptyMap(), null)
        assertEquals("empty", matchRule(cart, candidates)?.name)
    }

    @Test
    fun `two active scenarios on the same endpoint resolve by load order deterministically`() {
        val a = rule("from-a", scenario = "a")
        val b = rule("from-b", scenario = "b")
        // Asserted both ways round so the tiebreak is proven to be load order
        // rather than set-iteration order, which is not stable.
        assertEquals("from-a", matchRule(envelope, activeRules(listOf(a, b), setOf("a", "b")))?.name)
        assertEquals("from-b", matchRule(envelope, activeRules(listOf(b, a), setOf("a", "b")))?.name)
    }

    @Test
    fun `a disabled rule stays inert even when its scenario is active`() {
        val rules = listOf(rule("declined", scenario = "checkout-fails", enabled = false), rule("baseline"))
        assertEquals("baseline", matchRule(envelope, activeRules(rules, setOf("checkout-fails")))?.name)
    }

    @Test
    fun `scenarioNames returns distinct sorted names and drops untagged rules`() {
        val rules = listOf(
            rule("a", scenario = "slow-network"),
            rule("b", scenario = "checkout-fails"),
            rule("c", scenario = "checkout-fails"),
            rule("d")
        )
        assertEquals(listOf("checkout-fails", "slow-network"), scenarioNames(rules))
    }
}
