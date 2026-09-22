package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseSequenceTest {
    private fun spec(status: Int) = ResponseSpec(file = "r$status.json", status = status)

    private fun rule(response: ResponseSpec? = null, responses: List<ResponseSpec> = emptyList()) =
        Rule(
            name = "r",
            match = MatchSpec("GET", path = "/v1/x"),
            response = response,
            responses = responses
        )

    @Test
    fun `a rule with a single response has that one variant`() {
        assertEquals(listOf(spec(200)), rule(response = spec(200)).responseVariants())
    }

    @Test
    fun `a rule with a responses list has those variants in order`() {
        val seq = listOf(spec(500), spec(503), spec(200))
        assertEquals(seq, rule(responses = seq).responseVariants())
    }

    @Test
    fun `variants are consumed in order and the last one sticks`() {
        val variants = listOf(spec(500), spec(503), spec(200))
        val seen = (0..4).map { pickVariant(variants, it).status }
        assertEquals(listOf(500, 503, 200, 200, 200), seen)
    }

    @Test
    fun `a single-entry sequence behaves like a single response`() {
        val variants = listOf(spec(418))
        assertEquals(listOf(418, 418, 418), (0..2).map { pickVariant(variants, it).status })
    }
}
