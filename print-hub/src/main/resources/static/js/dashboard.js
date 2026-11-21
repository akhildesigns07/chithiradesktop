/*
 * Draws every <canvas data-chart="…"> on a dashboard page with Chart.js.
 * The JSON comes from ChartSpec (server side): {type: columns|line|hbar, labels, stacked, unit, series:[{name, slot, values}]}.
 *
 * Mark specs: bars ≤ 24px with a 4px rounded data end (square at the baseline), a 2px surface gap between
 * stacked segments, 2px lines with ≥ 8px ringed markers, hairline solid grid, recessive axes.
 * Hover: crosshair + one tooltip listing every series on columns and lines; per-bar tooltip on horizontal bars.
 * Colours come from CSS tokens (hub.css), so light and dark each use their own validated steps.
 */
(function () {
    'use strict';

    const charts = [];

    function token(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    function tokens() {
        return {
            surface: token('--chart-surface'),
            grid: token('--chart-grid'),
            text: token('--text-primary'),
            muted: token('--text-secondary'),
            series: { 1: token('--series-1'), 2: token('--series-2') }
        };
    }

    function formatValue(value, unit) {
        if (value === null || value === undefined) return '–';
        if (unit === '%') return value.toFixed(1) + ' %';
        return Math.round(value).toLocaleString();
    }

    /** Axis ticks: in full under 10,000, compact (12K) above. */
    function formatTick(value, unit, max) {
        if (unit === '%') return value + ' %';
        if (max >= 10000) return value >= 1000 ? (value / 1000).toLocaleString() + 'K' : value;
        return value.toLocaleString();
    }

    /** True when no later (stacked-on-top) series has a value at this index: this segment carries the data end. */
    function isDataEnd(spec, seriesIndex, index) {
        if (!spec.stacked) return true;
        for (let next = seriesIndex + 1; next < spec.series.length; next++) {
            if ((spec.series[next].values[index] || 0) > 0) return false;
        }
        return true;
    }

    /** Vertical hairline at the hovered x (columns and lines). */
    const crosshair = {
        id: 'crosshair',
        afterDatasetsDraw(chart, args, options) {
            const active = chart.tooltip && chart.tooltip.getActiveElements();
            if (!options.enabled || !active || !active.length) return;
            const x = active[0].element.x, area = chart.chartArea, ctx = chart.ctx;
            ctx.save();
            ctx.beginPath();
            ctx.moveTo(x, area.top);
            ctx.lineTo(x, area.bottom);
            ctx.lineWidth = 1;
            ctx.strokeStyle = options.color;
            ctx.stroke();
            ctx.restore();
        }
    };

    function build(canvas) {
        const spec = JSON.parse(canvas.dataset.chart);
        const box = canvas.parentElement;
        const hasData = spec.labels.length > 0 && spec.series.some(s => s.values.some(v => v !== null && v > 0));
        if (!hasData) {
            canvas.hidden = true;
            if (!box.querySelector('.chart-empty')) {
                const empty = document.createElement('div');
                empty.className = 'chart-empty';
                empty.textContent = 'No data for this range';
                box.appendChild(empty);
            }
            return;
        }
        const t = tokens();
        const horizontal = spec.type === 'hbar';
        const line = spec.type === 'line';
        const font = getComputedStyle(document.body).fontFamily;
        const pointCount = spec.labels.length;
        const max = Math.max(...spec.labels.map((_, i) =>
            spec.stacked ? spec.series.reduce((sum, s) => sum + (s.values[i] || 0), 0)
                         : Math.max(...spec.series.map(s => s.values[i] || 0))));

        const datasets = spec.series.map((series, seriesIndex) => {
            const colour = t.series[series.slot];
            if (line) {
                return {
                    label: series.name, data: series.values, borderColor: colour, backgroundColor: colour,
                    borderWidth: 2, borderCapStyle: 'round', borderJoinStyle: 'round', tension: 0, spanGaps: false,
                    pointRadius: pointCount <= 45 ? 4 : 0, pointHoverRadius: 5,
                    pointBorderColor: t.surface, pointBorderWidth: 2, pointHitRadius: 12
                };
            }
            const end = horizontal ? { topRight: 4, bottomRight: 4 } : { topLeft: 4, topRight: 4 };
            return {
                label: series.name, data: series.values, backgroundColor: colour,
                maxBarThickness: 24, categoryPercentage: 0.8, barPercentage: 0.9, borderSkipped: false,
                borderRadius: ctx => isDataEnd(spec, seriesIndex, ctx.dataIndex) ? end : 0,
                // 2px surface gap where a segment sits on the one below it
                borderColor: t.surface,
                borderWidth: spec.stacked && seriesIndex > 0 ? (horizontal ? { left: 2 } : { bottom: 2 }) : 0
            };
        });

        const valueAxis = {
            stacked: spec.stacked, beginAtZero: true,
            grid: { color: t.grid, lineWidth: 1, drawTicks: false },
            border: { display: false },
            ticks: { color: t.muted, padding: 6, maxTicksLimit: 6, callback: value => formatTick(value, spec.unit, max) }
        };
        const categoryAxis = {
            stacked: spec.stacked,
            grid: { display: false },
            border: { color: t.grid },
            ticks: { color: t.muted, autoSkip: true, maxRotation: 0, autoSkipPadding: 12,
                     callback: function (value) {
                         const label = this.getLabelForValue(value);
                         return label.length > 28 ? label.slice(0, 27) + '…' : label;
                     } }
        };

        const chart = new Chart(canvas, {
            type: line ? 'line' : 'bar',
            data: { labels: spec.labels, datasets },
            options: {
                indexAxis: horizontal ? 'y' : 'x',
                maintainAspectRatio: false,
                animation: false,
                interaction: { mode: 'index', intersect: horizontal, axis: horizontal ? 'y' : 'x' },
                font: { family: font },
                scales: horizontal ? { x: valueAxis, y: categoryAxis } : { x: categoryAxis, y: valueAxis },
                plugins: {
                    legend: {
                        display: spec.series.length >= 2, position: 'top', align: 'start',
                        labels: { color: t.text, usePointStyle: true, pointStyle: line ? 'line' : 'rectRounded',
                                  boxWidth: 10, boxHeight: 10, padding: 12, font: { family: font } }
                    },
                    tooltip: {
                        backgroundColor: t.surface, titleColor: t.muted, bodyColor: t.text,
                        borderColor: t.grid, borderWidth: 1, padding: 10, usePointStyle: true,
                        titleFont: { family: font, weight: 'normal' }, bodyFont: { family: font },
                        callbacks: {
                            // value first, series name after
                            label: item => ' ' + formatValue(item.raw, spec.unit) + '  ' + item.dataset.label,
                            labelPointStyle: () => ({ pointStyle: 'line', rotation: 0 }),
                            footer: items => spec.stacked && items.length > 1
                                ? formatValue(items.reduce((sum, i) => sum + (i.raw || 0), 0), spec.unit) + '  total' : ''
                        },
                        footerColor: t.muted, footerFont: { family: font, weight: 'normal' }
                    },
                    crosshair: { enabled: !horizontal, color: t.grid }
                }
            },
            plugins: [crosshair]
        });
        canvas.setAttribute('role', 'img');
        charts.push(chart);
    }

    function render() {
        while (charts.length) charts.pop().destroy();
        document.querySelectorAll('canvas[data-chart]').forEach(build);
    }

    document.addEventListener('DOMContentLoaded', render);
    // the theme follows the OS: redraw with the other mode's colours
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => setTimeout(render, 0));
})();
