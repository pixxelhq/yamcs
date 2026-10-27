# Parameter Chart tab: enum support + stacked multi-chart layout

## Context

Two rounds of work on the Parameter → Chart tab, tracked in one document.

**Round 1 (`../task files/task.txt` origin)** — enum-typed telemetry parameters could not be plotted at all: the archive path turned every enum sample into `NaN` and the realtime path dropped them silently. Fixed end to end (backend `Downsampler`/`ParameterValuesApi`, frontend value conversion, axis/tooltip/legend labels).

**Round 2 (`../task files/tast2.txt`)** — with enums plottable and multiple traces per chart supported, the remaining problem was the single shared Y-axis: a boolean (0–1), an enum with ordinals 0–200 and a voltage (3.0–4.2) squashed onto one scale are unreadable. Added a **Stacked** layout (one plot per parameter, own Y-axis, shared time axis) and a top-control toggle between it and the existing **Overlay** layout.

Both rounds are implemented on branch `feat/tm_enum_charts`. Sections below keep the original findings and reasoning, annotated with what actually shipped.

## Implementation status

**Feature 1 (enable enum charts) — implemented, not yet build-verified.** Branch `feat/tm_enum_charts`.

| Step | File(s) | Done |
| ---- | ------- | ---- |
| 1 | `Downsampler.java` — `ENUMERATED` + `BOOLEAN` cases in `accept()` and `process(pval)`, `enableCategoricalMode(Map)` = last-value-per-bucket + label→ordinal, `Sample.processLast()` | ✅ |
| 2 | `ParameterValuesApi.java` — `getParameterSamples` resolves `EnumeratedParameterType` (label→ordinal map) or `BooleanParameterType` (null map), calls `enableCategoricalMode` (scalar eng path only) | ✅ |
| 3 | `webapp-sdk/utils.ts` — `convertValueToNumber` handles `ENUMERATED` + `BOOLEAN` | ✅ |
| 4 | `TraceConfig.ts` — `enumValues?: EnumValue[]` | ✅ |
| 5 | `PlotBand.ts` (`updateValueFormatters`, `getValueLabel`), `parameter-chart-tab.component.ts` (`resolveEnumValues()` populates `enumValues`, default enum `lineStyle:'step'`, legend labels) | ✅ |
| 5b | Enum value↔label key in the detail pane — `trace-config.component.{ts,html,css}`, `enumValuesForTrace()` | ✅ |
| 5c | Always-visible ordinal↔label key overlaid next to the plot — `parameter-chart-tab.component.{html,css}` `.enum-key-overlay`, `enumKeys()` | ✅ |

**Bugs found during simulator testing (2026-08-28), fixed:**
- `Downsampler.accept()` / `process(pval)` dropped `BOOLEAN` → NaN. Added `BOOLEAN` cases (maps to 0/1).
- `parameter-chart-tab.component.ts` checked `engType === 'ENUMERATED'` — the MDB API value is `'enumeration'`, so enum labels never activated (Y-axis, legend, detail-pane key all showed bare ordinals). Fixed + consolidated into `resolveEnumValues()`.
- Enum ordinals arrive from the MDB API as **strings** (`int64` → JSON string), breaking the `=== value` / `Map<number>` lookups. `resolveEnumValues()` now coerces `value` with `Number()`.
- `landing.xml` `EnumTest` container used wrong absolute bit offsets (`128…`); switched to sequential entries after `/PUS/hkid`.
- `resolveEnumValues()` only handled `engType === 'enumeration'`, so **boolean** traces (`EnumTest_Flag`) got no key overlay and no `FALSE`/`TRUE` Y-axis labels — just bare `0`/`1`. Now also synthesizes a 2-row key for `engType === 'boolean'` from `zeroStringValue`/`oneStringValue`. Matching backend: `ParameterValuesApi` calls `enableCategoricalMode(null)` for `BooleanParameterType` so buckets keep last value instead of averaging toward `0.5`. (That branch was documented here but missing from the code until 2026-09-08 — until then historical boolean traces averaged; realtime was unaffected.)
| 7 | Enum badge in parameter picker | ❌ optional |
| 8 | Tests | ❌ |

