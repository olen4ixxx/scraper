/**
 * A month calendar behind a single read-only field. The first click picks the start and the
 * second the end, so a range is two clicks in one place rather than two separate date boxes;
 * picking the same day twice is how you say "just this day". The visible field is only ever a
 * label - the two hidden inputs it writes are what the form actually submits, so the server side
 * is unchanged.
 *
 * Kept in a file of its own rather than inlined in the page so that it can be checked at all.
 * It is the one substantial piece of logic here that no test could reach: a stray brace once
 * took the whole form down with a syntax error, and the only thing that noticed was opening the
 * page and looking at it. A file can be parsed on every push.
 */
function createDateRange(options) {
    const root = document.getElementById(options.rootId);
    const display = root.querySelector('.daterange-input');
    const calendar = root.querySelector('.calendar');
    const startInput = document.getElementById(options.startId);
    const endInput = document.getElementById(options.endId);
    const monthNames = ['January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'];

    let start = null;
    let end = null;
    let viewMonth = null;
    let awaitingEnd = false;

    function toIso(d) {
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    }
    function pad(n) { return n < 10 ? '0' + n : String(n); }
    function fromIso(s) {
        const parts = s.split('-');
        return new Date(+parts[0], +parts[1] - 1, +parts[2]);
    }
    function startOfDay(d) { return new Date(d.getFullYear(), d.getMonth(), d.getDate()); }
    function sameDay(a, b) { return a && b && toIso(a) === toIso(b); }
    function formatShort(d) {
        return d.getDate() + ' ' + monthNames[d.getMonth()].substring(0, 3);
    }

    function minDate() {
        const floor = options.minDate ? options.minDate() : null;
        const todayDate = startOfDay(new Date());
        return floor && floor > todayDate ? floor : todayDate;
    }

    /**
     * Builds the month grid. The colouring of the range is deliberately not done here:
     * see paint(), which works on the buttons this leaves behind. Rebuilding the grid
     * under a moving cursor would destroy the very button being hovered.
     */
    function render() {
        if (!viewMonth) {
            viewMonth = new Date((start || minDate()).getFullYear(), (start || minDate()).getMonth(), 1);
        }
        const first = new Date(viewMonth.getFullYear(), viewMonth.getMonth(), 1);
        const daysInMonth = new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 0).getDate();
        // Monday-first, matching how European calendars read.
        const leading = (first.getDay() + 6) % 7;
        const floor = minDate();

        let html = '<div class="calendar-head">'
            + '<button type="button" class="calendar-nav" data-nav="-1">‹</button>'
            + '<span class="calendar-month">' + monthNames[viewMonth.getMonth()] + ' ' + viewMonth.getFullYear() + '</span>'
            + '<button type="button" class="calendar-nav" data-nav="1">›</button>'
            + '</div><div class="calendar-grid">';
        ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'].forEach(function(d) {
            html += '<div class="calendar-dow">' + d + '</div>';
        });
        for (let i = 0; i < leading; i++) {
            html += '<button type="button" class="calendar-day blank" disabled></button>';
        }
        for (let day = 1; day <= daysInMonth; day++) {
            const date = new Date(viewMonth.getFullYear(), viewMonth.getMonth(), day);
            const disabled = date < floor;
            html += '<button type="button" class="calendar-day"'
                + (disabled ? ' disabled' : '') + ' data-date="' + toIso(date) + '">' + day + '</button>';
        }
        html += '</div><div class="calendar-hint"></div>';
        calendar.innerHTML = html;
        paint(null);
    }

    /**
     * Colours the days and writes the hint, for a range that may only be half chosen.
     *
     * <p>Three states, and the shapes are the point of them. Nothing chosen: plain days.
     * A start and no end: that day is drawn square on its right with a stub running off
     * it, so it reads as an opening rather than a finished selection - a first click
     * used to look exactly like a completed single date, which is what made the second
     * click undiscoverable. Both ends: one continuous band.
     *
     * <p>previewEnd is the day under the cursor while the range is half open, drawn in
     * a lighter shade so the answer to "what would clicking here give me" is on screen
     * before it is clicked.
     */
    function paint(previewEnd) {
        const tentativeEnd = end || (previewEnd && previewEnd >= start ? previewEnd : null);
        const previewing = !end && !!tentativeEnd;

        calendar.querySelectorAll('[data-date]').forEach(function(button) {
            const date = fromIso(button.dataset.date);
            button.className = 'calendar-day';

            if (!start) {
                return;
            }
            if (sameDay(date, start)) {
                if (!tentativeEnd) {
                    // A start with nothing after it yet. Open if a second click is
                    // expected, a finished single date if it is not.
                    button.classList.add(awaitingEnd ? 'is-open' : 'range-single');
                } else if (sameDay(start, tentativeEnd)) {
                    button.classList.add('range-single');
                } else {
                    button.classList.add('range-start');
                }
            } else if (tentativeEnd && sameDay(date, tentativeEnd)) {
                button.classList.add(previewing ? 'preview-end' : 'range-end');
            } else if (tentativeEnd && date > start && date < tentativeEnd) {
                button.classList.add(previewing ? 'preview-range' : 'in-range');
            }
        });

        const hint = calendar.querySelector('.calendar-hint');
        if (awaitingEnd && start) {
            hint.textContent = 'From ' + formatShort(start) + ' — now pick the last day, or the same day again for one date';
            hint.classList.add('is-active');
        } else {
            hint.textContent = 'Pick a day, then another one for a range';
            hint.classList.remove('is-active');
        }
    }

    function syncOut() {
        startInput.value = start ? toIso(start) : '';
        // A single day is submitted as a start with no end, which is what the server
        // already treats as "just this date".
        endInput.value = end && !sameDay(start, end) ? toIso(end) : '';

        if (!start) {
            display.value = '';
        } else if (!end || sameDay(start, end)) {
            display.value = formatShort(start) + ' ' + start.getFullYear();
        } else {
            display.value = formatShort(start) + ' – ' + formatShort(end) + ' ' + end.getFullYear();
        }
        if (options.onChange) options.onChange();
    }

    function open() {
        document.querySelectorAll('.calendar.open').forEach(function(c) {
            if (c !== calendar) c.classList.remove('open');
        });
        viewMonth = new Date((start || minDate()).getFullYear(), (start || minDate()).getMonth(), 1);
        render();
        calendar.classList.add('open');
    }

    function close() {
        calendar.classList.remove('open');
        awaitingEnd = false;
    }

    display.addEventListener('click', function(e) {
        e.stopPropagation();
        calendar.classList.contains('open') ? close() : open();
    });


    // Shows the range the cursor is currently offering. Only while a start exists
    // without an end - at any other time there is nothing to preview, and repainting
    // on every mouse move would be work for nothing.
    calendar.addEventListener('mouseover', function(e) {
        if (!awaitingEnd || !start) {
            return;
        }
        const day = e.target.closest('[data-date]');
        if (day && !day.disabled) {
            paint(fromIso(day.dataset.date));
        }
    });

    calendar.addEventListener('mouseleave', function() {
        if (awaitingEnd) {
            paint(null);
        }
    });
    calendar.addEventListener('click', function(e) {
        // Picking a day re-renders the grid, which detaches the very button that was
        // clicked - so by the time the click reaches the document-level "clicked
        // outside" handler, the target is no longer inside this widget and the range
        // would be closed and reset halfway through. Keeping the click here avoids it.
        e.stopPropagation();

        const nav = e.target.closest('[data-nav]');
        if (nav) {
            viewMonth = new Date(viewMonth.getFullYear(), viewMonth.getMonth() + Number(nav.dataset.nav), 1);
            render();
            return;
        }
        const dayButton = e.target.closest('[data-date]');
        if (!dayButton || dayButton.disabled) {
            return;
        }
        const picked = fromIso(dayButton.dataset.date);
        // Repainted rather than rebuilt: the grid is for the month already on screen,
        // and replacing it would destroy the button the cursor is sitting on - which
        // means no preview of the range until the mouse moves again, exactly when the
        // preview is most wanted.
        if (!awaitingEnd) {
            start = picked;
            end = null;
            awaitingEnd = true;
            paint(null);
            syncOut();
            return;
        }
        // A second pick before the first restarts the range rather than inverting it.
        if (picked < start) {
            start = picked;
            paint(null);
            syncOut();
            return;
        }
        end = picked;
        awaitingEnd = false;
        paint(null);
        syncOut();
        close();
    });

    document.addEventListener('click', function(e) {
        if (!e.target.closest('#' + options.rootId)) close();
    });

    return {
        startDate: function() { return start; },
        set: function(startIso, endIso) {
            start = startIso ? fromIso(startIso) : null;
            end = endIso ? fromIso(endIso) : null;
            awaitingEnd = false;
            syncOut();
        },
        clear: function() {
            start = null;
            end = null;
            awaitingEnd = false;
            syncOut();
        }
    };
}
