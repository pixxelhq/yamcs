# Link Management End to End: Yamcs fork + ff-mcs (FireflyMCS) + wds-backend

_Analysis date: 2026-10-07. Repos: `yamcs-Pixxel-fork` (branch `pixxel/5.10.x` @ `09f566339`, release `v5.10.12-Pixxel-9.4.2`), `ff-mcs` (`b5d2a61`), `wds-backend` (`98d732b`). All Yamcs line numbers below are from that release commit._

## Context
How link management works end to end across MPS (pass planning), `wds-backend` (pass-driven automation), `ff-mcs` (the FireflyMCS application) and `yamcs-Pixxel-fork` (the framework). §1 defines the layers; §2–§3b go layer by layer. WDS claims were cross-checked against its author's design notes (Appendix A0). This document explains it and proposes no code changes. Read §0 first: it changes which source files actually apply.

## 0. Version caveat
- ff-mcs pins Yamcs to **`5.10.12-Pixxel-9.4.2`** (`ff-mcs/pom.xml:15`). That is HEAD of branch `pixxel/5.10.x` (commit `09f566339`, "Prepare for release v5.10.12-Pixxel-9.4.2"). This document is checked against that commit.
- Master-line features (`DataLinkCompositionResolver`, `ComposedTm|TcFrameLink`, SRS4) **do not exist** on this branch.
- The frame handling that matters to ff-mcs (SRS3 CRC → AES-GCM decrypt → SCID check → redirection) is `AbstractTmFrameLink.handleFrame` (`tctm/ccsds/AbstractTmFrameLink.java:171-257`; SRS3 config parsed at :108-145).

---

## 1. Layers and ownership

Five layers, top to bottom. Each layer answers one question and talks only to the layer below it (plus the operator side path).

| Layer | Component | Answers | Repo / owner | Runs as |
|---|---|---|---|---|
| **L1 Plan** | MPS (Mission Planning Service) | *Which passes are booked?* (sat, antenna, provider, AOS/LOS, BOOKED/CANCELLED) | not in these repos; only its `/passes` contract is known (from WDS) | separate service |
| **L2 Orchestrate** | WDS (`wds-backend`) | *When should a link be on or off?* Turns passes into timed tasks | `wds-backend` | Python container, port 9090, one instance |
| **L3 Mission app** | ff-mcs (FireflyMCS) | *Which links exist, and how do they talk to each station?* `dataLinks:` config + custom link classes | `ff-mcs` | jar **inside** the Yamcs JVM; 6 Yamcs servers (FFLY01-03 × primary/secondary) |
| **L4 Framework** | Yamcs fork `5.10.12-Pixxel-9.4.2` | *How is any link created, switched, persisted, exposed and routed?* `LinkManager`, REST/WS API, UI, memento, streams | `yamcs-Pixxel-fork` @ `pixxel/5.10.x` | the Yamcs JVM itself |
| **L5 Ground segment** | KSAT stations, KSAT KOGS API, LeafSpace MQTT broker | *Where do the bytes go?* | external | external |

Side paths that are **not** part of the link-management chain:
- **Operator:** a human in the Yamcs web UI (L4) can enable or disable any link directly, bypassing L1–L2. WDS will override that at the next ON/OFF task.
- **Downstream consumers:** PLDP, APS and FOS receive uploads from WDS after LOS. They don't touch links.
- **Direct WDS → L5 calls:** KSAT modem bitrate change (`:8079/mc/v1/lr/…`) and LeafSpace preset change. These are manual WDS endpoints and don't go through Yamcs.