**Features 3 + 4 (stacked multi-chart layout + toggle) — implemented, not yet build-verified.**

Requirement (`../task files/tast2.txt`): plot N parameters as N vertically stacked sections that share one time axis but each own their Y axis, plus a toggle between that and the existing single-plot layout.

Decisions taken with the user:
- Stacked layout is **exactly one section per trace**. No grouping UI, and *not* the auto-split-by-type grouping that Open Question 5 previously assumed (superseded).
- The toggle is **not persisted** — every page load starts in overlay layout. Not in the URL `state`, not in a preference.

| Step | File(s) | Done |
| ---- | ------- | ---- |
| 6a | `PlotBand.ts` — demoted to a dumb renderer. `PlotDataSource`, the `BackfillingSubscription` and the CDK tooltip overlay moved out to the component so N bands share one WebSocket subscription and one set of archive requests. New `setTraces(traceIds, configById)` / `getTraceIds()` / `hasTrace()`; `addOrUpdateTrace`/`removeTrace`/`applyOrder`/`toggleFill`/`updateWindow` removed | ✅ |
| 6b | `parameter-chart-tab.component.ts` — owns `dataSource`, `tooltip`, `backfillSubscription`, `traceConfigById`, `requestedNameByTraceId`, `orderedTraceIds`, `bands: PlotBand[]`; `chartLayout` signal; `syncBands()` (rebuild only when the composition changes, TimeRuler recreated last so it stays at the bottom); `layoutBands()` divides the height and computes chip offsets | ✅ |
| 6c | `parameter-chart-tab.component.html/.css` — Overlay/Stacked `ya-button-group` in the top controls; global legend in overlay layout, one per-plot legend chip in stacked layout (`.band-legends`) | ✅ |
| 6d | `tooltip.component.ts` — optional `traceIds` filter so a hovered plot lists only its own traces | ✅ |
| 6e | `loadHLines()` — zero line on every plot; alarm thresholds only on the plot showing the URL's parameter (unchanged in overlay layout) | ✅ |

**Fixed 2026-09-08:** auto-generated Y-axis ticks landing on half-ordinals (`1.5`) now render blank. `PlotBand.updateValueFormatters()` suppresses fractional ticks only when *every* trace on the band is categorical, so an overlay band mixing an enum with a numeric trace keeps plain numbers on its shared axis.

## Historical-data review (2026-09-08)

Question asked: does the stacked layout (Round 2) also work when viewing historical data, not just realtime? **Yes** — the layout is a pure rendering split and nothing in the data path is layout-aware. Verified by reading:

- Archive fetch is layout-independent: `PlotDataSource.updateWindow()` issues one `downsampleMean` per trace on every viewport change, identical in overlay and stacked (`PlotDataSource.ts:112`).
- Toggling layout rebuilds bands but triggers **no refetch** — `data$` is a `BehaviorSubject`, so a freshly constructed `PlotBand` receives the current archive snapshot on subscribe (`PlotBand.ts:41`, `syncBands()` at `parameter-chart-tab.component.ts:527`).
- Enum labels on history come entirely from the backend (`ParameterValuesApi` `enableCategoricalMode` + the `Downsampler` `ENUMERATED` cases in both `process(pval)` and the columnar `accept()`), which the frontend layout never touches. Per-band `updateValueFormatters()` builds its table from that band's own traces, so a stacked band shows only its own enumeration.

### Fixed in this pass

| Gap | Fix |
| --- | --- |
| `ParameterValuesApi` had **no `BooleanParameterType` branch** (this doc claimed otherwise). Historical boolean traces averaged toward 0.5 instead of stepping; realtime unaffected | Added `else if (ptype instanceof BooleanParameterType) sampler.enableCategoricalMode(null)` — booleans arrive as `BOOLEAN` on both retrieval paths, so no label table is needed |
| `PlotBand.loadData()` gap detection used the trace index for point lookups | Inner loop now indexed by `j` over a local `samples` array |
| Half-ordinal Y-axis ticks (`1.5`) rendered as bare numbers | `axisLabelFormatter` returns `''` for fractional values, but only when every trace on the band is categorical |

### Deferred, with reasons

