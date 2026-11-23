# Feasibility: move link management out of the MCS

## Contents
_Companion plan: [link-management-analysis.md](link-management-analysis.md). Keep this list in sync when headings change._

- [Context](#context)
- [New mission inputs: jTYU (ESW diagram, 2026-10-08)](#new-mission-inputs-jtyu-esw-diagram-2026-10-08)
- [Verdict](#verdict)
- [1. Why adding a link needs a restart today (verified)](#1-why-adding-a-link-needs-a-restart-today-verified)
- [2. Dependency answer: sequence counters per data link (verified)](#2-dependency-answer-sequence-counters-per-data-link-verified)
- [3. Options (recommendation first)](#3-options-recommendation-first)
  - [3.1 Where Option A runs: new service, not WDS](#31-where-option-a-runs-new-service-not-wds)
- [4. Design points Option A must cover](#4-design-points-option-a-must-cover)
- [5. Configurable gateway: any ground station × any transport](#5-configurable-gateway-any-ground-station--any-transport)
  - [5.1 Four independent layers](#51-four-independent-layers)
  - [5.2 Example config (illustrative schema)](#52-example-config-illustrative-schema)
  - [5.3 Fixed interfaces (the parts that do not vary)](#53-fixed-interfaces-the-parts-that-do-not-vary)
- [6. Open questions and decisions register](#6-open-questions-and-decisions-register)
  - [6.1 Pixxel decisions (owner)](#61-pixxel-decisions-owner)
  - [6.2 External inputs (ISRO, vendor)](#62-external-inputs-isro-vendor)
  - [6.3 Checks Pixxel can do alone](#63-checks-pixxel-can-do-alone)
  - [6.4 Current-system items (reference only, no action planned)](#64-current-system-items-reference-only-no-action-planned)
  - [6.5 Answered](#65-answered)
- [Verification (of the claims above)](#verification-of-the-claims-above)

## Context
The roadmap item reads: *"TM-TC Chain: Cross-Mission Link Management. Move link management outside of the MCS (Owner: Nikhil). Objective: eliminate MCS restarts when adding or removing links. Dependency: confirm whether Yamcs keeps fixed sequence counters per data link."* This plan designs link management for the **new mission** (new repo on `pixxel/master`). It is a clean slate: transports, link layout, controller and config can all be redesigned. The current FF/FFLY deployment (ff-mcs, wds-backend, MPS) is **reference only**: it shows what works and what fails, and nothing here migrates or patches it.

**Companion plan:** [`link-management-analysis.md`](link-management-analysis.md) is the as-is audit of the current deployment, plus (its §6) the new-mission controller requirements drawn from it. Its **§0 is the single source for versions and scope**. In short: ff-mcs runs `pixxel/5.10.x` @ `9fbfc03a2` and is reference only, while the new mission repo uses `pixxel/master` @ `30d83c552`. Line references in §1–§2 are for `9fbfc03a2` unless marked "master". The master differences that change this answer are listed below.

## New mission inputs: jTYU (ESW diagram, 2026-10-08)
Source: the work-in-progress diagram "jTYU · UMACS ↔ ESW TM/TC ICD · Information Flow (as of 2026-10-08)", received 2026-11-23. It covers the whole system; only the parts that bear on link management are listed here. The diagram uses three line styles: solid green = agreed, amber dashed = agreed with a UMACS ICD change pending, red dotted = to be discussed or ICD not received.

**What the diagram fixes**
| Element | What it says | Link-management meaning |
|---|---|---|
| Pixxel ESW container (Docker, always up) | Holds MCS TM Processing & Streaming, MCS TC Handler + TC-003 status aggregator, MOS, Alarm Engine, Offline Data Service, Nomenclature/Config Publisher and a time-series TM DB. Pixxel can adapt it as needed. | The link controller, and a gateway if one is built, live inside this container. "Always up" means links stay configured and only the pass window gates traffic. |
| Master Scheduler (ISRO) → ESW | "Comm window: when to talk to Baseband H/W" (green arrow, no ICD id). | **The pass schedule comes from ISRO, not MPS.** The analysis §6.3 reconciler takes comm windows as input instead of MPS BOOKED passes. |
| Baseband H/W ↔ ESW | TM frames during a pass go to MCS TM Processing; TC frames go from the MCS TC Handler to the baseband. Both red dotted: **Baseband H/W ICD not received.** | Transport, envelope and uplink coding (the §5.1 layers) are all unknown for jTYU. |
| Baseband H/W ↔ jTYU | RF TM/TC link during a pass. | One baseband is drawn. The station count is not stated. |
| MCS TM → Time-series TM DB | "All processed TM (single data source)", queried by Offline Data Service (MS-OF-ICD-001/002). | Every frame stored needs ERT and the station/baseband id (§4 point 2) so offline queries can attribute it. |
| MOS ↔ MCS TC Handler | MOS sends "next TC to dispatch" over the MCS API; per-TC execution status goes back (push or pull: diagram's Q-13). | TC dispatch must check link state: a TC released outside a comm window has to fail visibly in TC-003 status, not vanish. |

**Owner's assumption (2026-11-23): jTYU uses a commercial ground station for LEOP.** The diagram does not draw it. Routine operations are assumed to go through the ISRO baseband, so jTYU has at least two station types, and the commercial one is only needed during LEOP.

**What the diagram does not show** (none of these is drawn)
- The commercial LEOP station (see the assumption above), or more than one ISRO baseband.
- A primary/secondary MCS pair.
- Link or station status going to UMACS. No ICD row carries it; candidates are a TM parameter over MS-TM-ICD-002 or an alarm over MS-AS-ICD-002.
- Encryption (SRS4/SDLS) on the baseband path.

**Effect on this plan**
1. **Option A stays the recommendation, with a smaller first scope.** With the LEOP assumption, jTYU needs two station types with different transports (the ISRO baseband and a commercial vendor). The commercial station is added for LEOP and removed after it, which is exactly the roadmap's "add or remove links without an MCS restart" case. The first gateway release needs only two profiles: the ISRO baseband and the chosen LEOP vendor. It does not need the full KSAT/LeafSpace set. If the LEOP station were dropped and only fixed ISRO basebands remained, a few fixed `pixxel/master` composed links plus the reconciler would be enough.
   - **LEOP stresses the controller:** dense back-to-back passes across stations, so the analysis §6.3 rules (TC overlap: first started wins; margins `aos-60`/`los+30`) are exercised from the first day of the mission.
2. **Q4 (where the controller runs, §6) leans toward inside the ESW container.** WDS's job today is polling MPS; for jTYU the schedule source is ISRO's Master Scheduler, so WDS's MPS code does not carry over.
3. **The §5.1 layer model still fits**: the baseband becomes one more profile once its ICD arrives. If the baseband speaks CCSDS SLE (RAF/RCF/CLTU), note that this fork has no SLE module; upstream Yamcs ships SLE as the separate `yamcs-sle` plugin (compatibility with `pixxel/master` not checked).

**Open questions raised here:** Q5 (baseband ICD), Q6 (LEOP vendor, station count), Q7 (comm-window interface), Q8 (redundancy), Q9 (TC outside a window, status to UMACS), Q10 (LEOP schedule source). All are in the §6 register.

## Verdict
The redesign is **feasible**, and the dependency question is answered **in favour** of it: no counter that matters is tied to a link in the current setup. The real work is elsewhere: station attribution, TC routing, primary/secondary fan-out, and status reporting all have to move into a new component.

**For jTYU** (ISRO baseband for routine ops, a commercial station for LEOP): Option A holds, with a two-profile first release. Sign-off is D1 in the §6 register; the blocking external inputs are Q5 (baseband ICD) and Q6 (LEOP vendor).

**On `pixxel/master` the picture shifts in four ways:**
1. On 5.10.x the PUS sequence counter was a JVM-wide map, so no counter was tied to a link. On master it is **per link by default**: the counter is tied to the postprocessor instance and is lost when that link is removed or restarted. A shared counter needs `seqCounterName` set explicitly. This makes the "no counter tied to a link" verdict conditional on that config. See §2.
2. Master already has a **composition model** (`DataLinkCompositionResolver`, `ComposedTmFrameLink`, `ComposedTcFrameLink`), which overlaps the four-layer model in §5.1. Only UDP transports and a CCSDS 131 channel decoder/CLTU encoder are included; the TCP, MQTT and websocket transports are not.
3. Master already has `enableLink`/`disableLink` on `LinkManager` and `LinksApi`. Only `addLink`/`removeLink` are missing, so Option B is smaller on master.
4. SRS3 is gone on master (SRS4 and SDLS replace it). The new mission repo targets SRS4/SDLS; ff-mcs's SRS3 path is reference only. Encryption is out of scope for link management.

## 1. Why adding a link needs a restart today (verified)
- `LinkManager` reads `dataLinks:` only in its constructor. It has no public `addLink` or `removeLink`.
  - `registerLink`/`unregisterLink` (`LinkManager.java:344/372` on 5.10.x; `:346/374` on master) are private and are called only from `createDataLink` (:234 on 5.10.x; :111 on master) and `stopLinks` (:331 on 5.10.x; same method on master).
  - `LinkListener.linkAdded/linkRemoved` therefore fire only at instance start and stop.
- Restarting the instance alone is enough; the JVM can stay up. `YamcsServer.restartInstance` (:383 on 5.10.x; :385 on master) reloads the instance YAML (:398), and REST exposes it as `POST /api/instances/{inst}:restart`.
  - The restart still drops processors, command queues, in-flight CFDP transactions and the in-memory sequence counters (see §2).
- In practice, links are added only when a new station appears. All 50 KSAT stations are already defined, disabled.
  - The `HA7` addition is an example of this cost: WDS `ANTENNAS` gained HA7 (commit 98d732b), and the ff-mcs links for it were added separately (coverage: analysis plan §3b issue 2).

## 2. Dependency answer: sequence counters per data link (verified)
| Counter | Where it lives | Tied to a link? | Impact of moving links out |
|---|---|---|---|
| PUS/CCSDS packet seq count (TC) | 5.10.x: `PusTcManager.java:81` → `CcsdsSeqCountFiller.java:15`, a **`static` map keyed by APID**. Master: `pus/PusCommandPostprocessor.java:121,216-217`, a per-instance map, shared only with `seqCounterName` | 5.10.x: **No**, it is assigned before link selection. Master: **Yes** by default (the map belongs to the link's postprocessor) | 5.10.x: none, as long as `PusTcManager` stays in Yamcs. ⚠ It is in memory only and resets to 0 on every restart; it is also JVM-wide. Master: removing or restarting a link resets its counter unless `seqCounterName` is set. |
| TC frame seq N(S) | 5.10.x: `Cop1TcPacketHandler` vS, per link per VC (`Cop1TcPacketHandler.java:114`, `int vS`). Master: `Cop1UplinkPacketHandler` (`int vS` at :111) | Would be, but **COP-1 is off** (`useCop1` appears 0 times in ff-mcs) | None today. BD frames (`TcPacketHandler.java:117`) carry no meaningful seq. If COP-1 is turned on later, one link per station would split vS. A single gateway-facing link avoids that. |
| SRS3 AES-GCM nonce | 5.10.x: `GCM.java:43,59-62`: a random 12-byte nonce for each frame. Master: SRS3 removed (replaced by SRS4 and SDLS; nonce handling not checked) | No | 5.10.x: no state to synchronise. Master: unknown until the SRS4 config is checked. |
| TM VC frame counters | Per-link VC handlers and ff-mcs `TcpTmFrameLink` counters | Yes, but statistics only | Only the per-station counters in `getExtraInfo` are lost. |
| Monitoring `sequenceCount` | `WebsocketsParameterDataLink.java:36`, `MqttParameterDataLink.java:55` | Yes | Cosmetic only. |

**Conclusion (5.10.x):** Yamcs does **not** keep sequence counters per data link that need protecting in this configuration. The counter that matters (PUS seq per APID) is per JVM and is lost on **restart**. That is a further argument for removing restarts.

**Conclusion (master):** the counter is per link unless `seqCounterName` is set. Whichever way it is configured, removing or re-adding a link resets it, and a restart resets it. Before the new mission relies on link-level add/remove, set a shared `seqCounterName` for each TC path that must stay continuous, or accept the reset.

## 3. Options (recommendation first)
| # | Option | What changes | Effort (rough) | Meets the objective? |
|---|---|---|---|---|
| **A (rec.)** | **Ground-station gateway service** outside Yamcs. It owns every station connection: for jTYU, the ISRO baseband and the LEOP vendor. ff-mcs's KSAT TCP (56030/51103), KSAT websocket and LeafSpace MQTT links are the reference implementations. Each instance gets **one stable link per kind** (Tm, Tc, Cfdp, Monitoring) pointing to the gateway. | New service; the pass controller drives the gateway instead of the Yamcs REST API; the new mission's instance YAML has about 4 links (ff-mcs has 208 per instance). | **4-7 weeks** (service with the configurable plug-in model of §5, Yamcs-side links, testing with each station profile; no migration, since the mission is new; re-estimate pending, §6 V4) | Yes. Adding a station becomes gateway config only. |
| B | Extend the fork: add `LinkManager.addLink(spec)/removeLink(name)` plus a `LinksApi` create/delete and proto. On master, enable/disable already exists, so only add/remove is new. The new mission keeps per-station links in Yamcs. | Wire the TC stream subscriber, register system parameters, persist the memento. | **3-5 days** in the fork, plus the controller (estimate made for 5.10.x; not re-estimated for master) | Partly: no restart, but link management stays inside the MCS. |
| C | No code: per-station links as in ff-mcs, and `POST /api/instances/{inst}:restart` to add one | None | 0 | No. Still a restart, and still resets counters. |

### 3.1 Where Option A runs: new service, not WDS
**Decision (recommended):** build the gateway as a **new service**. The pass-driven **controller** (control plane, analysis §6) drives the gateway; it does not carry frames. The controller can be WDS deployed with new-mission config, or something new (§6 Q4). The reasons below rule out WDS only as the *gateway*.

"Fully out" has a limit. Mission logic stays in Yamcs: SRS4/SDLS crypto, the SCID check, TC frame building and VC routing, and PUS sequence counters. Each instance keeps about 4 fixed gateway-facing links (§5.3).

**Why not repurpose WDS as the gateway:**
1. **Different job.** WDS is a scheduler: PassUpdateManager polls every 30 s, and RelativeTaskManager runs every 2 s with no catch-up. The gateway is a real-time data plane that carries every TM/TC frame.
2. **Failure coupling.** A WDS restart today costs a missed task. If WDS were the gateway, the same restart would drop TM and TC in the middle of a pass.
3. **Deployment shape.** WDS runs once per VM, per mission deployment, with SQLite and a single container. A cross-mission gateway needs its own HA (§6 Q3).
4. **Code fit (minor).** The reference transport code (`TcpTmFrameLink`, the `Mqtt*Link` classes, Paho) is Java; WDS is Python.

**Role split:**
| Component | Role |
|---|---|
| Gateway (new) | Data plane: station connections, envelopes, uplink coding, TM fan-out to primary and secondary, per-station status |
| Controller (WDS or new, §6 Q4) | Control plane: pass schedule and the analysis §6 reconciler. It calls `POST /missions/{m}/sessions` on the gateway instead of enabling/disabling Yamcs links. |
| Yamcs (`pixxel/master`) | Mission logic: crypto, framing, PUS, and about 4 fixed links |

**If WDS is the controller,** the new mission needs: gateway session calls in place of the `app/utils/mcs.py` enable/disable calls (`disable_non_pass_links`, `disable_link`); the station list read from the gateway registry instead of `config.py:295` `ANTENNAS`; and per-deployment config instead of a `main`/`main-us`-style branch (analysis §6.7 decision 4). The current FF/FFLY WDS is not changed.

## 4. Design points Option A must cover
1. **Where to split.** Anything **mission-specific** stays in Yamcs; anything **station-specific** moves to the gateway (detail in §5).
   - **Stays in Yamcs:** SRS4/SDLS encryption/decryption (SRS3 in ff-mcs), the SCID check, TC frame building and VC routing (`AbstractTmFrameLink.handleFrame`). Keys never leave the MCS.
   - **Moves to the gateway:** sockets, envelopes (KSAT T501, LeafSpace JSON), uplink channel coding (RS/CLTU/ASM) and the SatLab pre/postamble. Today these all sit in the `addNonsense()` tail.
2. **Station attribution.** Today the link name *is* the station: it is a TM tuple column and part of command history. The gateway must pass the station id with every frame, and the Yamcs-side link has to surface it as a parameter or tuple column.
3. **TC routing.** ff-mcs's implicit "first enabled link wins" rule (analysis §5 gap 2) becomes an explicit gateway rule: one active uplink station per satellite, chosen by the controller (analysis §6.3). This designs out the current "primary TC never enabled" fault (analysis §3b confirmed issue 1).
4. **Primary/secondary.** The gateway fans TM out to both instances and accepts TC and CFDP only from the primary. In the current deployment, WDS calls both instances.
5. **Status and alarms.** Yamcs then sees only the health of the gateway link. Per-station status has to come from the gateway as parameters, which could also close §5 gap 1 (no link-status events).
- Cost to accept: one extra network hop, and the gateway becomes a single point of failure for every mission. It needs its own HA.
- "Cross-mission": the gateway is mission-agnostic if it stays transport-only, and each mission's Yamcs keeps its own framing and crypto.

## 5. Configurable gateway: any ground station × any transport
Goal: adding a ground-station vendor or a transport type should be **configuration only**. Code is needed only when a transport or envelope is genuinely new.

### 5.1 Four independent layers
Every station channel is a composition of the four layers below, and each layer is a plug-in chosen by name in config.

| Layer | Job | Initial plug-ins | Where it lives today (ff-mcs) |
|---|---|---|---|
| **Transport** | Connect, reconnect, move bytes or messages | `tcp-client`, `tcp-server`, `udp`, `websocket` (ws/wss), `mqtt` (tcp/ssl/**ws/wss**) | Socket in `TcpTmFrameLink`, `TcpTcMultiplexer`, `WebsocketsParameterDataLink`, Paho client in `Mqtt*Link` |
| **Envelope** | Unwrap downlink to frame + ERT + station id; wrap uplink | `ksat-t501`, `length-prefix`, `json-field` (configurable field names, hex or base64), `raw` | T501 parsing in `TcpTmFrameLink`/`KsatPacketInputStream`; `{timestamp, payload}` JSON in `MqttTmFrameLink.java:279-281` |
| **Uplink coding** (TC only) | Station-specific channel coding | `rs`, `cltu`, `asm`, `satlab-pre-post`, `none`, chained in order | `TcpTcFrameLink.addNonsense()` |
| **Channel kind** | What the channel carries | `tm`, `tc`, `cfdp-up`, `monitoring` | `<ST>_Tm/_Tc/_Cfdp/_Monitoring` |

- A **profile** names one vendor's composition (KSAT, LeafSpace, …). A **station** is a profile plus host and credentials. A **mission** lists the stations it may use.
- **MQTT over WebSocket costs no extra code.** Eclipse Paho `mqttv3`, already in the ff-mcs `pom.xml:39-40`, accepts `ws://` and `wss://` broker URIs, so it is a URI change on the `mqtt` transport.

### 5.2 Example config (illustrative schema)
```yaml
profiles:
  ksat:
    tm:         { transport: tcp-client, port: 56030, envelope: ksat-t501 }
    tc:         { transport: tcp-client, port: 51103, envelope: ksat-t501,
                  uplinkCoding: [rs, cltu, asm, satlab-pre-post], shareSocket: cfdp-up }
    cfdp-up:    { sameAs: tc }
    monitoring: { transport: websocket, url: "ws://{host}:8079/receive",
                  envelope: json, filter: ksat-contact }
  leafspace:
    tm:         { transport: mqtt, broker: "tcp://mqtt.leaf.space:1883",
                  topic: "{norad}/downlink", envelope: json-field,
                  payloadField: payload, ertField: timestamp, encoding: hex }
    tc:         { transport: mqtt, topic: "{norad}/uplink", envelope: raw }
    monitoring: { transport: mqtt, topic: "{norad}/monitoring/#", envelope: json }
  vendor-x:     # example: MQTT over WebSocket
    tm:         { transport: mqtt, broker: "wss://broker.vendor-x.example/mqtt",
                  topic: "{scid}/tm", envelope: raw }
  isro-baseband: # placeholder until the Baseband H/W ICD arrives (§6 Q5)
    tm:         { transport: TBD, envelope: TBD }
    tc:         { transport: TBD, envelope: TBD, uplinkCoding: TBD }

stations:
  - { id: HA2,  profile: ksat, host: 10.0.0.2 }
  - { id: HA7,  profile: ksat, host: 10.0.0.7 }      # add without any MCS restart
  - { id: LEAF, profile: leafspace, credentials: ${env.LEAFSPACE_CREDS} }
  - { id: ISRO-BB1, profile: isro-baseband, host: TBD }

missions:
  - id: JTYU
    norad: TBD
    scid: TBD
    yamcs: { primary: "...", secondary: "..." }   # secondary only if Q8 says pair
    stations: [ISRO-BB1, HA2]   # HA2 stands in for the LEOP vendor (Q6); removed after LEOP
```

### 5.3 Fixed interfaces (the parts that do not vary)
1. **Northbound to Yamcs.** One envelope for every station: `{kind, stationId, ert, frame}` over a single TCP or WebSocket connection per instance.
   - Yamcs needs only 3 generic link classes: `GatewayTmFrameLink` (extends `AbstractTmFrameLink`), `GatewayTcFrameLink` and `GatewayParameterLink`.
   - The new mission's instance YAML has about 4 links (ff-mcs has 208) and never changes when a station is added.
2. **Control API for the controller and operators:**
   - `PUT/DELETE /stations/{id}`: add, update or remove a station hot. Persisted.
   - `POST /missions/{m}/sessions {station, aos, los, uplink}`: start or stop a pass session.
   - A status stream that reports per-station state as Yamcs parameters (for example `/GS/<station>/status`).
3. **Plug-in SPI:** three interfaces, `Transport`, `Envelope` and `UplinkCoder`.
   - A new vendor on existing transports is a config change only.
   - A new transport or envelope is one class plus a registry entry.

## 6. Open questions and decisions register
Single list of everything still open across both plans, as of 2026-11-23. IDs are stable; other sections refer to them. When an item is answered, move it to 6.5 with the date.

### 6.1 Pixxel decisions (owner)
| ID | Question | Current lean | Blocks |
|---|---|---|---|
| D1 | Approve Option A (gateway) as the architecture. | Yes, with a two-profile first release (ISRO baseband + LEOP vendor). | All design work |
| Q2 | One gateway shared across missions, or one per mission? | Shared ("cross-mission" goal), routing frames by SCID or NORAD. | Gateway config model, HA |
| Q3 | Gateway HA: active/standby, or one gateway per site? | None yet. Ties to Q8. | Deployment |
| Q4 | Where does the pass controller run: WDS with new-mission config, a new service, or inside the gateway? | Inside the ESW container: jTYU's schedule comes from ISRO, so WDS's MPS polling does not carry over. | Controller build |
| Q8 | Redundancy: one ESW container, or a primary/secondary pair? | None yet. Decides whether the gateway fans TM out to two instances (§4 point 4). | Gateway fan-out, Q3 |
| Q9 | What happens to a TC released outside a comm window (reject, hold, or MOS decides), and which UMACS interface carries link/station status (TM parameter over MS-TM-ICD-002, or alarm over MS-AS-ICD-002)? | Reject visibly in TC-003 status; publish station status as TM parameters. | TC handler, UMACS ICD |
| Q11 | Which TC paths need a continuous sequence count (`seqCounterName`) on `pixxel/master`? See §2. | Every PUS TC path that crosses a station change. | Instance config |

### 6.2 External inputs (ISRO, vendor)
| ID | Question | From | Blocks |
|---|---|---|---|
| Q5 | Baseband H/W ICD: transport (SLE, TCP, UDP?), frame envelope, and who does channel coding/CLTU (baseband or ESW)? | ISRO | ISRO baseband profile; V3 |
| Q6 | Which commercial vendor serves LEOP (KSAT, LeafSpace, other), and through which transport? How many ISRO basebands serve routine ops? Can one pass hand over between stations? | Pixxel ops / ISRO | LEOP profile, controller rules |
| Q7 | Comm-window interface from the Master Scheduler: format, transport, push or pull, lead time, updates and cancellations, time reference. | ISRO | Controller input |
| Q10 | Who schedules the commercial LEOP passes? If the Master Scheduler does not issue windows for them, the controller needs a second source (vendor booking API or a Pixxel-side plan). | Pixxel ops / ISRO | Controller input |

### 6.3 Checks Pixxel can do alone
| ID | Check | Where | Needed if |
|---|---|---|---|
| V1 | SRS4 nonce/IV handling on master: is any state tied to a link? | `tctm/ccsds/srs4/` on `pixxel/master` (§2 table) | Always |
| V2 | Does `AbstractTmFrameLink.handleFrame` (master :134) keep the 5.10.x redirection and SCID logic? | `pixxel/master` | Always (the `GatewayTmFrameLink` extends it) |
| V3 | Does upstream `yamcs-sle` work with `pixxel/master`? | upstream plugin | Q5 answer is SLE |
| Q1 | Does LeafSpace expect a CLTU or a bare TC frame on `{norad}/uplink`? (`MqttTcFrameLink.java:238` publishes `readyFrame`; encoding not checked.) | ff-mcs | Q6 answer is LeafSpace |
| V4 | Re-estimate Option A (4–7 weeks) and B (3–5 days, a 5.10.x estimate) against `pixxel/master` and the two-profile scope. | this plan §3 | Before D1 sign-off |

### 6.4 Current-system items (reference only, no action planned)
Check these only if the new design reuses the code concerned.
- `TcpTmFrameLink` 3 s connect timeout and 10 s reconnect; `KsatPacketInputStream` silently drops oversized frames (analysis §3). Relevant if KSAT is the LEOP vendor and these classes are ported.
- `WebsocketsParameterDataLink` NPE when KOGS returns non-200 (analysis §5 gap 4). Same condition.
- WDS: `SERVER` value on the FF VM not checked (analysis A6 item 4); the A6 "reported, not verified" items (swapped ON/OFF log messages, failures marked COMPLETED, no uniqueness, time handling).
- Verification item 4 below (instance restart picks up a new link): Option C only.

### 6.5 Answered
| Item | Answer | Date | Recorded in |
|---|---|---|---|
| Scope | New mission (jTYU) is a clean-slate design in a new repo on `pixxel/master`; ff-mcs, WDS and MPS are reference only. | 2026-11-23 | Context; analysis §0 |
| Sequence-counter dependency | No counter that matters is tied to a link on 5.10.x; on master it is per link unless `seqCounterName` is set. | 2026-11-23 | §2 |
| Where the gateway runs | A new service, not WDS (WDS is ruled out only as the gateway). | 2026-11-23 | §3.1 |
| Link window margins | Configurable, defaulting to `aos-60` / `los+30`. | 2026-11-23 | analysis §6.3, §6.7 |
| Overlapping TC passes | The first pass to start keeps TC until its LOS; the overlap raises an alert. | 2026-11-23 | analysis §6.3, §6.7 |
| Secondary TC | Manual failover only; the controller never enables it. | 2026-11-23 | analysis §6.3, §6.7 |
| Per-deployment values | Config, not code branches (unlike WDS `main`/`main-us`). | 2026-11-23 | analysis §6.7 |
| Commercial station for jTYU | Assumed: a commercial ground station is used for LEOP. | 2026-11-23 (assumption) | "New mission inputs" |
| MPS service | gss-fos-backend is taken as the live MPS (current system). | 2026-11-23 (assumption) | analysis §1 |
| FFLY01 `spacecraftId: FF06` | A config mistake in the current system; ignored. | 2026-11-23 | analysis §5 item 7 |

## Verification (of the claims above)
1. `grep -n "registerLink(\|unregisterLink(" yamcs-core/src/main/java/org/yamcs/management/LinkManager.java`: only internal call sites.
2. `grep -n "static" yamcs-core/src/main/java/org/yamcs/tctm/CcsdsSeqCountFiller.java` on `9fbfc03a2`: the seq map is static and keyed by APID. On `pixxel/master` the same grep shows only the named map (`namedSeqCounts`). The default per-instance map is set in the constructor, so check `PusCommandPostprocessor.java:121,216-217` too.
3. In each ff-mcs branch, `grep -c -i "useCop1: *true" src/main/yamcs/etc/yamcs.FFLY01-primary.yaml` (`ffly/prod/base`) or `yamcs.FF01-primary.yaml` (`ff/prod/base`) returns 0.
4. On a test server, `POST /api/instances/FFLY01-primary:restart` picks up a newly added link without a JVM restart. (Matters only for Option C.)
