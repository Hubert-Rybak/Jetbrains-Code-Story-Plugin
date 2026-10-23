package payments

data class Card(val number: String)
data class ChargeResult(val ok: Boolean, val id: String?, val error: String?)

interface PaymentProvider {
    fun charge(card: Card, amount: Long): ChargeResult
}

interface Ledger {
    fun record(card: Card, amount: Long, result: ChargeResult)
}

class Response private constructor(val status: Int, val body: String?) {
    companion object {
        fun ok(id: String?) = Response(200, id)
        fun badRequest(message: String) = Response(400, message)
        fun failed(error: String?) = Response(502, error)
    }
}
