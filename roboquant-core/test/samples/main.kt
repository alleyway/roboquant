package org.roboquant.samples

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import org.roboquant.Roboquant
import org.roboquant.brokers.Account
import org.roboquant.brokers.ECBExchangeRates
import org.roboquant.brokers.FixedExchangeRates
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.common.*
import org.roboquant.feeds.avro.AvroFeed
import org.roboquant.feeds.avro.AvroGenerator
import org.roboquant.feeds.csv.CSVConfig
import org.roboquant.feeds.csv.CSVFeed
import org.roboquant.feeds.csv.LazyCSVFeed
import org.roboquant.feeds.random.RandomWalk
import org.roboquant.logging.MemoryLogger
import org.roboquant.logging.SilentLogger
import org.roboquant.metrics.AccountSummary
import org.roboquant.metrics.PNL
import org.roboquant.metrics.ProgressMetric
import org.roboquant.policies.BettingAgainstBeta
import org.roboquant.policies.NeverShortPolicy
import org.roboquant.policies.TestPolicy
import org.roboquant.strategies.*
import java.nio.file.Files
import java.time.Instant
import java.time.Period
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.system.measureTimeMillis


fun small() {
    val feed = CSVFeed("data/US")
    val strategy = EMACrossover.longTerm()
    val roboquant = Roboquant(strategy, AccountSummary())
    roboquant.run(feed)
    roboquant.logger.summary().log()
    roboquant.broker.account.trades.summary().log()
    roboquant.broker.account.orders.summary().log()
}

fun trendFollowing() {
    val period = 200
    val strategy = TAStrategy(period)

    strategy.buy {
        ta.maxIndex(it.high, period) + 1 == period
    }

    strategy.sell {
        ta.minIndex(it.low, period) + 1 == period
    }

    val feed = AvroFeed.sp500()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), logger = logger)
    roboquant.run(feed)
    logger.summary().log()
    roboquant.broker.account.summary().log()

}


/**
 * Runs over 5000 stocks since 1962. Should runs in less than 1 GB JVM heap size
 *
 */
fun largeLowMem() {
    val feed = LazyCSVFeed("/data/assets/stock-market/stocks/")
    val strategy = EMACrossover.longTerm()
    val logger = MemoryLogger(showProgress = false)
    val roboquant = Roboquant(strategy, ProgressMetric(), logger = logger)
    roboquant.run(feed)
    logger.summary(3).print()
}


fun large1() {
    val feed = CSVFeed("/data/assets/stock-market/stocks/")
    val strategy = EMACrossover.longTerm()
    val roboquant = Roboquant(strategy, AccountSummary(), PNL())
    feed.split(Period.ofYears(10)).forEach {
        roboquant.run(feed, it)
    }
}


fun large2() {
    val feed = CSVFeed("/data/assets/stock-market/stocks/")
    val strategy = EMACrossover.longTerm()
    val logger = MemoryLogger(showProgress = false)
    val roboquant = Roboquant(strategy, ProgressMetric(), logger = logger)
    roboquant.run(feed)
    logger.summary().log()
    roboquant.broker.account.summary().log()
}

fun large3() {
    val strategy = EMACrossover.longTerm()
    val roboquant = Roboquant(strategy, AccountSummary())
    val feed = CSVFeed("/data/assets/stock-market/stocks/")
    feed.split(Period.ofYears(5)).map { it.splitTrainTest(0.2) }.forEach {
        roboquant.run(feed, it.first, it.second)
    }
}


fun large4() {
    val config = CSVConfig(fileExtension = ".us.txt")
    val feed = CSVFeed("/data/assets/us-stocks/Stocks", config)
    val strategy = EMACrossover.longTerm()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, AccountSummary(), logger = logger)
    roboquant.run(feed)
    logger.summary().log()
    roboquant.broker.account.trades.summary().log()
}

