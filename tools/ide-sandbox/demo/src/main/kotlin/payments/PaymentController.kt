package payments

class PaymentController(private val billing: BillingService) {
    fun charge(request: ChargeRequest): Response {
        if (request.amount <= 0 || request.amount > ChargeRequest.MAX_AMOUNT) {
            return Response.badRequest("The amount is out of range.")
        }
        val result = billing.charge(request.card, request.amount)
        return if (result.ok) Response.ok(result.id) else Response.failed(result.error)
    }
}