### Layer diagram
```
 L1 PLAN          ┌──────────────────────┐
                  │ MPS  GET /passes     │  bookings (sat, antenna, AOS/LOS, status)
                  └──────────┬───────────┘
                             │ HTTP poll every 30 s (WDS pulls)
 L2 ORCHESTRATE   ┌──────────▼───────────┐            ┌──────────────┐
                  │ WDS  gs_passes →     │            │ Operator     │
                  │ relative_tasks →     │            │ (browser)    │
                  │ LINK_MANAGEMENT_ON/OFF│           └──────┬───────┘
                  └──────────┬───────────┘                   │
                             │ yamcs-client REST              │ web UI
                             │ POST /api/links/{inst}/{link}:enable|:disable
 ════════════════════════════╪═══════ Yamcs server (×6) ══════╪═════════════
 L4 FRAMEWORK     ┌──────────▼────────────────────────────────▼───────┐
                  │ LinksApi (ReadLinks/ControlLinks, audit)           │
                  │ LinkManager (create, enable/disable, memento,      │
                  │   stream wiring, TcStreamSubscriber)               │
                  │ AbstractLink / AbstractTmFrameLink.handleFrame      │
                  └──────────┬────────────────────────────────────────┘
                             │ Java subclassing + dataLinks: config
 L3 MISSION APP   ┌──────────▼────────────────────────────────────────┐
                  │ ff-mcs: <ST>_Tm  TcpTmFrameLink                    │
                  │         <ST>_Tc  TcpTcFrameLink   ┐ TcpTcMultiplexer│
                  │         <ST>_Cfdp TcpCimTcFrameLink┘ (1 socket/ant) │
                  │         <ST>_Monitoring WebsocketsParameterDataLink │
                  │         LeafSpace Mqtt* links                       │
                  └──────────┬────────────────────────────────────────┘
 ════════════════════════════╪═══════════════════════════════════════════
 L5 GROUND        TCP :56030 (TM), TCP :51103 (TC+CFDP), WS :8079 (monitoring),
                  MQTT mqtt.leaf.space:1883, HTTPS KOGS API
```
L3 sits *below* L4 in the diagram because ff-mcs classes are plugins that L4 instantiates and calls. They share one process; the boundary is a Java API, not a network hop.

### Interfaces between layers
| From → To | Mechanism | What crosses | Defined in |
|---|---|---|---|
| L1 → L2 | HTTP GET, WDS pulls every 30 s | pass list, now-1 h … now+48 h, BOOKED + CANCELLED | `wds-backend/app/schedule.py:243-270` |
| L2 → L4 | REST via `yamcs-client` | `:enable` / `:disable` per link name, per Yamcs server | `wds-backend/app/utils/mcs.py:113-188` |
| Operator → L4 | Yamcs web UI → same REST | same calls, audited the same way | `LinksApi.java`, `links.proto` |
| L4 → L3 | Java: `Link.init/doEnable/doDisable/connectionStatus`, `setTmSink`, `sendCommand` | lifecycle and data | `tctm/Link.java`, `tctm/AbstractLink.java` |
| L3 → L5 | TCP / WS / MQTT / HTTPS | frames, CLTUs, monitoring JSON | ff-mcs `src/main/java/space/pixxel/FireflyMCS/` |

### Who owns which decision
| Decision | Owner layer | Where |
|---|---|---|
| A pass exists for antenna X | L1 MPS | MPS (not inspected) |
| Turn X's links on at AOS+1 s, off at LOS+1 s | L2 WDS | `schedule.py:331-454` |
| TC goes only through the primary MCS | L2 WDS | `mcs.py:175` |
| Link names (`HA2_Tm` …) and the station list | L3 ff-mcs config, **copied by hand** into L2 `config.ANTENNAS` | instance YAML `dataLinks:`, `config.py:295` |
| Links start disabled after restart | L3 config (`enabledAtStartup: false`) over the L4 memento | instance YAML; `LinkManager.java:139-153` |
| Which enabled TC link gets a command | L4 (first enabled link in config order) | `LinkManager.java:502` |
| Reconnect, framing, encryption | L3 classes on top of L4 `handleFrame` | ff-mcs `TcpTmFrameLink`, `AbstractTmFrameLink.java:171-257` |
| Link status visibility | L4 (1 s poll; no events) | `LinksApi.java:77-83` |

## 2. L4 Framework: Yamcs core (`../../yamcs-core/src/main/java/org/yamcs`)

### Link contract (`tctm/Link.java`)
- Status values: `OK / UNAVAIL / DISABLED / FAILED` (:18-36).
- Methods: `enable/disable/isDisabled`, `isEffectivelyDisabled()` (true if this link or a parent is disabled), counters, `getExtraInfo`, `getDetailedStatus`, `setupSystemParameters`.
- Common config keys (`getDefaultSpec` :158-175): `name, class, stream|tmStream|tcStream|ppStream, enabledAtStartup, invalidPackets`.
- Sub-interfaces: `TmPacketDataLink.setTmSink`, `TcDataLink.sendCommand`, `ParameterDataLink.setParameterSink`, `AggregatedDataLink.getSubLinks`.