fun large5() {
    val feed = AvroFeed.sp500()
    val strategy = EMACrossover.longTerm()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), AccountSummary(), logger = logger)
    val t = measureTimeMillis {
        roboquant.run(feed)
        logger.summary()
        roboquant.broker.account.summary()
        println(roboquant.broker.account.orders.size)
    }
    println(t)
}




fun large6() {

    fun getConfig(exchange: String): CSVConfig {
        Exchange.getInstance(exchange)
        return CSVConfig(
            fileExtension = ".us.txt",
            assetExchange = exchange,
            parsePattern = "??T?OHLCV?",
            template = Asset("TEMPLATE", exchangeCode = exchange)
        )
    }

    val config1 = getConfig("NASDAQ")
    val config2 = getConfig("NYSE")
    val path = Path("/data/assets/stooq/data/daily/us/")
    var feed: CSVFeed? = null

    for (d in Files.list(path)) {
        if (d.name.startsWith("nasdaq")) {
            val tmp = CSVFeed(d.toString(), config1)
            if (feed === null) feed = tmp else feed.merge(tmp)
        }
    }

    for (d in Files.list(path)) {
        if (d.name.startsWith("nyse")) {
            val tmp = CSVFeed(d.toString(), config2)
            if (feed === null) feed = tmp else feed.merge(tmp)
        }
    }

    AvroGenerator.capture(feed!!, "/tmp/us_2000_2020.avro", TimeFrame.fromYears(2000, 2020), 6)

}


fun intraday() {
    val path = "data/INTRA"
    // "/data/assets/INTRADAY/"
    val feed = CSVFeed(path)
    if (feed.assets.isNotEmpty()) {
        val strategy = EMACrossover.longTerm()
        val roboquant = Roboquant(strategy, AccountSummary())
        roboquant.run(feed)
        println(roboquant.broker.account.summary())
    }
}

fun oneMillionBars() {
    val timeline = mutableListOf<Instant>()
    var start = Instant.parse("2000-01-01T09:00:00Z")
    repeat(1_000_000) {
        timeline.add(start)
        start = start.plusSeconds(60)
    }
    val feed = RandomWalk(timeline, 1, generateBars = true)
    val strategy = EMACrossover.longTerm()
    val policy = TestPolicy()

    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), AccountSummary(), policy = policy, logger = logger)
    roboquant.run(feed)
    logger.summary().print()
}

/*
fun crypto() {
    val config = CSVConfig(
        assetBuilder = {
            val currencyCode = it.substring(it.lastIndex - 2)
            val symbol = it.substring(0, it.lastIndex - 2)
            Asset(symbol, AssetType.CRYPTO, currencyCode)
        },
    )

    val feed = CSVFeed("/data/assets/crypto_small", config)

    val strategy = EMACrossover()
    val exp = Roboquant(strategy, ProgressMetric(), AccountSummary(), logger = MemoryLogger(maxHistorySize = 100))
    exp.run(feed)
    exp.broker.account.summary().log()
    exp.logger.summary().log()
}
*/

fun multiCurrency() {
    val feed = CSVFeed("data/US", CSVConfig(priceAdjust = true))
    val template = Asset("TEMPLATE", currencyCode = "EUR")
    val feed2 = CSVFeed("data/EU",  CSVConfig(priceAdjust = true, template = template))
    feed.merge(feed2)

    val euro = Currency.getInstance("EUR")
    val usd = Currency.getInstance("USD")
    val currencyConverter = FixedExchangeRates(usd, euro to 1.2)

    val cash = Cash(usd to 100_000.00)
    val account = Account(usd, currencyConverter)
    val broker = SimBroker(cash, account)

    val strategy = EMACrossover.midTerm()
    val policy = NeverShortPolicy(minAmount = 1_000.0, maxAmount = 15_000.0)

    val roboquant = Roboquant(strategy, AccountSummary(), policy = policy, broker = broker, logger = MemoryLogger())
    roboquant.run(feed)
    broker.account.summary().print()
}

fun manyMinutes() {
    val strategy = EMACrossover.longTerm()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), logger = logger)

    val tf = TimeFrame.fromYears(2019, 2019)
    val timeline = tf.toMinutes(excludeWeekends = true)
    val feed = RandomWalk(timeline, 10, generateBars = true)

    roboquant.run(feed)
    logger.summary().print()
}

