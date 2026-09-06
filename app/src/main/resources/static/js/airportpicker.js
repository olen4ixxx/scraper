/**
 * The place picker, used unchanged on both sides of the search.
 *
 * There used to be two different things here. "To" was this: a text box that filtered a list,
 * chips for what you had chosen, a "+" on each chip offering the airports near it. "From" was a
 * dropdown of twelve Polish airports, and a Swap button existed to move a destination into it -
 * which is a strange way to say "I want to leave from Milan", and the only way there was.
 *
 * Both sides now offer every airport we hold flights for. Reversing a search is picking the
 * places the other way round, so there is nothing left for a Swap button to do. The two sides
 * were always the same to the server, which takes the same comma-separated tokens from each.
 *
 * In a file of its own so that something other than a visitor's browser parses it - a syntax
 * error in an inline script shows up as a page that quietly does nothing.
 */
function createAirportPicker(options) {
    const root = document.getElementById(options.rootId);
    const airports = options.airports || [];
    const searchInput = root.querySelector('[data-role="search"]');
    const hiddenInput = root.querySelector('[data-role="hidden"]');
    const suggestionsBox = root.querySelector('[data-role="suggestions"]');
    const chipsBox = root.querySelector('[data-role="chips"]');
    const nearbyPanel = root.querySelector('[data-role="nearby"]');
    const clearAllBtn = root.querySelector('[data-role="clear-all"]');

    const byCountry = {};
    airports.forEach(function(a) {
        if (!byCountry[a.country]) byCountry[a.country] = [];
        byCountry[a.country].push(a);
    });
    const countries = Object.keys(byCountry).sort();

    let selected = [];
    let nearbyOpenFor = null;

    function haversineKm(lat1, lon1, lat2, lon2) {
        const r = 6371;
        const dLat = (lat2 - lat1) * Math.PI / 180;
        const dLon = (lon2 - lon1) * Math.PI / 180;
        const a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    function hideNearbyPanel() {
        nearbyOpenFor = null;
        nearbyPanel.style.display = 'none';
        nearbyPanel.innerHTML = '';
    }

    function showNearby(iata) {
        const target = airports.find(function(a) { return a.iata === iata; });
        if (!target) return;

        const selectedValues = selected.map(function(s) { return s.value; });
        const nearby = airports
            .filter(function(a) { return a.iata !== iata && selectedValues.indexOf(a.iata) === -1; })
            .map(function(a) { return { airport: a, distance: haversineKm(target.lat, target.lon, a.lat, a.lon) }; })
            .sort(function(x, y) { return x.distance - y.distance; })
            .slice(0, 10);

        nearbyPanel.innerHTML = '';
        const title = document.createElement('div');
        title.className = 'nearby-panel-title';
        title.textContent = 'Airports near ' + target.city + ', in order of distance';
        nearbyPanel.appendChild(title);

        if (nearby.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'nearby-empty';
            empty.textContent = 'No other collected airports nearby';
            nearbyPanel.appendChild(empty);
        } else {
            nearby.forEach(function(n) {
                const item = document.createElement('div');
                item.className = 'nearby-item';

                const label = document.createElement('span');
                label.textContent = n.airport.city + ' (' + n.airport.iata + ')';

                const dist = document.createElement('span');
                dist.className = 'distance';
                dist.textContent = Math.round(n.distance) + ' km';

                item.appendChild(label);
                item.appendChild(dist);
                item.addEventListener('click', function() {
                    addSelection(n.airport.city + ' (' + n.airport.iata + ')', n.airport.iata, 'airport');
                    showNearby(iata);
                });
                nearbyPanel.appendChild(item);
            });
        }

        nearbyPanel.style.display = 'block';
        nearbyOpenFor = iata;
    }

    function toggleNearby(iata) {
        if (nearbyOpenFor === iata) {
            hideNearbyPanel();
        } else {
            showNearby(iata);
        }
    }

    function renderChips() {
        chipsBox.innerHTML = '';
        selected.forEach(function(s) {
            const chip = document.createElement('div');
            chip.className = 'chip';

            const label = document.createElement('span');
            label.className = 'chip-label';
            label.textContent = s.label;
            chip.appendChild(label);

            if (s.kind === 'airport') {
                const nearbyBtn = document.createElement('button');
                nearbyBtn.type = 'button';
                nearbyBtn.className = 'chip-nearby-btn';
                nearbyBtn.setAttribute('data-role', 'nearby-toggle');
                nearbyBtn.textContent = '+';
                nearbyBtn.title = 'Show nearby airports';
                nearbyBtn.addEventListener('click', function(e) {
                    e.stopPropagation();
                    toggleNearby(s.value);
                });
                chip.appendChild(nearbyBtn);
            } else if (s.kind === 'country' || s.kind === 'city') {
                const addMoreBtn = document.createElement('button');
                addMoreBtn.type = 'button';
                addMoreBtn.className = 'chip-nearby-btn';
                addMoreBtn.textContent = '+';
                addMoreBtn.title = 'Add another airport or country';
                addMoreBtn.addEventListener('click', function(e) {
                    e.stopPropagation();
                    hideNearbyPanel();
                    searchInput.focus();
                    render(searchInput.value);
                });
                chip.appendChild(addMoreBtn);
            }

            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'chip-remove';
            removeBtn.textContent = '×';
            removeBtn.title = 'Remove';
            removeBtn.addEventListener('click', function() {
                removeSelection(s.value);
            });
            chip.appendChild(removeBtn);

            chipsBox.appendChild(chip);
        });

        hiddenInput.value = selected.map(function(s) { return s.value; }).join(',');
        clearAllBtn.style.display = selected.length > 0 ? 'inline' : 'none';
    }

    function addSelection(label, value, kind) {
        if (kind === 'anywhere') {
            selected = [{ label: label, value: value, kind: kind }];
        } else {
            selected = selected.filter(function(s) { return s.kind !== 'anywhere' && s.value !== value; });
            selected.push({ label: label, value: value, kind: kind });
        }
        searchInput.value = '';
        suggestionsBox.classList.remove('open');
        hideNearbyPanel();
        renderChips();
    }

    function removeSelection(value) {
        selected = selected.filter(function(s) { return s.value !== value; });
        hideNearbyPanel();
        renderChips();
    }

    function addSuggestionItem(label, value, kind, className) {
        const item = document.createElement('div');
        item.className = 'suggestion-item' + (className ? ' ' + className : '');
        item.textContent = label;
        // A plain click, not mousedown: nothing here closes on blur, so there is no race to
        // win, and a click is the one event that behaves the same under a finger as a mouse.
        item.addEventListener('click', function() {
            addSelection(label, value, kind);
        });
        suggestionsBox.appendChild(item);
    }

    function render(filter) {
        const q = (filter || '').trim().toLowerCase();
        suggestionsBox.innerHTML = '';
        let anyMatch = false;

        if (options.allowAnywhere && (!q || 'anywhere'.indexOf(q) !== -1)) {
            addSuggestionItem('Anywhere', 'ANYWHERE', 'anywhere', 'suggestion-anywhere');
            anyMatch = true;
        }

        countries.forEach(function(country) {
            const airportsHere = byCountry[country];
            const countryMatches = country.toLowerCase().indexOf(q) !== -1;
            const matchingAirports = airportsHere.filter(function(a) {
                return countryMatches
                    || a.city.toLowerCase().indexOf(q) !== -1
                    || a.iata.toLowerCase().indexOf(q) !== -1
                    || a.name.toLowerCase().indexOf(q) !== -1;
            });

            if (q && !countryMatches && matchingAirports.length === 0) {
                return;
            }

            const byCity = {};
            airportsHere.forEach(function(a) {
                if (!byCity[a.city]) byCity[a.city] = [];
                byCity[a.city].push(a);
            });
            const cityNames = Object.keys(byCity).sort();

            let countryHeaderAdded = false;
            cityNames.forEach(function(cityName) {
                const cityAirports = byCity[cityName];
                const cityMatches = countryMatches || cityName.toLowerCase().indexOf(q) !== -1;
                const shownAirports = cityAirports.filter(function(a) {
                    return cityMatches
                        || a.iata.toLowerCase().indexOf(q) !== -1
                        || a.name.toLowerCase().indexOf(q) !== -1;
                });
                if (shownAirports.length === 0) return;

                if (!countryHeaderAdded) {
                    anyMatch = true;
                    addSuggestionItem('🌍 All of ' + country + ' (' + airportsHere.length + ')',
                        'COUNTRY:' + country, 'country', 'suggestion-country');
                    countryHeaderAdded = true;
                }

                if (cityAirports.length > 1) {
                    addSuggestionItem(cityName + ' (All ' + cityAirports.length + ')',
                        'CITY:' + cityName, 'city', 'suggestion-city');
                }
                shownAirports.forEach(function(a) {
                    addSuggestionItem(a.city + ' (' + a.iata + ')', a.iata, 'airport', 'suggestion-airport');
                });
            });
        });

        if (!anyMatch) {
            const empty = document.createElement('div');
            empty.className = 'suggestion-empty';
            empty.textContent = 'No matches';
            suggestionsBox.appendChild(empty);
        }

        suggestionsBox.classList.add('open');
    }

    searchInput.addEventListener('focus', function() { render(searchInput.value); });
    searchInput.addEventListener('input', function() { render(searchInput.value); });

    clearAllBtn.addEventListener('click', function() {
        selected = [];
        hideNearbyPanel();
        suggestionsBox.classList.remove('open');
        renderChips();
        searchInput.focus();
    });

    /**
     * Anything outside puts both panels away.
     *
     * <p>Two things were wrong before. It listened for "click", which on iOS never reaches the
     * document from a tap on ordinary page background - so on a phone the nearby list could only
     * be closed by pressing the same "+" that opened it. And it treated the whole field as
     * "inside", so a tap on the text box left the nearby list hanging below it. The list closes
     * on anything but itself now; the suggestions still count the field as their own, since
     * typing in the box is how you drive them.
     */
    document.addEventListener('pointerdown', function(e) {
        const target = e.target;
        if (!nearbyPanel.contains(target) && !isOwnNearbyToggle(target)) {
            hideNearbyPanel();
        }
        if (!root.contains(target)) {
            suggestionsBox.classList.remove('open');
        }
    });

    /** This field's own "+", which owns the panel and is allowed to toggle it shut itself. */
    function isOwnNearbyToggle(target) {
        return !!(target && target.closest && root.contains(target)
            && target.closest('[data-role="nearby-toggle"]'));
    }

    /**
     * Turns a submitted token back into a chip. The two shorthands are the ones the old fixed
     * "From" dropdown used to submit; they still arrive from saved searches.
     */
    function tokenToSelection(token) {
        const upper = token.toUpperCase();
        if (upper === 'ANYWHERE') {
            return { label: 'Anywhere', value: 'ANYWHERE', kind: 'anywhere' };
        }
        if (upper.indexOf('COUNTRY:') === 0) {
            const name = token.substring(8);
            return { label: '🌍 All of ' + name, value: 'COUNTRY:' + name, kind: 'country' };
        }
        if (upper.indexOf('CITY:') === 0) {
            const name = token.substring(5);
            return { label: name + ' (All)', value: 'CITY:' + name, kind: 'city' };
        }
        if (upper === 'POLAND') {
            return { label: '🌍 Poland (all airports)', value: 'POLAND', kind: 'country' };
        }
        if (upper === 'WARSAW') {
            return { label: 'Warsaw (WAW + WMI)', value: 'WARSAW', kind: 'city' };
        }
        const airport = airports.find(function(a) { return a.iata === upper; });
        return {
            label: airport ? (airport.city + ' (' + airport.iata + ')') : upper,
            value: upper,
            kind: 'airport'
        };
    }

    return {
        /** Fills the field from a comma-separated list of tokens; empty leaves it untouched. */
        set: function(tokens) {
            if (!tokens) return false;
            const parsed = tokens.split(',')
                .map(function(t) { return t.trim(); })
                .filter(function(t) { return t; })
                .map(tokenToSelection);
            if (parsed.length === 0) return false;
            selected = parsed;
            renderChips();
            return true;
        },
        setDefault: function(tokens) {
            if (selected.length === 0) {
                this.set(tokens);
            }
        },
        isEmpty: function() { return selected.length === 0; },
        focus: function() {
            searchInput.focus();
            render(searchInput.value);
        },
        markInvalid: function() {
            searchInput.style.borderColor = '#e53935';
        }
    };
}