### `AbstractLink` (`tctm/AbstractLink.java`)
- **Status:** `getLinkStatus()` (:86) returns DISABLED if disabled, FAILED if the service failed, otherwise the subclass's `connectionStatus()`.
- **Enable/disable:** `enable()/disable()` (:110/:123) flip an atomic flag and call `doEnable()/doDisable()`. If that throws, the flag is rolled back.
- **Per link:** an `EventProducer`, `LinkAction`s (`addAction` :215), and system parameters `linkStatus, dataIn/OutCount, dataIn/OutRate`. These are published under `/yamcs/<serverId>/links/<name>/…`; the namespace is the server id, not the instance.

### `LinkManager` (`management/LinkManager.java`)
1. **Constructor** (:87): reads `dataLinks:`, loads the saved state (`yamcs.links` memento, key at :70; loaded :97-99), then calls `createDataLink` (:110) for each entry (:103). That loads the class, validates it and calls `init()`.
2. **Startup state** (:139-153): explicit `enabledAtStartup` wins; otherwise the memento; otherwise enabled. A disabled link gets `link.disable()` before wiring.
3. **`configureDataLink`** (:165) wires the streams:
   - **TM:** `setTmSink` → `processTmPacket` (:249) applies the invalid-packet policy and emits a TM tuple (the link name is one of the columns) on the `tmStream`.
   - **TC:** one `TcStreamSubscriber` per TC stream (:502).
   - **PP:** a `StreamPbParameterSender`.
   - **Aggregated:** recurses into sub-links.
4. **`startLinks()`** (:304) registers system parameters, then `startAsync()`. In `doStart`, a link calls `doEnable()` if it is enabled.
5. **`enableLink/disableLink`** (:402/:408) call the link, then `saveMemento()` (:419). The enable/disable state **persists across restarts**.
6. **No status watchdog.** `LinkListener` only reports links being added or removed. Status is *pulled* every 1 s, by the `links` websocket and by `SystemParametersService`. A dropped connection raises no event.

### API (`LinksApi.java` + `links.proto`)
| Call | Route | Privilege |
|---|---|---|
| ListLinks / GetLink | `GET /api/links/{inst}[/{link}]` | ReadLinks |
| EnableLink / DisableLink | `POST …/{link}:enable` / `:disable` | ControlLinks (audited) |
| ResetLinkCounters | `POST …:resetCounters` | ControlLinks (not audited) |
| RunAction | `POST …/{link}/actions/{action}` | ControlLinks (audited) |
| SubscribeLinks | WS topic `links`, full snapshot every 1 s (`LinksApi.java:77-83`) | ReadLinks |

Auditing comes from the `log:` option on the RPC in `links.proto` (:34, :42, :68), written by `RouteHandler.java:238`. `LinksApi` itself never calls the audit log.

### Web UI (`yamcs-web/.../projects/webapp/src/app/links/`)
- Routes: `/links` (list), `/links/log` (audit records from `LinksApi`), `/links/:link` (detail).
- Enable, Disable and Reset buttons appear only with ControlLinks. Link actions open `LinkActionDialogComponent` (`shared/link.service.ts`).
- The Pixxel fork has **no changes** to `LinksApi`, `links.proto`, `LinkManager`, `Link` or `AbstractLink` beyond upstream merges.
- **Two fork UI changes** (since `yamcs-5.10.12`):
  - `a35426578`: sub-links (links with a `parentName`) get no checkbox and no Enable/Disable buttons in the list or detail page. The REST API still allows it.
  - `b72141345`: `extraInfo` values starting with `http://` render as hyperlinks. This is how the IQ/spectrum URLs from ff-mcs `TcpTmFrameLink` become clickable.

## 3. L3 Mission app: ff-mcs (FireflyMCS)