fun minimal() {
    val roboquant = Roboquant(EMACrossover())
    val feed = CSVFeed("data/US")
    roboquant.run(feed)
}


fun determine() {
    val strategy = TestStrategy(100)
    val roboquant = Roboquant(strategy, AccountSummary(), logger = SilentLogger())
    val feed = CSVFeed("data/US")
    roboquant.run(feed)
}


fun testingStrategies() {
    val strategy = EMACrossover()
    val roboquant = Roboquant(strategy)
    val feed = CSVFeed("data/US")

    // Basic use case
    roboquant.run(feed)

    // Walk forward
    feed.split(Period.ofYears(2)).forEach {
        roboquant.run(feed, it)
    }

    // Walk forward learning
    feed.split(Period.ofYears(2)).map { it.splitTrainTest(0.2) }.forEach { (train, test) ->
        roboquant.run(feed, train, test, 100)
    }

}


fun ecbRates() {
    val rates = ECBExchangeRates.fromWeb()
    println(rates.currencies)
}


suspend fun runParallel() {
    val feed = RandomWalk.lastDays(100, 10, false)
    val deferredList = mutableListOf<Deferred<MemoryLogger>>()
    for (i in 10..15) {
        for (j in i + 1..i + 5) {
            val s = EMACrossover(i, j)
            val logger = MemoryLogger(false)
            val e = Roboquant(s, AccountSummary(), logger = logger, name = "Run $i $j")
            val deferred = Background.async {
                e.runAsync(feed)
                logger
            }

            deferredList.add(deferred)
        }
    }

    val loggers = deferredList.awaitAll()
    val l = Logging.getLogger("ParallelRuns")
    loggers.forEach {
        val entry = it.getMetric("account.value").last()
        l.info { "${entry.info.name}  ${entry.value}" }
    }

}

fun ta() {
    val shortTerm = 30
    val longTerm = 50
    val strategy = TAStrategy(longTerm)

    strategy.buy { price ->
        val emaShort = ta.ema(price.close, shortTerm)
        emaShort > ta.ema(price.close, longTerm) && ta.cdlMorningStar(price)
    }

    strategy.sell { price ->
        ta.cdl3BlackCrows(price) || (ta.cdl2Crows(price) && ta.ema(price.close, shortTerm) < ta.ema(price.close, longTerm))
    }

    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, AccountSummary(), logger = logger)
    val feed = CSVFeed("data/US")
    roboquant.run(feed)
    logger.summary(10).print()
}


fun taLarge() {
    val shortTerm = 30
    val longTerm = 50
    val strategy = TAStrategy(longTerm)

    strategy.buy { price ->
        with (ta) {
            cdlMorningStar(price) || cdl3Inside(price) || cdl3Outside(price) || cdl3LineStrike(price) ||
                    cdlHammer(price) || cdlPiercing(price) || cdlSpinningTop(price) || cdlRiseFall3Methods(price) ||
                    cdl3StarsInSouth(price) || cdlOnNeck(price)
        }
    }

    strategy.sell { price ->
        ta.cdl3BlackCrows(price) || (ta.cdl2Crows(price) && ta.ema(price.close, shortTerm) < ta.ema(price.close, longTerm))
    }

    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, AccountSummary(), logger = logger)
    val feed = CSVFeed("data/US")
    roboquant.run(feed)
    logger.summary(10).print()
}

fun avro() {
    val feed = AvroFeed("/data/assets/avro/universe.avro")
    val strategy = EMACrossover()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), logger = logger)
    roboquant.run(feed, TimeFrame.fromYears(1960, 2021))
    logger.summary().print()
}


fun avroGen() {
    // val feed = CSVFeed("/data/assets/stock-market/stocks/")
    val feed =  CSVFeed("/data/assets/individual_stocks_5yr", CSVConfig("_data.csv"))
    val t = measureTimeMillis {
        val file ="/tmp/5yr_sp500.avro"
        AvroGenerator.capture(feed, file)
    }
    println(t)
}

