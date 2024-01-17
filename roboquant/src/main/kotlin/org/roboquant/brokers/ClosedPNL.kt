package org.roboquant.brokers

import org.roboquant.common.*
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ClosedPNL is created once an trade has been (partially) closed and records various aspects of a trade like [size],
 * [entryPrice] and [exitPrice]. A single order can result in multiple trades,ex. if the order is filled in batches.
 *
 * All the monetary amounts are denoted in the currency of the underlying asset. One important metric that can be
 * derived from trades is the realized profit and loss [pnl].
 *
 * @property time The time of this trade
 * @property asset The underlying asset of this trade
 * @property size The size or volume of this trade, negative for selling assets
 * @property entryPrice The average price paid denoted in the currency of the asset excluding fee
 * @property exitPrice The average price sold denoted in the currency of the asset excluding fee
 * @property pnlValue The realized profit & loss made by this trade denoted in the currency of the asset
 * @property orderId The id of the corresponding order
 * @constructor Create a new closedPNL
 */
data class ClosedPNL(
    val time: Instant,
    val asset: Asset,
    val size: Size,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnlValue: Double,
    val orderId: Int,
) {
    /**
     * Returns the realized profit & loss amount of this trade
     */
    val pnl: Amount
        get() = Amount(asset.currency, pnlValue)

}

/**
 * Get the timeline for a collection of trades
 */
val Collection<ClosedPNL>.timeline
    get() = map { it.time }.distinct().sorted()

/**
 * Return the total realized PNL for a collection of trades
 */
val Collection<ClosedPNL>.realizedPNL: Wallet
    get() = sumOf { it.pnl }

/**
 * Return the timeframe for a collection of trades
 */
val Collection<ClosedPNL>.timeframe: Timeframe
    get() = timeline.timeframe

/**
 * Return the collection as table
 */
fun Collection<ClosedPNL>.lines(): List<List<Any>> {
    val lines = mutableListOf<List<Any>>()
    lines.add(listOf("symbol", "time", "ccy", "size", "rlzd p&l", "entryPrice", "exitPrice"))
    forEach {
        with(it) {
            val currency = asset.currency
            val pnl = pnl.formatValue()
            val entryPrice = Amount(currency, entryPrice).formatValue()
            val exitPrice = Amount(currency, exitPrice).formatValue()
            val t = time.truncatedTo(ChronoUnit.SECONDS)
            lines.add(listOf(asset.symbol, t, currency.currencyCode, size, pnl, entryPrice, exitPrice))
        }
    }
    return lines
}

/**
 * Create a summary of the closedPNL
 */
@JvmName("summaryClosedPNLs")
fun Collection<ClosedPNL>.summary(name: String = "closedPNLs"): Summary {
    val s = Summary(name)
    if (isEmpty()) {
        s.add("EMPTY")
    } else {
        val lines = this.lines()
        return lines.summary(name)
    }
    return s
}

