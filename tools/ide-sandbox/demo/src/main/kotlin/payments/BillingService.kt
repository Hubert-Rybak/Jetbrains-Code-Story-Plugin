package payments

class BillingService(private val provider: PaymentProvider, private val ledger: Ledger) {
    fun charge(card: Card, amount: Long): ChargeResult {
        val result = provider.charge(card, amount)
        ledger.record(card, amount, result)
        return result
    }
}
