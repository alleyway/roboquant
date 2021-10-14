package org.roboquant.brokers.sim

import org.roboquant.Phase
import org.roboquant.brokers.*
import org.roboquant.common.Cash
import org.roboquant.common.Currency
import org.roboquant.common.Logging
import org.roboquant.feeds.Event
import org.roboquant.metrics.MetricResults
import org.roboquant.orders.CancellationOrder
import org.roboquant.orders.Order
import org.roboquant.orders.OrderStatus
import org.roboquant.orders.SingleOrder
import java.lang.Double.min
import java.time.Instant
import java.util.logging.Logger

/**
 * Simulated Broker that is used during back testing. It simulates both broker behavior and the exchange
 * where the orders are executed. It supports both [SingleOrder] and [CancellationOrder] orders.
 *
 * It is also possible to use this SimBroker in combination with live feeds to see how your strategy is performing with
 * realtime data without the need for a real broker.
 *
 * @property initialDeposit Initial deposit to use before any trading starts. Default is the often used paper trading
 * setting of 1 million USD.
 * @constructor Create new Sim broker
 */
class SimBroker(
    private val initialDeposit: Cash = Cash(Currency.USD to 1_000_000.00),
    currencyConverter: CurrencyConverter? = null,
    baseCurrency: Currency = initialDeposit.currencies.first(),
    private val costModel: CostModel = DefaultCostModel(),
    private val validateBuyingPower: Boolean = false,
    private val recording: Boolean = false,
    private val prefix: String = "broker.",
    private val priceType: String = "OPEN"
) : Broker {

    private val metrics = mutableMapOf<String, Number>()
    override val account: Account = Account(baseCurrency, currencyConverter)

    companion object Factory {

        private val logger: Logger = Logging.getLogger("SimBroker")

        /**
         * Create a new SimBroker instance with the provided initial deposit of cash in the account
         *
         * @param amount
         * @param currencyCode
         * @return
         */
        fun withDeposit(amount: Double, currencyCode: String = "USD"): SimBroker {
            val currency = Currency.getInstance(currencyCode)
            return SimBroker(Cash(currency to amount))
        }

    }

    /**
     * Execute the accepted orders. If there is no price info available, the order will be skipped and tried again next
     * step.
     *
     * @param event
     */
    private fun execute(event: Event) {
        val prices = event.prices
        val now = event.now
        logger.finer { "Executing at $now with ${prices.size} prices" }

        for (order in account.orders.accepted) {
            val action = prices[order.asset] ?: continue
            val price = action.getPrice(priceType)

            val executions = order.execute(price, now)
            for (execution in executions) {
                record("order.${order.asset.symbol}", execution.quantity)

                val (cost, fee) = costModel.calculate(order, execution, action)
                val totalCost = cost + fee
                val avgCost = totalCost / execution.size()

                updateAccount(execution, order, avgCost, totalCost, fee, now)
            }

        }
    }


    /**
     * Update the account based on an execution. This will perform the following steps:
     *
     * 1. Update the cash position
     * 2. Update the portfolio position for the underlying asset
     * 3. Create and add a trade object to the account
     *
     */
    private fun updateAccount(
        execution: Execution,
        order: Order,
        avgCost: Double,
        totalCost: Double,
        fee: Double,
        now: Instant
    ) {
        val asset = execution.asset
        val position = Position(asset, execution.quantity, avgCost)
        account.cash.withdraw(asset.currency, totalCost)
        val pnl = account.portfolio.updatePosition(position)

        val newTrade = Trade(
            now,
            execution.asset,
            execution.quantity,
            execution.price,
            totalCost,
            fee,
            pnl,
            order.id
        )
        account.trades.add(newTrade)
    }


    /**
     * Place orders (created by the policy) at this broker.
     *
     * @param orders The new orders
     * @param event
     *
     */
    override fun place(orders: List<Order>, event: Event): Account {
        logger.finer { "Received ${orders.size} orders at ${event.now}" }
        account.orders.addAll(orders)
        validateOrders(event)
        execute(event)
        account.portfolio.updateMarketPrices(event)
        account.time = event.now
        return account
    }



    /**
     * Validate if there is enough buying power to process the orders that are just received. If there is not enough
     * cash, the order will be rejected. If there is no price info to determine the required amount of cash, the order
     * will not yet be accepted.
     **/
    private fun validateOrders(event: Event) {
        var buyingPower = account.buyingPower
        val initialOrders = account.orders.filter { it.status === OrderStatus.INITIAL }
        for (order in initialOrders) {
            if (! validateBuyingPower) {
                order.status = OrderStatus.ACCEPTED
            } else {
                val price = event.prices[order.asset]?.getPrice()
                if (price != null) {
                    val expectedCost = min(0.0, order.getValue(price))
                    if (buyingPower > expectedCost) {
                        buyingPower -= expectedCost
                        order.status = OrderStatus.ACCEPTED
                    } else {
                        logger.fine { "Not enough buying power $buyingPower, required $expectedCost, rejecting order $order" }
                        order.status = OrderStatus.REJECTED
                    }
                } else {
                    logger.fine { "No price found for order $order, keeping state to INITIAL" }
                }
            }
        }

    }


    /**
     * At the start of a new phase the account and metrics will be reset
     *
     * @param phase
     */
    override fun start(phase: Phase) {
        reset()
    }


    override fun reset() {
        account.reset()
        account.cash.deposit(initialDeposit)
        metrics.clear()
    }

    /**
     * Record a metric
     *
     * @param key
     * @param value
     */
    private fun record(key: String, value: Number) {
        !recording && return
        metrics["$prefix$key"] = value
    }

    /**
     * Get metrics generated by the simulated broker
     *
     * @return
     */
    override fun getMetrics(): MetricResults {
        val result = metrics.toMap()
        metrics.clear()
        return result
    }

}