### Instances
- There are six instance files: `src/main/yamcs/etc/yamcs.FFLY0{1,2,3}-{primary,secondary}.yaml`. The instance is chosen with `${env.INSTANCE}` in `yamcs.yaml:23`.
- Primary and secondary have the same links and differ only in replication (master vs slave).
- Between satellites, only the spacecraft IDs differ (`spacecraftIdSrs` FFB1/FFB2…, the KMS id, the monitoring id).

### `dataLinks:` (starts at line ~1095): 204 links, **all `enabledAtStartup: false`**
- **KSAT:** 50 stations × 4 links (HA2 template at lines 1096-1342):

| Link | Class | Endpoint | Role |
|---|---|---|---|
| `<ST>_Tm` | `TcpTmFrameLink` (custom) | host:**56030** | TM frames (KSAT T501 + SRS3 + AES-GCM), VC routing |
| `<ST>_Tc` | `TcpTcFrameLink` (custom) | host:**51103** | TC frames: VC0 `tc_obc`, VC1 `tc_arbitrator`, VC2 `tc_payload` |
| `<ST>_Cfdp` | `TcpCimTcFrameLink` (custom) | host:**51103** | CFDP uplink from `tc_cfdp` |
| `<ST>_Monitoring` | `WebsocketsParameterDataLink` (custom) | `ws://host:8079/receive` | Ground station RF metrics → `/KSAT/*` |

- **LeafSpace:** 4 links (lines 13486-13694): `MqttTm/Tc/CimTc FrameLink` and `MqttParameterDataLink` on `tcp://mqtt.leaf.space:1883`, topics `<norad>/downlink | uplink | monitoring/#`.

### Custom Java (`src/main/java/space/pixxel/FireflyMCS/`)
- **`TcpTmFrameLink`:**
  - TCP client with a 3 s connect timeout.
  - Parses the T501 header (ERT and length in bits), then calls the fork's `handleFrame`.
  - Reconnects every 10 s.
  - `getExtraInfo` adds frame counters (valid, invalid, cross-satellite) and IQ/spectrum URLs at `host:8008`.
- **`KsatPacketInputStream`:** reads frames by their 4-byte length prefix. Oversized frames are **silently dropped**.
- **`TcpTcFrameLink.addNonsense()`:** RS → CLTU → ASM → optional SatLab pre/postamble → KSAT T501 header. Sends with 5 retries × 2 s.
- **`TcpTcMultiplexer`:** a static map keyed by **antenna**, so `<ST>_Tc` and `<ST>_Cfdp` share **one socket** to :51103. The socket is reference-counted and closes when the last link releases it.
- **`WebsocketsParameterDataLink`:**
  - Looks up each new `ksat_id` in the KSAT KOGS API and drops metrics for other spacecraft.
  - Publishes `/KSAT/<key>` with a 1 s expiry.
- **Nothing in ff-mcs itself automates link management (see §3b: wds-backend does).** No code calls `LinkManager`, defines link actions or switches links by pass. Operators enable a station's links by hand in the UI, and that state persists in the memento.

### Streams and wiring (instance yaml `streamConfig` ~13714, `processor.yaml`, `extra_streams.sql`)
**TM:**
```
<ST>_Tm → handleFrame (CRC → decrypt → SCID check → redirect)
   ├─ byte[4]==0x06 → tm_cfdp → cfdp_in → CfdpService
   └─ VC0 tm_arbitrator_realtime | VC1 tm_stored | VC4 tm_idle
      | VC5 tm_obc_realtime | VC6 tm_payload_realtime
        (PusPacketPreprocessor; invalid packets → invalid_tm_stream)
   → PusTmManager → p_tm_* → StreamTmPacketProvider → realtime processor + archive
```
**TC:**
```
command → command-queue (by significance) → StreamTcCommandReleaser
   → p_tc_{obc|arbitrator|payload}   (chosen by tcPatterns on the command path)
   → PusTcManager → tc_{obc|arbitrator|payload}
   → LinkManager.TcStreamSubscriber → first enabled link on that stream that accepts it
   → <ST>_Tc VC (PusCommandPostprocessor, SRS3 CSP + GCM) → addNonsense
   → TcpTcMultiplexer[antenna] → host:51103
```
**CFDP up:** `CfdpService → cfdp_out → tc_cfdp → <ST>_Cfdp → same socket :51103`