1. **Legend value is blank in a past-only range.** `PlotBand.getParameterValue()` reads `latestRealtimeEngValues` only, so with no realtime traffic the chip shows just the parameter name. More noticeable in stacked layout, where the chip doubles as the plot title. A fallback to the last archived point in the visible window would change realtime semantics too — the chip would sometimes mean "now" and sometimes "last known", with nothing to distinguish them. **Needs a product decision before coding.**
2. **Y-axis zoom not persisted in stacked layout.** `State.minimum`/`maximum` are scalars in the URL schema; per-band persistence means an array plus a migration path for already-bookmarked URLs.
3. **Stacked sections are clipped, not scrollable.** `timeline.yOverflow = 'hidden'` is a `@fqqb/timeline` container setting; making the stack scroll while the `TimeRuler` stays pinned is a layout redesign, not a tweak.

Explicitly **not** gaps — both are documented MDB limitations (see "Requirements & limitations" items 2 and 4) with no fix short of new backend retrieval paths: unmapped enum ordinals plotting as NaN gaps, and enum/boolean members inside aggregates getting no labels.


## Findings

**Chart stack**: `ParameterChartTabComponent` (`yamcs-web/.../telemetry/parameters/parameter-chart-tab/`) draws via `PlotBand.ts`, which extends `LinePlot` from the `@fqqb/timeline` canvas library (not d3/Chart.js). Data comes from two sources:
- **Archive**: `yamcsClient.downsampleMean()` → `ParameterValuesApi.getParameterSamples` → `Downsampler.java`, bucketed avg/min/max/n.
- **Realtime**: parameter subscription → `PlotDataSource.processRealtimeDelivery()` → `utils.ts` `convertValueToNumber(value)`.

**Two independent bugs currently block enums, confirmed by reading the code directly:**

1. **Realtime drop** — `convertValueToNumber` (`webapp-sdk/src/lib/utils.ts:145-162`) switches over numeric `Value` types only; `ENUMERATED` (and `BOOLEAN`) fall to `default: return null`, silently deleting the point.
2. **Archive drop (the common-deployment path)** — `Downsampler.java` has *two* entry points:
   - `process(ParameterValue)` (switch at lines 78-102; `ENUMERATED` case at 97-99) — **does** handle `ENUMERATED`, via `value.getSint64Value()` (the ordinal).
   - `accept(ParameterValueArray)` (switch at lines 123-167, the columnar path used whenever the ParameterArchive is enabled — the default in most deployments) — **has no `ENUMERATED` case**, falls to `default:` → `process(timestamps[i], Double.NaN, expireMillis)`. Every archived enum point becomes NaN. Confirmed by direct read.
   - Deeper wrinkle: in the columnar path the enum eng-value arrives as a `String` — the **label**, not the numeric ordinal (`ValueArray.java:116,150` — `ENUMERATED` → `getStringValue()`). So fixing #2 isn't just adding a switch case — it needs a label→ordinal lookup (from the parameter's MDB `EnumValue` list) at read time, since the ordinal isn't in that array at all.
   - The `Sample`/`MeanSample` wire type only has numeric `avg/min/max` fields — no label field, so this stays a numeric-ordinal problem, not a string one.

**Enum data model already available client-side**: `ParameterType.enumValues: EnumValue[]` (`mdb.ts:88`; `EnumValue` interface at `mdb.ts:282`) ships via `getParameter()`. This is the ready-made ordinal↔label table.

**Axis hooks exist but are unused**: `LinePlot` (the `@fqqb/timeline` base class) exposes `axisLabelFormatter` and `hoveredValueLabelFormatter: (value: number) => string` (`LinePlot.d.ts:191-200`), plus `lineStyle` — but **one shared Y-axis per band**, no per-trace axis. Neither formatter is overridden anywhere today; legend value display is `updateLegendValues()` in **`parameter-chart-tab.component.ts:525`** (not `PlotBand.ts`), which reads `this.band.getParameterValue(traceId)` (`PlotBand.ts:279`) and does `item.value.set(String(rtValue))` (`:530`).

