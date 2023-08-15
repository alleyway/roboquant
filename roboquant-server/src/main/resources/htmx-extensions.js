// noinspection JSUnresolvedReference,JSUnusedGlobalSymbols

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

htmx.defineExtension('echarts', {
    onEvent: function (name, evt) {
        console.log(name);
        if (name === 'htmx:beforeProcessNode') {
            let elem = evt.detail.elt;
            let chart = echarts.init(elem);
            let resizeObserver = new ResizeObserver(() => chart.resize());
            resizeObserver.observe(elem);
            elem.style.setProperty('display', 'none');
        }

        if (name === 'htmx:beforeSwap') {
            let option = JSON.parse(evt.detail.serverResponse);
            let elem = evt.detail.target;
            elem.style.setProperty('display', 'block');
            let chart = echarts.getInstanceByDom(elem);
            chart.setOption(option);
            return false
        }
        return true;
    }
});

htmx.config.allowEval = false;