### Legacy (not used by current config)
- **Python helpers:** `simulator.py` (UDP 10015/10025, quickstart leftover), `zmp_tc_router.py` and `tcp_zmq_router.py` (TCP/ZMQ :6000 → PMT → GNU Radio :5556).
- **Java classes:** `ZeroMq*`, `Udp*` and `XbtUdpCfdpLink`.

## 3b. L2 Orchestrate: wds-backend (`/Users/nikhil/GSS Workspace/wds-backend/app/`)
Correction to §3: ff-mcs itself has no link automation, but **wds-backend switches links on and off automatically from the pass schedule**.

**Stack:**
- FastAPI on :9090 and SQLite (`db/wds.db`).
- Scheduling uses `threading.Timer` loops (`schedule.py:20-49`) and a 50-worker thread pool.
- The schedulers run only if `SERVER == "PRIMARY"` (`worker.py:28`). The default is lowercase `"primary"`, so `.env` must set it in uppercase.

**Pipeline:**
1. **Every 30 s:** `PassUpdateManager.reload` (`schedule.py:292`) GETs `{MPS_SERVICE}/passes` (now-1h…now+48h, BOOKED and CANCELLED) and upserts `gs_passes`.
2. **New BOOKED pass:** `create_task_for_pass` (`schedule.py:331`) inserts 6 `relative_tasks` rows:
   - `LINK_MANAGEMENT_ON @ aos+1` and `LINK_MANAGEMENT_OFF @ los+1`. Commit 4c3b57b changed these from aos-120 and los+60.
   - `REBUILD los+90` and the uploads at los+210/240/300.
3. **Every 2 s:** `RelativeTaskManager.reload` (`schedule.py:185`) loads PENDING tasks whose pass is still BOOKED (`repository.py:117`) and evaluates each equation. It submits the task only if `now-10s < t <= now`; otherwise the task stays PENDING forever, with no catch-up.
   - State flow: created `PENDING` → manager sets `SENT_TO_POOL` → pool sets `RECEIVED_BY_POOL` → `COMPLETED` / `FAILED`. `RUNNING` exists in the enum but is never set.
4. **ON → `McsActivities.enable_link`** (`utils/mcs.py:264`), on **both** the primary and secondary Yamcs of that satellite, using the `yamcs-client` library (`get_link().enable_link()`, which calls the REST `:enable`):
   - `disable_non_pass_links()` (`mcs.py:113`): disables all 4 links of every antenna not currently in a pass, per the `gs_passes` SQL.
   - Enables `<antenna>_Tm` and `_Monitoring` on both instances.
   - **Tc/Cfdp:** never enabled on secondary. On primary they are enabled only if `avoid_antennas` (the antennas in a pass) is empty (`mcs.py:165-188`).
5. **OFF → `disable_link`** (`mcs.py:300`): disables all 4 links of that pass's antenna on both instances.

**Antenna list:** `config.ANTENNAS` (`config.py:295`) maps each antenna to the links `{ant}_Tm/_Tc/_Cfdp/_Monitoring`. It must match the ff-mcs YAML by hand.

**Confirmed issues (I read the code):**
1. **Primary TC is never enabled.** At `aos+1` the pass's own antenna already satisfies `aos<=now<=los`, so `avoid_antennas` is never empty (`mcs.py:175`). With the old aos-120 timing, only *other* antennas could block TC.
2. **`HA7` is in `ANTENNAS` (commit 98d732b) but has no `HA7_*` links in any ff-mcs instance YAML.** Every ON task gets 404s trying to disable it, and an HA7 pass would get no links.
3. **Default instance names are wrong:** `FF01-primary` vs ff-mcs `FFLY01-primary` (`config.py:188`). Correct only if `.env` overrides them; I didn't check `.env`.
4. **Cancelled passes still count as "in pass"** for link decisions: `get_antennas_in_gs_pass` has no `status = 'BOOKED'` filter (`mcs.py:147-154`), unlike `get_current_passes` (`repository.py:257`). A cancelled pass on another antenna blocks primary TC and keeps its links from being disabled.

**Other risks (reported by search, not verified):**
- Tasks are marked COMPLETED even when every Yamcs call failed.
- Back-to-back passes on the same antenna, or overlapping LeafSpace passes, can disable links still in use.
- Passwords are hardcoded as defaults in `config.py`.

