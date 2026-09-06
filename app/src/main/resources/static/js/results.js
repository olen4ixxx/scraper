/**
 * The results page: expanding a card, opening a leg's segments, and the small price-history
 * graph that comes with them.
 *
 * In a file of its own for the same reason as the date-range widget - so that something other
 * than a visitor's browser parses it. None of this is reachable from a test, and the browser
 * only reports a syntax error by rendering nothing.
 */
    function toggleExpand(summary) {
        summary.closest('.result-card').classList.toggle('expanded');
    }

    // Scoped to the leg the button sits in, so opening the outbound's segments doesn't
    // also open the return's.
    function toggleLegDetails(button) {
        const leg = button.closest('.leg');
        const segments = leg.querySelector('.segments');
        segments.classList.toggle('show');
        button.textContent = segments.classList.contains('show') ? 'Hide' : 'Details';
        if (segments.classList.contains('show')) {
            segments.querySelectorAll('.price-history[data-flight-id]').forEach(loadPriceHistory);
        }
    }

    function loadPriceHistory(container) {
        const flightId = container.getAttribute('data-flight-id');
        if (!flightId || flightId === 'null' || container.dataset.loaded) {
            return;
        }
        container.dataset.loaded = '1';
        fetch('/api/priceHistory?flightId=' + encodeURIComponent(flightId))
            .then(function(r) { return r.json(); })
            .then(function(points) { renderPriceHistory(container, points); })
            .catch(function() { container.innerHTML = ''; });
    }

    function formatDate(iso) {
        const d = new Date(iso);
        return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
    }

    // Every price we've collected for this exact flight over time, as a small sparkline.
    // Only changes are recorded - re-checking a fare that hasn't moved writes nothing - so a
    // point is a price change, which is why the label counts prices rather than checks.
    function renderPriceHistory(container, points) {
        if (!points || points.length === 0) {
            container.innerHTML = '<div class="price-history-label">Price history</div>'
                + '<div class="price-history-empty">First time we\'ve checked this flight</div>';
            return;
        }
        if (points.length === 1) {
            container.innerHTML = '<div class="price-history-label">Price history</div>'
                + '<div class="price-history-empty">One price so far, ' + Math.round(points[0].price)
                + ' ' + points[0].currency + ' on ' + formatDate(points[0].collectedAt) + '</div>';
            return;
        }

        const prices = points.map(function(p) { return p.price; });
        const min = Math.min.apply(null, prices);
        const max = Math.max.apply(null, prices);
        const w = 132, h = 34, pad = 5;

        const xs = [], ys = [];
        points.forEach(function(p, i) {
            xs.push(pad + (i / (points.length - 1)) * (w - 2 * pad));
            ys.push(max === min ? h / 2 : h - pad - ((p.price - min) / (max - min)) * (h - 2 * pad));
        });

        const first = points[0];
        const last = points[points.length - 1];
        const trendColor = last.price > first.price ? '#e53935' : (last.price < first.price ? '#2e7d32' : '#999');

        const line = xs.map(function(x, i) { return x.toFixed(1) + ',' + ys[i].toFixed(1); }).join(' ');
        // A dot per price, small enough that a dozen of them still read as a line. They are
        // what makes the individual prices visible at all - a bare polyline shows the shape
        // and hides every value that made it.
        let dots = '';
        xs.forEach(function(x, i) {
            dots += '<circle class="price-history-dot" data-i="' + i + '" cx="' + x.toFixed(1)
                + '" cy="' + ys[i].toFixed(1) + '" r="2.2" stroke="' + trendColor + '"/>';
        });

        const svg = '<svg width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '">'
            + '<polyline points="' + line + '" fill="none" stroke="' + trendColor
            + '" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>' + dots + '</svg>';

        container.innerHTML = '<div class="price-history-label">Price history ('
            + points.length + (points.length === 1 ? ' price' : ' prices') + ')</div>'
            + '<div class="price-history-chart">' + svg
            + '<span class="price-history-range">' + Math.round(min) + '–' + Math.round(max) + ' ' + last.currency + '</span>'
            + '<div class="price-history-tip"></div></div>';

        attachPriceHistoryTip(container.querySelector('.price-history-chart'), points, xs, w);
    }

    // Hover on a mouse, tap on a phone, both landing on the nearest price rather than
    // demanding you hit a two-pixel dot.
    function attachPriceHistoryTip(chart, points, xs, w) {
        const svg = chart.querySelector('svg');
        const tip = chart.querySelector('.price-history-tip');
        const dots = chart.querySelectorAll('.price-history-dot');
        let shown = -1;

        function show(index) {
            if (index === shown) { return; }
            shown = index;
            dots.forEach(function(dot, i) { dot.classList.toggle('is-active', i === index); });
            const p = points[index];
            tip.innerHTML = '<b>' + Math.round(p.price) + ' ' + p.currency + '</b> · ' + formatDate(p.collectedAt);
            // Kept inside the chart's own width, so the first and last prices don't push a
            // tooltip off the side of the card.
            const clamped = Math.min(Math.max(xs[index], 26), w - 26);
            tip.style.left = clamped + 'px';
            tip.classList.add('is-visible');
        }

        function hide() {
            shown = -1;
            dots.forEach(function(dot) { dot.classList.remove('is-active'); });
            tip.classList.remove('is-visible');
        }

        function nearest(event) {
            const box = svg.getBoundingClientRect();
            const x = (event.clientX - box.left) * (w / box.width);
            let best = 0;
            for (let i = 1; i < xs.length; i++) {
                if (Math.abs(xs[i] - x) < Math.abs(xs[best] - x)) { best = i; }
            }
            show(best);
        }

        svg.addEventListener('pointerdown', nearest);
        svg.addEventListener('pointermove', nearest);
        // A mouse leaving the chart has finished reading it. A finger lifting has not - but the
        // browser fires pointerleave the instant a touch ends, so the price appeared and went
        // again in the same tap, which is what this used to do on a phone. On touch the price
        // stays up until you touch something else.
        svg.addEventListener('pointerleave', function(event) {
            if (event.pointerType === 'mouse') { hide(); }
        });
        document.addEventListener('pointerdown', function(event) {
            if (!chart.contains(event.target)) { hide(); }
        });
    }
