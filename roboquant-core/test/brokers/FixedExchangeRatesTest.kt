package org.roboquant.brokers


import org.junit.Test
import org.roboquant.Roboquant
import org.roboquant.TestData
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.common.Asset
import org.roboquant.common.Currency.Companion.EUR
import org.roboquant.common.Currency.Companion.USD
import org.roboquant.feeds.csv.CSVConfig
import org.roboquant.feeds.csv.CSVFeed
import org.roboquant.logging.SilentLogger
import org.roboquant.metrics.AccountSummary
import org.roboquant.policies.TestPolicy
import org.roboquant.strategies.EMACrossover
import kotlin.test.assertEquals


internal class FixedExchangeRatesTest {

    @Test
    fun multiCurrency() {
        val feed = CSVFeed(TestData.dataDir() + "US")
        val asset = Asset("TEMPLATE", currencyCode = "EUR")
        val config = CSVConfig(template = asset)
        val feed2 = CSVFeed(TestData.dataDir() +"EU", config)
        feed.merge(feed2)

        val currencyConverter = FixedExchangeRates(USD, EUR to 1.2)
        assertEquals(USD, currencyConverter.baseCurrency)

        val broker = SimBroker(currencyConverter = currencyConverter)

        val strategy = EMACrossover()
        val policy = TestPolicy()
        val roboquant = Roboquant(
            strategy,
            AccountSummary(),
            policy = policy,
            broker = broker,
            logger = SilentLogger()
        )
        roboquant.run(feed)

        assertEquals(2, broker.account.cash.currencies.size)
    }


}