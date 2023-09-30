/*
 * Copyright 2020-2023 Neural Layer
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

package org.roboquant.ml

import org.roboquant.common.Asset
import org.roboquant.feeds.Event
import org.roboquant.feeds.PriceBar
import org.roboquant.ta.InsufficientData
import org.roboquant.ta.PriceBarSeries
import org.roboquant.ta.TaLib
import smile.data.vector.DoubleVector

class TaLibSingleFeature(
    override val name: String,
    private val asset: Asset,
    private val block: TaLib.(prices: PriceBarSeries) -> Double
) : SingelValueFeature() {

    private val t = TaLib()
    private val history = PriceBarSeries(1)
    private val prices = mutableListOf<Double>()

    companion object {

        fun rsi(asset: Asset, timePeriod: Int = 14): Feature {
            return TaLibSingleFeature("rsi", asset) {
                rsi(it, timePeriod)
            }
        }


    }

    override fun update(event: Event) {
        val action = event.prices[asset]
        var d = Double.NaN
        if (action != null && action is PriceBar && history.add(action)) {
            try {
                d = t.block(history)
            } catch (e: InsufficientData) {
                history.increaseCapacity(e.minSize)
            }
        }
        prices.add(d)
    }

    override fun reset() {
        history.clear()
        prices.clear()
    }

    override fun getVector(): DoubleVector {
        return DoubleVector.of(name, prices.toDoubleArray())
    }

}