## 4. Five scenarios, step by step
- **(a) Startup:**
  1. `YamcsServerInstance.doInit` creates `LinkManager` (:144).
  2. All 204 links are created disabled, unless the memento says otherwise, since there is an explicit `false`.
  3. `startLinks()` (:156) starts the services; disabled links open no sockets.
- **(b) Operator enables HA2_Tm:**
  1. The UI sends `POST /api/links/FFLY01-primary/HA2_Tm:enable` → `LinksApi` → `LinkManager.enableLink`.
  2. `doEnable` opens TCP :56030. The state is saved to the memento and an audit record is written.
  3. The UI shows OK within 1 s.
- **(c) KSAT connection drops:**
  1. An `IOException` is logged, the socket is set to null, and a reconnect is tried every 10 s.
  2. `connectionStatus` = UNAVAIL. No event or alarm fires; the UI and `/yamcs/<server>/links/HA2_Tm/linkStatus` pick it up on the 1 s poll.
- **(d) TM frame arrives:** follow the TM chain in §3.
- **(e) Command sent:** follow the TC chain in §3.
  - If no enabled TC link exists on the stream → `Acknowledge_Sent` NOK.
  - If several stations' TC links are enabled at once, the **first enabled link in config order** gets the command.

## 5. Gaps worth knowing (not proposing fixes)
1. Link status changes raise no events or alarms. An alarm would need an MDB alarm on `/yamcs/<server>/links/*/linkStatus`.
2. When several stations are enabled, TC routing is "first enabled link wins" by config order.
3. `TcpTcMultiplexer` is keyed only by antenna, so instances sharing one JVM would share sockets.
4. `WebsocketsParameterDataLink` can throw an NPE when KOGS returns non-200 (around line 252).
5. CFDP redirection tuples are labelled with the link name `Ff_Sbt_Cfdp_Down`, which no longer exists. The name comes from ff-mcs config (`redirection[].link`, e.g. `yamcs.FFLY01-primary.yaml:10155`), not from Yamcs: `handleFrame` uses the configured name if set, else the real link name (`AbstractTmFrameLink.java:231`). Removing `link:` from the config would label them with the real `<ST>_Tm` link.

## Verification
To confirm any of the above in a running ff-mcs:
1. Run `INSTANCE=FFLY01-primary ./mvnw yamcs:run`.
2. Open `http://localhost:8090/links` and enable a station's links.
3. Run `curl localhost:8090/api/links/FFLY01-primary` to see status and counters.
4. Toggle a link and check `/links/log` for the audit record.

---

## Appendix A: wds-backend in detail (`/Users/nikhil/GSS Workspace/wds-backend/app/`)

### A0. Cross-check with the author's design notes (received 2026-10-07)
| Author's claim | Code says | Verdict |
|---|---|---|
| WDS is a generic task framework; tasks are relative to an event or absolute | `relative_tasks` (event = a `gs_passes` row) and `absolute_tasks` tables | ✅ matches |
| Primary purpose: enable/disable a spacecraft's MCS TMTC links relative to its GS pass | `LINK_MANAGEMENT_ON/OFF` per pass | ✅ matches, but primary TC is never enabled in practice (A6 risk 1) |
| Passes fetched from MPS for the **next 72 hours** | `now - 1 h … now + 48 h` (`schedule.py:247-248`) | ❌ **48 h**, not 72 h |
| 3 task managers, reload every 2 s (configurable) | Absolute + Relative every 2 s, PassUpdate every 30 s (`config.py:5-7`, `worker.py:20-35`). A 4th class, `VpnKeepAliveManager` (`schedule.py:212`), exists but is not started | ✅ (pass manager is 30 s, as the notes say later) |
| Managers pick up tasks and "update the status to PENDING" | Tasks are **created** PENDING. The manager sets `SENT_TO_POOL`, the pool sets `RECEIVED_BY_POOL`, then `COMPLETED`/`FAILED` | ❌ wording; the notes themselves later say SENT_TO_POOL |
| Managers scan for PENDING tasks and submit them | Only tasks due within the last **10 s** are submitted (`schedule.py:163,197`); older PENDING tasks are skipped forever | ⚠️ missing caveat |
| Pass manager "creates a task for every new pass" | Creates **6** tasks per new BOOKED pass (`schedule.py:331-454`) | ⚠️ incomplete |
| Pass manager cancels the tasks of cancelled passes | `cancel_task_for_pass` (`schedule.py:324,460-462`); also the relative manager only runs tasks whose pass is BOOKED (`repository.py:117`) | ✅ matches |
| Task types: link management, MCS rebuild, MCS reconciliation, image table upload, downlink file upload, telemetry upload | `TaskType` enum (`models.py:62-72`) has 10 values. Link management is split into `_ON`/`_OFF`. `RECONCILLIATION` is **absolute-only** (manual; dumps and loads `tm` + `cmdhist` between primary and secondary, `mcs.py:250-262`), not per pass. `FOS_DATA_SYNC` (absolute) is dispatched but not in the notes. `PASS_SHEET` and `INFLUX_DB` are defined but never dispatched | ⚠️ list is incomplete |
| "Other features that require infra support" | `routes/monitoring.py` (VM/service health), KSAT bitrate and LeafSpace preset endpoints (`routes/tasks.py`) | ✅ matches |

