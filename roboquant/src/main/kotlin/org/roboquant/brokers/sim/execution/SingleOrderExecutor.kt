/*
 * Copyright 2020-2024 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.roboquant.brokers.sim.execution

import org.roboquant.brokers.sim.Pricing
import org.roboquant.common.Logging
import org.roboquant.common.Size
import org.roboquant.common.UnsupportedException
import org.roboquant.common.days
import org.roboquant.common.plus
import org.roboquant.orders.*
import java.time.Instant

/**
 * Base class for executing single orders. It takes care of Time-In-Force handling and status management and makes
 * the implementation of concrete single order executors like a MarketOrder easier.
 *
 * @property order the single order to execute
 */
internal abstract class SingleOrderExecutor<T : SingleOrder>(final override var order: T) : OrderExecutor<T> {

    val logger = Logging.getLogger(SingleOrderExecutor::class)

    /**
     * Fill so far
     */
    var fill = Size.ZERO
        private set

    /**
     * Remaining order size
     */
    private val remaining
        get() = order.size - fill

    // Used by time-in-force policies
    private lateinit var openedAt: Instant

    /**
     * Order status
     */
    override var status: OrderStatus = OrderStatus.INITIAL

    /**
     * Cancel the order, return true if successful, false otherwise. Any open SingleOrder can be cancelled, closed
     * orders not.
     */
    internal fun cancel(time: Instant): Boolean {
        if (status == OrderStatus.ACCEPTED && expired(time)) status = OrderStatus.EXPIRED
        if (status.closed) return false
        status = OrderStatus.CANCELLED
        return true
    }

    /**
     * Validate TiF policy and return true if the order has expired according to the defined policy.
     */
    private fun expired(time: Instant): Boolean {
        return when (val tif = order.tif) {
            is GTC -> time > (openedAt + tif.maxDays.days)
            is DAY -> !order.asset.exchange.sameDay(openedAt, time)
            is FOK -> remaining.nonzero
            is GTD -> time > tif.date
            is IOC -> time > openedAt
            is PO -> {
                val ret =(time == openedAt) && remaining.iszero // Added line
                if (ret) {
                    logger.debug { "Enforcing Post-Only for $order" }
                }
                ret
            }
            else -> throw UnsupportedException("unsupported time-in-force policy tif=$tif")
        }
    }

    /**
     * Execute the order, using the provided [pricing] and [time] as input.
     */
    override fun execute(pricing: Pricing, time: Instant): List<Execution> {
        if (status == OrderStatus.INITIAL) {
            status = OrderStatus.ACCEPTED
            openedAt = time
        }

        val execution = fill(remaining, pricing)
        fill += execution?.size ?: Size.ZERO

        if (expired(time)) {
            status = OrderStatus.EXPIRED
            return emptyList()
        }

        if (remaining.iszero) status = OrderStatus.COMPLETED
        return if (execution == null) emptyList() else listOf(execution)
    }

    @Suppress("ReturnCount")
    fun update(order: CreateOrder, time: Instant, pricing: Pricing): Boolean {
        // "order" ^^ is actually the UpdateOrder.updateOrder
        // returning true means success

        if (status == OrderStatus.ACCEPTED && expired(time)) return false
        if (status.closed) return false

        @Suppress("UNCHECKED_CAST")
        val newOrder = order as? T
        return if (newOrder != null) {
            if (newOrder.size != order.size) return false
            this.order = newOrder
            when (newOrder) {
                is LimitOrder -> {
                    if (limitTrigger(newOrder.limit, newOrder.size, pricing)) {
                        logger.debug { "Rejecting LimitOrder that would trigger immediately: $newOrder" }
                        false
                    } else {
                        true
                    }
                }
                else -> {
                    logger.warn { "Updating order that is NOT LimitOrder: $newOrder" }
                    true
                }
            }
        } else {
            false
        }

    }

    override fun modify(modifyOrder: ModifyOrder, time: Instant, pricing: Pricing?): Boolean {
        return when (modifyOrder) {
            is CancelOrder -> cancel(time)
            is UpdateOrder -> update(modifyOrder.update, time, pricing!!)
            else -> false
        }
    }

    /**
     * Subclasses only need to implement this method and don't need to worry about time-in-force and state management.
     */
    abstract fun fill(remaining: Size, pricing: Pricing): Execution?
}


internal class MarketOrderExecutor(order: MarketOrder) : SingleOrderExecutor<MarketOrder>(order) {