**`lineStyle` is already a live user control**: `LineStyle = 'straight' | 'step'` (`@fqqb/timeline`). `TraceConfig.lineStyle: LineStyle` already exists (`TraceConfig.ts:10`), is already wired through the trace form (`parameter-chart-tab.component.ts:218,493,703`, default `'straight'`) and applied in `PlotBand.ts:192,222`. Step rendering already works end-to-end — the enum work only needs to **change the default to `'step'` for enum traces**, not add the option.

**Alternative primitive exists**: `getParameterRanges` (backend + `YamcsClient.getParameterRanges()`) returns non-aggregated `Range[]` (exact value-runs + counts) and already powers a working categorical visualization — `timeline/bands/parameter-states/ParameterStateBand.ts` + `StateLegend.ts`/`ColorMap.ts`/`StateBuffer.ts`. This renders as colored bar segments (an `ItemBand`), not a line plot, and lives in the separate Timeline feature.

**Multi-axis feasibility (researched for the "multiple Y-axis" requirement below)**:
- **Same-grid dual axis** (left+right Y-axis sharing one plot, like a classic dual-axis chart): **not supported** by the installed `@fqqb/timeline@1.6.0`. `LinePlot` (`node_modules/@fqqb/timeline/dist/types/drawables/line-plot/LinePlot.d.ts`) has exactly one `AxisRegion`, one `_minimum`/`_maximum`/`setAxisRange()`. `Line` (`Line.d.ts`) has per-line color/width/fill overrides but no axis-id field. Grep of the minified bundle for `rightAxis`/`secondAxis`/`dualAxis`/`axis2`/`yAxisId` — zero matches. Would require forking/patching the library.
- **Separate grids stacked vertically, one shared time axis** (each grid its own independent `LinePlot`): **already a native, production-proven pattern**. `Timeline` is a multi-band container; any number of `Band` subclasses stack vertically with independent Y-axes but share the timeline's time viewport. Already exercised today by the Timeline "Views" page — `timeline/timeline.component.ts` `switchView()` (method at :287, band construction :307-351) stacks `ParameterPlot` (extends `LinePlot`), `ParameterStateBand`, `ItemBand`, `CommandBand` under one shared scrubber. Bringing this to the Parameter Chart tab is new *application* work, not new *library* work — see "Multi-axis support" below.

## Suggested approach

**Extend the existing line-plot chart (not the state-band pattern).** Decided — see Open Question 4. Rationale: it reuses zoom/pan/URL-persistence/legend/backfill/alarm-lines that already work, and matches what "add chart support" most naturally means to a user of the existing Chart tab. The state-band pattern (Approach B) is not pursued in this feature.

Because ordinal `avg`/`min`/`max` over a bucket is close to meaningless for categorical data (enum ordinal order is arbitrary MDB declaration order, not magnitude — a bucket spanning `OFF=0`/`ON=1` would render as `avg=0.5`, implying a state that never existed), the archive-side fix should switch enum sampling to **last-value-per-bucket** (`min=max=avg=last ordinal seen`) rather than true averaging, and the frontend should default enum traces to `lineStyle: 'step'` with fill disabled.

### Step-by-step

_Steps 1–5 are implemented — see Implementation status. Details below kept for reference._