Not covered by the notes but relevant to links: the TC-only-on-primary rule, `disable_non_pass_links`, the hand-maintained `ANTENNAS` list, and that each per-pass task type has an on/off flag (`config.py:8-11`).

### A1. Deployment and config
- **Container:** `Dockerfile` uses python:3.12-slim plus Flyway. `start.sh` runs `setup_db.py` and then `uvicorn worker:app --port 9090`.
- **Compose:** `docker-compose.yml` has a single service (`.env`, port 9090, volumes `/wds/db` and `./app`). There is no Redis or Celery.
- **Startup gate:** the schedulers and Flyway start only if `config.SERVER == "PRIMARY"` (`worker.py:28`).
- **MCS connections:** `MCS_FF0{1,2,3}_{PRIMARY,SECONDARY}_{USERNAME,PASSWORD,SERVER,PORT,INSTANCE}` (`config.py:184-232`).
  - Default hosts: 192.168.10.202-204 (primary) and .206-.208 (secondary).
  - Default ports: 8091-8093.
- **Other settings:**
  - `ACTIVE_SATELLITES` (`config.py:292`)
  - `MPS_SERVICE` / `MPS_PASSES_API` (`config.py:18-19`)
  - Pritunl proxies (`config.py:240-244`)
  - `LINK_MANAGEMENT_TASK_ENABLE` (`config.py:8`)
  - Pass fetch every 30 s; task poll every 2 s.

### A2. Tasks created per BOOKED pass (`schedule.py:331-454`)
| task_type | equation |
|---|---|
| LINK_MANAGEMENT_ON | `aos+1` (was `aos - 120` before 4c3b57b) |
| LINK_MANAGEMENT_OFF | `los+1` (was `los + 60`) |
| REBUILD | `los + 90` (parameter archive rebuild, aos-10min … los+10min, `mcs.py:328-339`) |
| TELEMETRY_UPLOAD | `los + 210` |
| DOWNLINK_FILE_UPLOAD | `los + 240` |
| IMAGE_TABLE_UPLOAD | `los + 300` |

Task states (`models.py:53-60`): PENDING → SENT_TO_POOL → RECEIVED_BY_POOL → COMPLETED / FAILED / CANCELLED.

### A3. Pass-change handling (`PassUpdateManager.reload`, `schedule.py:292-329`)
- **New pass:** upserted. If BOOKED, `create_task_for_pass` runs.
- **Modified pass** (aos/los/status/antenna/sat/provider_id/jira_id/pass_no):
  - CANCELLED → `cancel_task_for_pass` cancels all its tasks.
  - Otherwise → `update_task_for_pass`, which only rewrites targets if `sat` changed.
- **AOS/LOS edits** need no task update. Execution time is computed live from the joined `gs_passes` row.

### A4. Endpoints and DB
- **`routes/tasks.py`:**
  - `POST /absolute-task/run-now|run-later`
  - `POST /relative-task/run-later`: manual link task with a custom equation; `event_id` must be an existing pass
  - `POST /relative-task/cancel`
  - `GET /tasks/get-all`
  - `POST /actions/bitrate-change`: KSAT modem `:8079/mc/v1/lr/...`
  - `POST /actions/preset-change`: LeafSpace
