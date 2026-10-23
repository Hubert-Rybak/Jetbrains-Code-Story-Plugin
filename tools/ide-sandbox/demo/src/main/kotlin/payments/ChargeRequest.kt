package payments

data class ChargeRequest(val card: Card, val amount: Long) {
    companion object {
        /** The largest amount in cents that one charge can take. */
        const val MAX_AMOUNT = 1_000_000L
    }
}
