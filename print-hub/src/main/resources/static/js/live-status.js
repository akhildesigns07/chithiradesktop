/*
 * Keeps the job list, job page and print room up to date without pressing reload.
 *
 * The page's <main data-live …> says what it shows:
 *   data-statuses="12:HELD,13:SENT"  jobs on screen and their status
 *   data-open-jobs="7"               (print room) jobs on its open lists, to notice new ones
 *   data-latest-id="42"              (job list, first page) newest job id, to notice new ones
 * Every 5 seconds this asks GET /jobs/live and reloads when anything differs. While someone is typing
 * counts (a form field changed and not saved), it waits and shows a note instead, so no input is lost.
 */
(function () {
    'use strict';

    const INTERVAL_MS = 5000;
    const main = document.querySelector('main[data-live]');
    if (!main) return;

    const known = {};
    (main.dataset.statuses || '').split(',').filter(Boolean).forEach(pair => {
        const [id, status] = pair.split(':');
        known[id] = status;
    });
    const ids = Object.keys(known);
    const openJobs = main.dataset.openJobs !== undefined ? Number(main.dataset.openJobs) : null;
    const latestId = main.dataset.latestId ? Number(main.dataset.latestId) : null;

    let dirty = false;          // a form on the page has unsaved input
    let changePending = false;  // an update arrived while the user was typing

    document.querySelectorAll('main form').forEach(form => {
        form.addEventListener('input', () => { dirty = true; });
        form.addEventListener('submit', () => { dirty = false; });
    });

    const note = document.createElement('div');
    note.className = 'live-note';
    note.setAttribute('role', 'status');
    document.body.appendChild(note);

    function showNote(text, waiting) {
        note.textContent = text;
        note.classList.toggle('waiting', waiting);
    }

    function typing() {
        const active = document.activeElement;
        return dirty || (active && active.closest && active.closest('main form') !== null
            && ['INPUT', 'SELECT', 'TEXTAREA'].includes(active.tagName));
    }

    function reloadWhenFree() {
        if (typing()) {
            changePending = true;
            showNote('Status changed – the page refreshes when you finish typing', true);
            return;
        }
        window.location.reload();
    }

    async function check() {
        try {
            const response = await fetch('/jobs/live?ids=' + encodeURIComponent(ids.join(',')),
                { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
            if (!response.ok) {
                showNote(response.status === 401 || response.status === 403 ? 'Live updates stopped – log in again' : 'Live updates paused', true);
                return;
            }
            const live = await response.json();
            const changed = ids.some(id => live.statuses[id] !== known[id])
                || (openJobs !== null && live.openJobs !== openJobs)
                || (latestId !== null && live.latestJobId > latestId);
            if (changed) {
                reloadWhenFree();
            } else if (!changePending) {
                showNote('● Live · checked ' + new Date().toLocaleTimeString(), false);
            }
        } catch (error) {
            showNote('Live updates paused (hub not reachable)', true);
        }
    }

    // finished typing (left the field without saving, or cleared it): apply the waiting update
    document.addEventListener('focusout', () => setTimeout(() => {
        if (changePending && !typing()) window.location.reload();
    }, 300));

    showNote('● Live', false);
    setInterval(check, INTERVAL_MS);
})();