1. `Downsampler.java` — add label→ordinal resolution (pass a `Map<String,Integer>` built from the parameter's `EnumValue` list into the `Downsampler`; only the columnar `accept(ParameterValueArray)` path needs it — the `process(ParameterValue)` path at lines 97-99 already gets the ordinal directly via `value.getSint64Value()`), add the missing `ENUMERATED` case to the `accept(ParameterValueArray)` switch (lines 123-167; today falls to `default:` → `Double.NaN`), and change categorical sample semantics to last-value-wins instead of averaging.
2. `ParameterValuesApi.java` (`getParameterSamples`, lines 505-566) — there is **no `ParameterType` looked up today** (the type check is commented out, lines 512-519; only `ParameterWithId pid` exists). Add: get the type via `pid.getParameter().getParameterType()`, check it is an enumerated type, build the label→ordinal `Map` from its `EnumValue` list, and pass it into the `Downsampler` (new setter alongside `setUseRawValue`, `:541`).
3. `webapp-sdk/src/lib/utils.ts` — add `ENUMERATED` (and `BOOLEAN`) cases to `convertValueToNumber` (`:145-162`, currently `default: return null`) so realtime values stop being dropped. Consumed at `PlotDataSource.ts:283,293`.
4. `TraceConfig.ts` — add `enumValues?: EnumValue[]` to the interface (`:6-13`), populated once the parameter fetch resolves an enum-typed parameter.
5. Label rendering (three files) — `PlotBand.ts`: set `axisLabelFormatter` / `hoveredValueLabelFormatter` on the plot from the ordinal→label map, default `lineStyle: 'step'` and `fill: false` for enum traces. `parameter-chart-tab.component.ts:525` (`updateLegendValues()`): map `rtValue` through the label table instead of bare `String(rtValue)`. `Legend.ts` / `LegendItem.ts`: carry the formatted label if needed for display.
6. **Multi-band refactor** — *as built* (see "Multi-axis support" below, and the Features 3 + 4 table above): `parameter-chart-tab.component.ts` moves from a single `this.band = new PlotBand(...)` to `PlotBand[]`. No `bandId`/`axisGroup` field was added to `TraceConfig`; the component derives band groups from `orderedTraceIds` plus a `chartLayout` signal. The split is **user-driven via the Overlay/Stacked toggle**, one plot per trace — not automatic by type, and nothing is ever rejected for mixing types.
7. (Optional, low priority) `shared/parameter-input/parameter-input.component.ts` — enum-type badge in the parameter picker so users see it before adding.
8. Tests: Java unit test for `Downsampler`'s new `ENUMERATED` branch + last-value semantics; manual verification with ParameterArchive enabled (the path that's silently broken today); manual verification of multi-band grouping (numeric + enum traces land in separate grids, shared time scrubber stays in sync).

## Multi-axis support (Round 2 — shipped)

**Requirement**: support multiple Y-axes for a chart — either on the same grid or as separate grids.