- **`routes/passes.py`:** `GET /passes/{sat}/{start}/{end}`, `GET /passes/current/{sat}`.
- **No direct link enable/disable/status endpoint.**
- **Tables:** `relative_tasks`, `absolute_tasks`, `gs_passes` (`schema.sql`, `db_scripts/V1.0.0-2`).

### A5. Timeline of one pass (FF01, KSAT HA2)
1. **Up to 48 h before:** the 30 s poll inserts the pass and its 6 tasks.
2. **Before AOS:** edits are upserted. A cancellation marks the tasks CANCELLED.
3. **AOS+1 s** (plus up to 2 s poll jitter), on FF01 primary and secondary:
   - Disable the links of every antenna not in a pass (~200 calls).
   - Enable `HA2_Tm` and `HA2_Monitoring`.
   - On primary only, enable `HA2_Tc` and `HA2_Cfdp` if no antenna is in a pass. That is never true at AOS+1 (see risk 1).
4. **LOS+1 s:** disable `HA2_{Tm,Tc,Cfdp,Monitoring}` on both instances.
5. **LOS+90 / 210 / 240 / 300 s:** archive rebuild, then the uploads to PLDP/APS.

### A6. Risks
**Verified in code:**
1. **Primary never enables TC since 4c3b57b.** At `aos+1` the pass's own antenna is in `avoid_antennas` (`mcs.py:128,175`). The timestamp formats stored by `repository.py:235` and queried in `mcs.py:153` compare correctly as strings, so the pass's own antenna is found as in-pass.
2. **`HA7` mismatch.** It is in `ANTENNAS` (`config.py:295`, commit 98d732b) but no ff-mcs instance YAML has `HA7_*` links.
3. **Instance names.** wds-backend defaults to `FF0x-primary`; ff-mcs uses `FFLY0x-primary`. It works only if `.env` overrides the names (`.env` was not checked).
4. **`SERVER` case.** The default is `"primary"`, but the gate checks `"PRIMARY"`, so the scheduler is off unless `.env` sets it.
5. **Cancelled passes still count as in-pass:** there is no `status='BOOKED'` filter in `get_antennas_in_gs_pass` (`mcs.py:147-154`). Verified 2026-10-07.
6. **Absolute tasks never reach a terminal state on success.** `FOS_DATA_SYNC` and `RECONCILLIATION` set `RECEIVED_BY_POOL` and never `COMPLETED` (`schedule.py:60-73`).
7. **Disabled task types get stuck.** If a feature flag (`LINK_MANAGEMENT_TASK_ENABLE`, `PLDP_TASK_ENABLE`, `MCS_REBUILD_TASK_ENABLE`, `IMAGE_TABLE_TASK_ENABLE`, `config.py:8-11`) is off, the task falls through the dispatch chain and stays `SENT_TO_POOL`.
8. **Missed tasks are lost:** only tasks due in the last 10 s are submitted (`schedule.py:163,197`), with no catch-up, so a restart or stall near AOS/LOS drops the action.

**Reported by search, not verified:**
1. **No reference counting:** back-to-back passes on the same antenna, or overlapping LeafSpace passes, can disable links that are still needed.
2. **Failures look like success:**
   - Tasks are marked COMPLETED even when every Yamcs call failed.
   - `get_link` sits outside the try in `disable_link` (`mcs.py:193`).
   - If `disable_non_pass_links` throws, the resulting NameError is swallowed and the enable is skipped.
   - The ON/OFF failure log messages are swapped (`schedule.py:83,94`).
3. **No uniqueness:** nothing makes (event_id, task_type) unique.
4. **Time handling:** naive UTC values are compared as strings, and `process_expression` round-trips through local time. This is safe only if the container TZ has no DST.
5. **`update_task_for_pass` overwrites the targets of all 6 tasks** (`schedule.py:473-480`). A pass that goes CANCELLED → BOOKED never gets its tasks back.
6. **Secrets in source:** `config.py:184-232,364-368`. The password is also passed on the `yamcs login` shell command line (`mcs.py:90`).
7. **Secondary never enables TC, by design:** on failover, TC must be enabled by hand.