    /**
     * Market orders will always fill 100% against the [pricing] provided. It uses [Pricing.marketPrice] to
     * get the actual price.
     */

    override fun fill(remaining: Size, pricing: Pricing): Execution {

        val marketExecPrice = if (remaining.isNegative)
            pricing.lowPrice(remaining)
        else
            pricing.highPrice(remaining)

        val type = if (remaining.isNegative) {
            "Sell"
        } else {
            "Buy"
        }
        logger.info("Executed Market ${type} order | qty: ${remaining} price: ${marketExecPrice}")
        return Execution(order, remaining, marketExecPrice)
    }

}

private fun stopTrigger(stop: Double, size: Size, pricing: Pricing): Boolean {
    return if (size.isNegative) pricing.lowPrice(size) <= stop
    else pricing.highPrice(size) >= stop
}

private fun limitTrigger(limit: Double, size: Size, pricing: Pricing): Boolean {
    return if (size.isNegative) pricing.highPrice(size) >= limit
    else pricing.lowPrice(size) <= limit
}

private fun getTrailStop(oldStop: Double, trail: Double, size: Size, pricing: Pricing): Double {

    return if (size.isNegative) {
        // Sell stop
        val price = pricing.highPrice(size)
        val newStop = price * (1.0 - trail)
        if (oldStop.isNaN() || newStop > oldStop) newStop else oldStop
    } else {
        // Buy stop
        val price = pricing.lowPrice(size)
        val newStop = price * (1.0 + trail)
        if (oldStop.isNaN() || newStop < oldStop) newStop else oldStop
    }

}

internal class LimitOrderExecutor(order: LimitOrder) : SingleOrderExecutor<LimitOrder>(order) {

    // how can we simulate that here?

    override fun fill(remaining: Size, pricing: Pricing): Execution? {
        return if (limitTrigger(order.limit, remaining, pricing)) {

            logger.debug("order.limit: ${order.limit}, "
                + "highPrice: ${pricing.highPrice(remaining)}, "
                + "lowPrice: ${pricing.lowPrice(remaining)}")
//            val limitExecPrice = if (remaining.isNegative)
//                pricing.lowPrice(remaining)
//            else
//                pricing.highPrice(remaining)
            val type = if (remaining.isNegative) {
                "Sell"
            } else {
                "Buy"
            }
            print("Limit Execution!\u0007")
            logger.debug("Executing Limit ${type} order | qty: ${remaining} price: ${order.limit}")
            Execution(order, remaining, order.limit)
        } else {
            null
        }
    }

}

internal class StopOrderExecutor(order: StopOrder) : SingleOrderExecutor<StopOrder>(order) {

    /**
     * Stop orders will fill only if the configured stop price is triggered.
     */
    override fun fill(remaining: Size, pricing: Pricing): Execution? {
        return if (stopTrigger(order.stop, remaining, pricing))
            Execution(order, remaining, pricing.marketPrice(remaining))
        else
            null
    }

}

internal class StopLimitOrderExecutor(order: StopLimitOrder) : SingleOrderExecutor<StopLimitOrder>(order) {

    private var stopTriggered = false

    override fun fill(remaining: Size, pricing: Pricing): Execution? {
        if (!stopTriggered) stopTriggered = stopTrigger(order.stop, remaining, pricing)
        if (stopTriggered && limitTrigger(order.limit, remaining, pricing))
            return Execution(order, remaining, order.limit)

        return null
    }

}

internal class TrailOrderExecutor(order: TrailOrder) : SingleOrderExecutor<TrailOrder>(order) {

    private var stop = Double.NaN

    override fun fill(remaining: Size, pricing: Pricing): Execution? {
        stop = getTrailStop(stop, order.trailPercentage, remaining, pricing)
        return if (stopTrigger(stop, remaining, pricing))
            Execution(
                order, remaining, pricing.marketPrice(remaining)
            ) else
            null
    }

}

internal class TrailLimitOrderExecutor(order: TrailLimitOrder) : SingleOrderExecutor<TrailLimitOrder>(order) {

    private var stop: Double = Double.NaN
    private var stopTriggered = false

    override fun fill(remaining: Size, pricing: Pricing): Execution? {
        stop = getTrailStop(stop, order.trailPercentage, remaining, pricing)
        if (!stopTriggered) stopTriggered = stopTrigger(stop, remaining, pricing)
        val limit = stop + order.limitOffset
        return if (stopTriggered && limitTrigger(limit, remaining, pricing))
            Execution(order, remaining, limit)
        else
            null
    }

}