**Decision: separate grids only.** Same-grid dual-axis is not planned at all — see Open Question 6. Per the feasibility findings above:
- **Separate grids** (multiple `PlotBand`/`LinePlot` instances stacked vertically under one shared `Timeline`, each with its own independent Y-axis) is achievable with **no `@fqqb/timeline` library changes** — the library already supports arbitrary `Band` stacking (proven by the Timeline "Views" page). This is the approach taken.
- **Same-grid dual axis** (e.g. numeric trace on a left axis, enum/second-numeric trace on a right axis, both drawn in one plot area) is **not supported by the library as installed** and would require forking/patching `@fqqb/timeline` (new axis-scale construct, line→axis assignment, right-axis rendering in `Band` internals that aren't designed for more than one axis per band). **Not planned** — separate stacked grids are considered sufficient long-term.

**Shape of the separate-grids change**:
- `ParameterChartTabComponent` (`parameter-chart-tab.component.ts:334`, `this.band = new PlotBand(...)`; `Timeline` created at `:316`) changes from one `this.band` to managing `PlotBand[]`, each added to the same shared `Timeline` instance (pattern mirrors `timeline.component.ts` `switchView()`, band construction at `:307-351`).
- **Grouping (as built, superseding the auto-split-by-type sketch below)**: no field is added to `TraceConfig`. The component holds `orderedTraceIds` and derives the band groups from the layout signal — `[allTraceIds]` in overlay layout, `[[id], [id], …]` in stacked layout. Grouping is therefore one plot per trace, not one plot per type.
- No manual grid-assignment UI. Manual per-trace grid control can be a later enhancement.
- **Two library constraints drove the design** (both verified against `@fqqb/timeline@1.6.0`): band order is insertion order only (`Timeline.add()` is called from the `Drawable` constructor; only `removeChild()` exists), so any composition change means tearing down and recreating every band with the `TimeRuler` last; and `band.label` is drawn by `DefaultSidebar` into the same 75px strip as the Y-axis ticks, so per-plot titles are HTML chips overlaid on the canvas rather than band labels. The teardown-and-recreate pattern mirrors `timeline/timeline.component.ts` `switchView()`.
- **Why the data layer had to move first**: rebuilding a band is only acceptable if it costs no network. `PlotBand` used to own the `PlotDataSource` (realtime WebSocket + all `downsampleMean` requests), a `BackfillingSubscription` and a tooltip overlay — N bands would have meant N of each. These are now component-owned singletons, and because `dataSource.data$` is a `BehaviorSubject` a freshly constructed band receives the current data on subscribe.
- **Direct tie-in to the enum work**: this replaces the earlier caveat that flatly blocks combining numeric + enum traces on one chart (step 6, old plan). Nothing is ever rejected: the user switches to **Stacked** when traces have incompatible scales, and each parameter then gets its own axis — sidestepping the "avg=0.5 of OFF/ON" nonsense from a *different* angle than blocking. The split is user-driven, not automatic by type.

## Caveats

- The archive-path fix (`Downsampler.accept`) is the one non-negotiable piece — without it, enum charts will show flat/empty lines in the common deployment configuration (ParameterArchive enabled), making the feature look broken even though realtime data would work.
- Booleans are handled alongside enums: realtime (`convertValueToNumber`), archive (`Downsampler` BOOLEAN cases + `ParameterValuesApi` `enableCategoricalMode(null)`), and a frontend-synthesized `{0,1}` key from `zeroStringValue`/`oneStringValue`.
- No *same-grid* per-trace Y-axis (left+right dual axis in one plot) exists in the underlying `@fqqb/timeline` library; adding one is a third-party-library-level change and **not planned** (Open Question 6). *Separate* grids (multiple bands, each own axis, shared time scrubber) are already supported by the library and are how mixed numeric+enum (or differently-typed-enum) traces coexist on one chart.
- The line-plot extension (chosen, Approach A) visually implies continuity between discrete states even with `step` styling — accepted tradeoff. The state-band pattern (Approach B) is visually more correct but a bigger, separate investment; not pursued (Open Question 4).

**Stacked layout (Round 2):**

- **Rebuild churn.** Any change to layout or trace order tears down and recreates every band (no reorder API in `@fqqb/timeline`). `syncBands()` compares a composition signature first, so the high-frequency `form.valueChanges` path (colour/width/fill edits) only re-runs `applyTraceConfigs()` and never rebuilds.
- **Y-axis zoom is overlay-only.** `State.minimum`/`maximum` in the URL stay a single-axis concept; they are read and written only when there is exactly one band. Per-plot axis zoom still works interactively but is not persisted. "Reset zoom" resets every plot.
- **Sections are clipped, not scrollable** (`timeline.yOverflow = 'hidden'`). Available height is divided evenly with a 40px floor, so many traces make each section thin.
- **Per-plot titles are HTML chips**, not `band.label` — `DefaultSidebar` draws the label into the same 75px strip as the Y-axis ticks, so a label would overlap them. Offsets are computed from `contentHeight + timeline.bandBorderWidth` (band padding defaults to 0), not read back from `band.y`.
- **Pre-existing bug, fixed 2026-09-08**: `PlotBand.loadData()` gap detection indexed `traceData.points[i - 1]` / `[i + 1]` with `i`, the *trace* index rather than the point index, so gap edges were snapped to `firstTime`/`lastTime` at the wrong samples. The inner loop is now indexed by `j` over a local `samples` array.

## Requirements & limitations for real (non-simulator) MDBs

No packet or container structure changes are needed — an enum parameter is charted like any other. What matters is the parameter *type* definition and where the parameter sits in the MDB.

**Requirements:**

1. **Type** must be `EnumeratedParameterType` with a discrete `<EnumerationList>`, or `BooleanParameterType`. Range-based enumerations (`<Enumeration>` with `maxValue`) are **not supported** — `ParameterValuesApi` only iterates `getValueEnumerationList()` discrete entries, so range enums get no label map.
2. **EnumerationList must cover every ordinal the telemetry can emit.** In the columnar archive path the eng value arrives as a label string; an ordinal with no `<Enumeration>` entry cannot be mapped back and is plotted as a gap (NaN). Partial "only-interesting-states" tables (common in real MDBs) will show gaps for the unnamed values.
3. **Parameter must be archived** — extracted from a received container, Parameter Archive service enabled, and the requested time window backfilled. Newly added params only archive going forward.

**Limitations:**

4. **Scalar top-level parameters only.** Enum/boolean members inside an **aggregate** or **array** parameter (`pid.getPath() != null`) skip the label mapping — the chart shows raw ordinals with no labels and averaged (not last-value) buckets. Real HK aggregate structures hit this.
5. **Engineering value only.** The `raw` toggle plots the plain pre-calibration integer (works, but no labels, no step default, no key overlay). Labels + last-value bucketing + the key overlay apply to the engineering value.
6. **Enum alarm limits are not drawn.** The "Show alarm thresholds" toggle reads `defaultAlarm.staticAlarmRanges`; enum parameters use `enumerationAlarm` (per-label severity), which this chart does not render.
7. **~~One shared Y-axis per chart.~~ Resolved by the Stacked layout.** In the default *Overlay* layout all traces still share one Y-axis, so two enums with different ordinal ranges (e.g. `0–4` and `0–200`) squash each other. Switch the top-control toggle to *Stacked* to give every parameter its own plot and its own Y-axis while keeping one shared time axis. In stacked layout each axis also gets only its own enumeration's labels, instead of the merged ordinal→label table that overlay layout must use.
   Remaining rough edges of the stacked layout: the Y-axis zoom saved in the URL `state` applies to overlay layout only; sections are clipped rather than scrollable, so many traces make each section thin (floor of 40px).
8. **Boolean** — `zeroStringValue`/`oneStringValue` are optional (default `FALSE`/`TRUE`). Requires the `Downsampler` `BOOLEAN` cases and the `BooleanParameterType` branch in `ParameterValuesApi.getParameterSamples` (both in this branch); the frontend synthesizes the `{0,1}` key from the type's zero/one string values.

9. **Legend shows no value over a historical range.** The legend (overlay) and the per-plot chip (stacked) read `latestRealtimeEngValues`, which only fills from the realtime subscription. Viewing a past window shows the parameter name with no value — see "Historical-data review" above.

## Open questions

1. ~~Should raw-value plotting be supported for enum traces?~~ **Resolved, not a decision** — a parameter's raw value type for an enum is a plain integer (`UINT32`/`SINT32`/etc, the pre-calibration bits); it's only the *engineering* value that is `ENUMERATED`. Both `convertValueToNumber` (realtime) and `Downsampler.accept` (archive) already have working integer cases, so raw-value plotting for enum parameters already works today, unaffected by this plan. The `raw`/`engineering` toggle needs no special-casing — leave it generic. This confirms the bug and fix in steps 1-3 are scoped correctly to the `ENUMERATED` (engineering) case only.
2. ~~Is "last value in bucket" the right categorical downsample semantic, or would "mode" be better?~~ **Resolved: last-value, no mode.** `Downsampler.Sample` (`Downsampler.java:223-274`) is a one-pass streaming reducer — last-value is a near-free change (overwrite `avg=min=max=value` per call, skip the rolling-avg math). Mode would need a per-`Sample` frequency map + argmax/tie-break at `collect()`, real added complexity. Last-value also matches the recommended step-line rendering (the line should show "state as of this boundary," not "state that dominated this window" — the latter can visually contradict its own right edge). Mode's actual benefit — not losing brief blips — is already covered by the existing `getParameterRanges`/`ParameterStateBand` alternative noted in Findings, which preserves every transition exactly; that's the right tool when transition-completeness matters, not the downsampled line chart.
3. ~~Should two different enum parameters (different `EnumValue` tables) ever be allowed on the same chart if a user explicitly wants to compare state transitions, even though the axis labels would conflict?~~ **Resolved: allow it.** Treat it like adjacent differently-colored plots/points sharing an axis — don't block it, let the user make whatever sense of it they want.
4. ~~Priority: is the line-plot extension (Approach A) or the state-band (Approach B) the real target UX?~~ **Resolved: Approach A** — extend the existing line-plot chart with step-line rendering for enum traces. Approach B (categorical state band) is not pursued in this round.
5. ~~Default grid-assignment UX for multi-axis?~~ **Superseded.** Originally resolved as "auto-split by type". The shipped behaviour is instead a user-driven **Overlay / Stacked** toggle: overlay puts every trace in one plot (the previous behaviour), stacked gives every trace its own plot and Y-axis. No automatic type-based grouping, and still no manual per-trace grid-assignment control.
6. ~~Should same-grid dual-axis (left/right) be scoped as a real future item?~~ **Resolved: no.** Separate stacked grids (each own Y-axis, shared time axis) is sufficient long-term. Same-grid dual-axis is not planned; no `@fqqb/timeline` fork.

## Verification (once implemented)

- Unit tests: `Downsampler` categorical branch (new Java test class or extend existing `DownsamplerTest` if present).
- Manual: open Parameter → Chart tab for an enum-typed parameter (a) with ParameterArchive enabled, confirm archive history renders with labeled Y-axis and step line instead of NaN/empty; (b) watch realtime updates append correctly; (c) hover tooltip and legend show labels, not raw ordinals.

### Stacked layout (Features 3 + 4)

1. Chart `/SIMULATOR/EnumTest_State`, then add `/SIMULATOR/EnumTest_Sparse` (ordinals 0–200) and `/SIMULATOR/BatteryVoltage1` as traces 2 and 3.
2. **Overlay**: identical to before this change — one plot, one axis, merged enum key overlay, single legend, tooltip listing all traces, alarm thresholds.
3. **Stacked**: three sections, each with its own Y-axis (`EnumTest_State` labelled `OFF/ON/STANDBY/…`, `EnumTest_Sparse` its own labels, `BatteryVoltage1` in volts — the sparse enum no longer squashes the others). Check that pan/zoom/scrubber, the `1m`/`15m`/`Date range` buttons and autoscroll move all three together; that hovering shows a tooltip with only that section's trace while the `MouseTracker` crosshair crosses all sections; that each section carries its own legend chip with the live value; and that alarm-threshold hlines appear only on the section for the URL's parameter.
4. Toggle back and forth and add/remove/reorder traces while stacked: DevTools **Network** must show **no new** `samples` requests on toggle or reorder, and **WS** must still show exactly **one** parameter subscription.
5. Reload the page: it must come back in **overlay** layout (deliberately not persisted), with time range and traces still restored from the URL.

### Dedicated test parameters in the PUS simulator

Added HK struct id 5 (TM[3,25], `hkid==5`), emitted once per second, enabled by default. Source:
`Pus3Service.fillEnumTest()` + `landing.xml` `EnumTest` container / `EnumTest*Type` types.

| Parameter | Type | Pattern | Exercises |
| --------- | ---- | ------- | --------- |
| `/SIMULATOR/EnumTest_State` | enum `OFF=0 ON=1 STANDBY=2 SAFE=3 FAULT=4` | ordinal `c % 5` | dense ordinals incl. 0, plain step line, legend/tooltip labels |
| `/SIMULATOR/EnumTest_Sparse` | enum `IDLE=0 ARMED=3 ACTIVE=7 DEGRADED=25 CRITICAL=100 LOST=200` | cycles the 6 ordinals | non-monotonic/sparse ordinals, label→ordinal map, half-ordinal Y-tick edge case |
| `/SIMULATOR/EnumTest_Partial` | enum `NOMINAL=10 WARNING=20 ALARM=30` | 10/20/30, but every 4th sample emits ordinal `99` (absent from table) | unmapped-ordinal handling (archive `Downsampler` → `NaN`; realtime → bare number) |
| `/SIMULATOR/EnumTest_Flag` | boolean | toggles every sample | BOOLEAN realtime + archive path (`convertValueToNumber`, `Downsampler` BOOLEAN cases, `ParameterValuesApi` boolean categorical), synthesized `FALSE`/`TRUE` key from `zeroStringValue`/`oneStringValue` |

Test flow: start the `pus` example, chart each param, let ~2 min of archive accumulate, then widen the range to hit the columnar `Downsampler.accept()` path. `EnumTest_State` + a numeric param (e.g. `/SIMULATOR/BatteryVoltage1`) together check the multi-grid split (Feature 2).

PUS5 `event_id` is also enum-typed and valid, but only emits ordinals 1/2 unless events are enabled via TC[5,5] and PMON limits are breached — the dedicated params above are preferred.