fun trendFollowing2() {
    val feed = AvroFeed.sp500()
    val strategy = TAStrategy(200)

    strategy.buy {
        ta.recordHigh(it.high, 200) ||   ta.recordHigh(it.high, 50)
    }

    strategy.sell {
        ta.recordLow(it.low, 25)
    }

    val roboquant = Roboquant(strategy, ProgressMetric())
    roboquant.run(feed)
    roboquant.broker.account.summary().log()
    roboquant.broker.account.trades.summary().log()
}


fun avroCapture() {
    val feed = CSVFeed("/data/assets/individual_stocks_5yr", CSVConfig("_data.csv"))
    AvroGenerator.capture(feed, "/tmp/5yr_sp500.avro")

    val feed2 = AvroFeed("/tmp/5yr_sp500.avro", useIndex = true)
    val strategy = EMACrossover()

    val roboquant = Roboquant(strategy, ProgressMetric())
    roboquant.run(feed2)
    roboquant.broker.account.summary().log()
    roboquant.broker.account.trades.summary().log()

}


fun beta() {
    val feed = CSVFeed("/data/assets/stock-market/stocks/")
    val market = CSVFeed("/data/assets/stock-market/market/")
    feed.merge(market)
    val strategy = NoSignalStrategy()
    val marketAsset = feed.find("SPY")

    val policy = BettingAgainstBeta(feed.assets, marketAsset, maxAssetsInPortfolio = 10)
    policy.recording = true
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), policy = policy, logger = logger)
    roboquant.run(feed)
    logger.summary().print()
    roboquant.broker.account.summary().print()

}


fun beta2() {
    val feed = CSVFeed("/data/assets/us-stocks/Stocks", CSVConfig(".us.txt"))
    val market = CSVFeed("/data/assets/us-stocks/ETFs", CSVConfig(".us.txt", "spy.us.txt"))
    feed.merge(market)
    val strategy = NoSignalStrategy()
    val marketAsset = feed.find("SPY")
    val policy = BettingAgainstBeta(feed.assets, marketAsset, 60, maxAssetsInPortfolio = 10)
    policy.recording = true
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), PNL(cumulative = true), policy = policy, logger = logger)
    roboquant.run(feed)
    logger.summary().print()
    println(roboquant.broker.account.summary())
    println(roboquant.broker.account.trades.totalFee())

}

suspend fun runMany() {
    large1()
    large2()
    large3()
    intraday()
    oneMillionBars()
    multiCurrency()
    ecbRates()
    manyMinutes()
    taLarge()
    runParallel()
}

suspend fun main() {
    // Logging.setDefaultLevel(Level.FINE)
    Config.info()

    when ("MIN") {
        // "CRYPTO" -> crypto()
        "SMALL" -> small()
        "BETA" -> beta()
        "BETA2" -> beta2()
        "LARGE" -> large1()
        "LARGE2" -> repeat(1) { large2() }
        "LARGE3" -> large3()
        "LARGE4" -> large4()
        "LARGE5" -> large5()
        "LARGE6" -> large6()
        "LARGE_LOW_MEM" -> largeLowMem()
        "INTRA" -> intraday()
        "1e6" -> oneMillionBars()
        "MC" -> multiCurrency()
        "ECB" -> ecbRates()
        "MIN" -> minimal()
        "MINUTES" -> manyMinutes()
        "TESTING" -> testingStrategies()
        "MANY" -> runMany()
        "DETERMINE" -> determine()
        "TA" -> ta()
        "TREND" -> trendFollowing()
        "TREND2" -> trendFollowing2()
        "TA_LARGE" -> taLarge()
        "PARALLEL" -> runParallel()
        "AVRO" -> avro()
        "AVRO_GEN" -> avroGen()
        "AVRO_CAP" -> avroCapture()
        "AVRO_ALL" -> {
            avroGen(); avro()
        }

    }

}