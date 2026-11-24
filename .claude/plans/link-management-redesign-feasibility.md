# Feasibility: move link management out of the MCS

## Contents
_Companion plan: [link-management-analysis.md](link-management-analysis.md). Keep this list in sync when headings change._

- [Context](#context)
- [New mission inputs: jTYU (ESW diagram, 2026-10-08)](#new-mission-inputs-jtyu-esw-diagram-2026-10-08)
  - [ISTRAC station ICDs (received 2026-11-12)](#istrac-station-icds-received-2026-11-12)
  - [Effect on this plan](#effect-on-this-plan)
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

**Update 2026-11-24:** the diagram's "Baseband H/W ICD not received" is out of date. ISTRAC sent two draft station-side ICDs on 2026-11-12, and Pixxel has reviewed them. See [ISTRAC station ICDs](#istrac-station-icds-received-2026-11-12) below. Sources for this update are in the `gss-jtyu-prd` repo, `MCS/` folder: `esw-istrac-icd/memory/decisions.md` (ST-D-xx), `esw-istrac-icd/icd_summary.md` (ST-Q-xx), `umacs-esw-icd/memory/decisions.md` (D-xx) and `to-dos for PM.md` (PM-xx).

**What the diagram fixes**
| Element | What it says | Link-management meaning |
|---|---|---|
| Pixxel ESW container (Docker, always up) | Holds MCS TM Processing & Streaming, MCS TC Handler + TC-003 status aggregator, MOS, Alarm Engine, Offline Data Service, Nomenclature/Config Publisher and a time-series TM DB. Pixxel can adapt it as needed. | The link controller, and a gateway if one is built, live inside this container. "Always up" means links stay configured and only the pass window gates traffic. |
| Master Scheduler (ISRO) → ESW | "Comm window: when to talk to Baseband H/W" (green arrow, no ICD id). | **The pass schedule comes from ISRO, not MPS.** The analysis §6.3 reconciler takes comm windows as input instead of MPS BOOKED passes. Since decided: the window arrives **before AOS** (D-25) and goes to the **Pass Mgmt** module inside ESW (D-35). The pass details name the station, which picks the RTD-API topics (ST-D-11). |
| Baseband H/W ↔ ESW | TM frames during a pass go to MCS TM Processing; TC frames go from the MCS TC Handler to the baseband. Both red dotted: **Baseband H/W ICD not received.** | **Superseded 2026-11-13:** the MCS never talks to the baseband. TM and TC go through ISTRAC's RTD-API servers (ST-D-03). See the next subsection. |
| Baseband H/W ↔ jTYU | RF TM/TC link during a pass. | One baseband is drawn. Since decided: jTYU uses stations of both the ISTRAC and the SBS-3 networks (ST-D-10). |
| MCS TM → Time-series TM DB | "All processed TM (single data source)", queried by Offline Data Service (MS-OF-ICD-001/002). | Every frame stored needs ERT and the station/baseband id (§4 point 2) so offline queries can attribute it. |
| MOS ↔ MCS TC Handler | MOS sends "next TC to dispatch" over the MCS API; per-TC execution status goes back (push or pull: diagram's Q-13). | TC dispatch must check link state: a TC released outside a comm window has to fail visibly in TC-003 status, not vanish. |

**Owner's assumption (2026-11-23): jTYU uses a commercial ground station for LEOP.** The diagram does not draw it. Routine operations are assumed to go through the ISRO baseband, so jTYU has at least two station types, and the commercial one is only needed during LEOP. (2026-11-24: routine operations go through ISTRAC's RTD-API servers, not the baseband directly. Whether the LEOP station is a second station type depends on whether it also comes through RTD-API, Q6.)

**What the diagram does not show** (none of these is drawn; status as of 2026-11-24)
- The commercial LEOP station (see the assumption above). Still in no ICD. RTD-API Sec. 1 says ISTRAC also buys station time from external agencies (KSAT, SSC, NOAA, ESA, AWS, JPL, Leaf Space, Kepler, SANSA, JAXA). Whether jTYU's LEOP passes reach the MCS through RTD-API or directly is open (Q6).
- A primary/secondary MCS pair. **Since decided:** a main and a redundant MCS, one in each control centre (ST-D-07, ST-D-09). Closes Q8.
- Link or station status going to UMACS. Still no ICD row carries it; candidates are a TM parameter over MS-TM-ICD-002 or an alarm over MS-AS-ICD-002.
- Encryption (SRS4/SDLS). Unchanged: SDLS stays in the MCS. The STC block carries the SDLS frame as its payload (ST-D-29, ST-D-34).

### ISTRAC station ICDs (received 2026-11-12)
Sources: *ISTRAC RTD-API Interface Specification*, ISTRAC-MPS-RTD-API-03 Issue 1, 19 Aug 2026 (draft), and *ICD between Station Computer & Spacecraft Health Monitoring & Command Software*, Draft V1.0, 30 Sep 2026 (the STC ICD). The RTD-API is the transport and the STC ICD is the payload. Pixxel's review is complete, but nothing has been discussed with ISTRAC yet; 41 questions to ISTRAC are open (`icd_summary.md` Sec. 3.1). Decisions below are Pixxel's (ST-D-xx), not ISTRAC-confirmed.

| Topic | What is decided | Source | Link-management meaning |
|---|---|---|---|
| Path | The MCS never connects to the baseband. TM and TC go TTC Processor → STC → SBS-3 Mission Network → RTD-API servers → MCS. | ST-D-03 | No baseband profile. One RTD-API profile covers every ISTRAC and SBS-3 station. |
| Transport | The MCS is an MQTT client: MQTT **v5** over TLS 1.3, port 8883 (not websockets). It logs in with an ISTRAC-issued username, password and client id, and uses mutual TLS against the ISTRAC root CA. Credentials are deployment secrets. | ST-D-04, ST-D-12, ST-D-13, ST-D-15, ST-D-16 | ff-mcs uses Paho `mqttv3` (`pom.xml:39-40`), which does not speak MQTT v5. The new repo needs the separate Paho `mqttv5` client. |
| Topics | Subscribe to `/<user>/<spacecraft>/telemetry/<station>` and `/<user>/<spacecraft>/command-response/<station>`; publish TC on `/<user>/<spacecraft>/command-transmit/<station>`. | ST-D-17 | **A station is a topic, not a connection.** Adding a station means subscribing to new topics on the connection that already exists. Whether a wildcard (`.../telemetry/+`) is allowed is open (ST-Q-21). |
| Envelope | One STC block per message (ISTRAC to confirm, ST-Q-23): header, time stamp, quality factor, frame, filler, checksum. The header time stamp is the ERT. DUMMY TM blocks are not TM; they mark a data break. | ST-D-18, ST-D-21, ST-D-22, ST-D-23 | A new `stc-block` envelope. Station id and ERT come from the block header (§4 point 2). Byte layouts are still pending (ST-Q-29, ST-Q-51, ST-Q-52). |
| Uplink coding | The MCS sends bare TC frames inside the STC block. ISTRAC does the CLTU coding. | ST-D-30 (ISTRAC to confirm, ST-Q-50) | `uplinkCoding: none` for this profile. Not SLE. |
| TC session | Before any TC in a pass, the MCS opens a TC session with that pass's STC and sends TC only after a success ack. No init ack within a timeout means the station is unreachable (alert). Each TC gets two acks, "received by STC" and "radiated by station", each with a timeout. Acks are matched to requests by the satellite block data they carry back. | ST-D-31 to ST-D-37 | New per-station, per-pass state that a pass session must own (§4 point 6). |
| Station selection | Master Scheduler pass details, through Pass Mgmt, give the station and the timing, and so the topics. | ST-D-11; D-25, D-35 | The controller is Pass Mgmt. It picks the topics for each pass. Answers Q4. |
| Redundancy | Main MCS in the Main Spacecraft Control Centre, redundant MCS in the Redundant Centre; each is the other's failsafe. Each centre has a primary and a redundant RTD-API server (four servers in all). | ST-D-07, ST-D-09; RTD-API Fig. 1 | Answers Q8. How the four servers work together and how the MCS switches over is Q12. |
| Offline data | End-of-pass files (TM, TC, tracking) plus a meta file. How they reach Pixxel is not defined. | ST-D-20, ST-Q-27 | Outside link management. Listed for completeness. |

### Effect on this plan
1. **The case for Option A narrows for jTYU [inference].** For every ISTRAC and SBS-3 station, a new station is a new topic on one MQTT connection. If the Yamcs-side link takes its station topic from Pass Mgmt for each pass, the link-per-station restart problem does not arise. That is **Option D** in §3. The gateway (Option A) adds value only if the LEOP vendor connects **outside** RTD-API with its own transport (Q6). The commercial LEOP station is still added for LEOP and removed after it; under Option D this is a config change only if it comes through RTD-API. D1 now waits on Q6.
   - **LEOP stresses the controller:** dense back-to-back passes across stations, so the analysis §6.3 rules (TC overlap: first started wins; margins `aos-60`/`los+30`) are exercised from the first day of the mission. Whether a TC session can open at `aos-60` depends on ST-Q-47 (Q13).
2. **Q4 is answered: the controller is the Pass Mgmt module inside ESW** (D-35, user 2026-10-30). It owns the comm window, AOS and LOS; the MCS does not detect AOS/LOS itself. It is not built yet (PM-16, MPS engineer). WDS's MPS polling does not carry over.
3. **The §5.1 layer model still fits.** RTD-API is one profile: `mqtt` transport (v5) + `stc-block` envelope + `uplinkCoding: none` (§5.2). It is not SLE, so the `yamcs-sle` check (V3) is dropped. The TC session handshake is new: it is neither a transport nor an envelope (§4 point 6).
4. **The gateway may not need to copy TM to both MCS instances [inference].** The RTD-API servers are MQTT brokers, so the main and the redundant MCS can each subscribe directly. Whether both may subscribe, and which one may publish TC, waits for Q12.

**Open questions raised here:** Q6 (LEOP vendor and path), Q7 (comm-window format), Q9 (TC outside a window, status to UMACS), Q10 (LEOP schedule source), Q12 (RTD-API servers and MCS switch-over), Q13 (ISTRAC items that set controller parameters). Answered here: Q4, Q5, Q8. All are in the §6 register.

## Verdict
The redesign is **feasible**, and the dependency question is answered **in favour** of it: no counter that matters is tied to a link in the current setup. The real work is elsewhere: station attribution, TC routing, primary/secondary fan-out, and status reporting all have to move into a new component.

**For jTYU** (ISTRAC/SBS-3 stations through RTD-API for routine ops, a commercial station for LEOP): the choice now turns on Q6. If the LEOP vendor also comes through RTD-API, **Option D** (one RTD-API link per kind, topic chosen per pass) meets the objective without a gateway. If the LEOP vendor connects directly, Option A holds with a two-profile first release (RTD-API + LEOP vendor). Sign-off is D1 in the §6 register. Q5 (station ICD) was answered on 2026-11-24 by the ISTRAC drafts; the blocking inputs are now Q6 (LEOP vendor and path) and Q12 (RTD-API servers and MCS switch-over).

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
| SDLS anti-replay sequence number (ARSN), per security association | Master: SDLS config (`CcsdsTcSdlsConfiguration`); where the ARSN is held and whether it is persisted **not checked** (§6 V5) | Unknown | jTYU requires the ARSN to survive MCS restarts (MCS-DR-009, Proposed, `gss-jtyu-prd/MCS/derived_requirements.md`). If master ties it to the link or keeps it in memory, removing a link or restarting would desynchronise it from the spacecraft. Added 2026-11-24. |

**Conclusion (5.10.x):** Yamcs does **not** keep sequence counters per data link that need protecting in this configuration. The counter that matters (PUS seq per APID) is per JVM and is lost on **restart**. That is a further argument for removing restarts.

**Conclusion (master):** the counter is per link unless `seqCounterName` is set. Whichever way it is configured, removing or re-adding a link resets it, and a restart resets it. Before the new mission relies on link-level add/remove, set a shared `seqCounterName` for each TC path that must stay continuous, or accept the reset. The SDLS ARSN has a stricter requirement (it must survive restarts, MCS-DR-009) and its storage on master is unchecked (V5).

## 3. Options (recommendation first)
| # | Option | What changes | Effort (rough) | Meets the objective? |
|---|---|---|---|---|
| **A (rec. if Q6: LEOP vendor connects directly)** | **Ground-station gateway service** outside Yamcs. It owns every station connection: for jTYU, the ISTRAC RTD-API servers and the LEOP vendor. ff-mcs's KSAT TCP (56030/51103), KSAT websocket and LeafSpace MQTT links are the reference implementations. Each instance gets **one stable link per kind** (Tm, Tc, Cfdp, Monitoring) pointing to the gateway. | New service; the pass controller drives the gateway instead of the Yamcs REST API; the new mission's instance YAML has about 4 links (ff-mcs has 208 per instance). | **4-7 weeks** (service with the configurable plug-in model of §5, Yamcs-side links, testing with each station profile; no migration, since the mission is new; re-estimate pending, §6 V4) | Yes. Adding a station becomes gateway config only. |
| **D (rec. if Q6: LEOP via RTD-API)** (added 2026-11-24) | **One RTD-API link per kind inside Yamcs.** Each instance has one MQTT v5 TM link and one TC link connected to the RTD-API server. Pass Mgmt tells the links which `<station>` topics to use for each pass. The station id comes from the STC block header. | New Yamcs link classes: MQTT v5 transport, `stc-block` decode/encode, TC session handshake and ack tracking (§4 point 6), and an API for Pass Mgmt to set the active station. No gateway service. | Not estimated (§6 V4) | Yes, for every station reachable through RTD-API: a new station is a new topic, with no restart. No, for a LEOP vendor outside RTD-API. |
| B | Extend the fork: add `LinkManager.addLink(spec)/removeLink(name)` plus a `LinksApi` create/delete and proto. On master, enable/disable already exists, so only add/remove is new. The new mission keeps per-station links in Yamcs. | Wire the TC stream subscriber, register system parameters, persist the memento. | **3-5 days** in the fork, plus the controller (estimate made for 5.10.x; not re-estimated for master) | Partly: no restart, but link management stays inside the MCS. |
| C | No code: per-station links as in ff-mcs, and `POST /api/instances/{inst}:restart` to add one | None | 0 | No. Still a restart, and still resets counters. |

### 3.1 Where Option A runs: new service, not WDS
**Decision (recommended):** build the gateway as a **new service**. The pass-driven **controller** (control plane, analysis §6) drives the gateway; it does not carry frames. For jTYU the controller is the **Pass Mgmt module inside ESW** (§6 Q4, answered 2026-11-24 from D-35). The reasons below rule out WDS only as the *gateway*.

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
| Controller (jTYU: Pass Mgmt inside ESW, §6 Q4) | Control plane: pass schedule and the analysis §6 reconciler. It calls `POST /missions/{m}/sessions` on the gateway instead of enabling/disabling Yamcs links. |
| Yamcs (`pixxel/master`) | Mission logic: crypto, framing, PUS, and about 4 fixed links |

**If WDS is the controller** (not the case for jTYU, where Q4 chose Pass Mgmt; kept for a future MPS-driven mission), the new mission needs: gateway session calls in place of the `app/utils/mcs.py` enable/disable calls (`disable_non_pass_links`, `disable_link`); the station list read from the gateway registry instead of `config.py:295` `ANTENNAS`; and per-deployment config instead of a `main`/`main-us`-style branch (analysis §6.7 decision 4). The current FF/FFLY WDS is not changed.

## 4. Design points Option A must cover
1. **Where to split.** Anything **mission-specific** stays in Yamcs; anything **station-specific** moves to the gateway (detail in §5).
   - **Stays in Yamcs:** SRS4/SDLS encryption/decryption (SRS3 in ff-mcs), the SCID check, TC frame building and VC routing (`AbstractTmFrameLink.handleFrame`). Keys never leave the MCS.
   - **Moves to the gateway:** sockets, envelopes (KSAT T501, LeafSpace JSON), uplink channel coding (RS/CLTU/ASM) and the SatLab pre/postamble. Today these all sit in the `addNonsense()` tail.
2. **Station attribution.** Today the link name *is* the station: it is a TM tuple column and part of command history. The gateway must pass the station id with every frame, and the Yamcs-side link has to surface it as a parameter or tuple column.
   - For RTD-API stations, the station is in the topic and the STC block header, and the header time stamp is the ERT (ST-D-22). Station codes come from ISTRAC (ST-Q-35). Whether the LEOP provider gives ERT is open (PM-40).
3. **TC routing.** ff-mcs's implicit "first enabled link wins" rule (analysis §5 gap 2) becomes an explicit gateway rule: one active uplink station per satellite, chosen by the controller (analysis §6.3). This designs out the current "primary TC never enabled" fault (analysis §3b confirmed issue 1).
4. **Primary/secondary.** The gateway fans TM out to both instances and accepts TC and CFDP only from the primary. In the current deployment, WDS calls both instances.
   - jTYU has a main and a redundant MCS in two control centres (ST-D-09). The RTD-API servers are brokers, so both MCS may be able to subscribe directly, with no fan-out [inference]. Which servers each MCS uses and which MCS may publish TC is Q12.
5. **Status and alarms.** Yamcs then sees only the health of the gateway link. Per-station status has to come from the gateway as parameters, which could also close §5 gap 1 (no link-status events).
   - RTD-API stations give richer signals: TC session ack status and error codes, ack timeouts (station unreachable, not received, radiation not confirmed) and DUMMY TM blocks for data breaks (ST-D-23, ST-D-32, ST-D-33, ST-D-36). These should feed the same per-station status.
6. **TC session per pass (RTD-API stations; added 2026-11-24).** Starting a pass's TC means sending a TC session init to that station's STC and waiting for a success ack. Only then may TC flow. Each TC then has two acks with timeouts, matched by the satellite block data (ST-D-31 to ST-D-37). This state belongs to whichever component owns the station connection: the gateway (Option A) or the Yamcs RTD-API TC link (Option D). Open timing questions are in Q13.
- Cost to accept: one extra network hop, and the gateway becomes a single point of failure for every mission. It needs its own HA.
- "Cross-mission": the gateway is mission-agnostic if it stays transport-only, and each mission's Yamcs keeps its own framing and crypto.

## 5. Configurable gateway: any ground station × any transport
Goal: adding a ground-station vendor or a transport type should be **configuration only**. Code is needed only when a transport or envelope is genuinely new.

### 5.1 Four independent layers
Every station channel is a composition of the four layers below, and each layer is a plug-in chosen by name in config.

| Layer | Job | Initial plug-ins | Where it lives today (ff-mcs) |
|---|---|---|---|
| **Transport** | Connect, reconnect, move bytes or messages | `tcp-client`, `tcp-server`, `udp`, `websocket` (ws/wss), `mqtt` (tcp/ssl/**ws/wss**) | Socket in `TcpTmFrameLink`, `TcpTcMultiplexer`, `WebsocketsParameterDataLink`, Paho client in `Mqtt*Link` |
| **Envelope** | Unwrap downlink to frame + ERT + station id; wrap uplink | `ksat-t501`, `stc-block` (ISTRAC STC ICD), `length-prefix`, `json-field` (configurable field names, hex or base64), `raw` | T501 parsing in `TcpTmFrameLink`/`KsatPacketInputStream`; `{timestamp, payload}` JSON in `MqttTmFrameLink.java:279-281` |
| **Uplink coding** (TC only) | Station-specific channel coding | `rs`, `cltu`, `asm`, `satlab-pre-post`, `none`, chained in order | `TcpTcFrameLink.addNonsense()` |
| **Channel kind** | What the channel carries | `tm`, `tc`, `cfdp-up`, `monitoring` | `<ST>_Tm/_Tc/_Cfdp/_Monitoring` |

- A **profile** names one vendor's composition (KSAT, LeafSpace, …). A **station** is a profile plus host and credentials. A **mission** lists the stations it may use.
- **MQTT over WebSocket costs no extra code.** Eclipse Paho `mqttv3`, already in the ff-mcs `pom.xml:39-40`, accepts `ws://` and `wss://` broker URIs, so it is a URI change on the `mqtt` transport.
- **MQTT v5 is extra code.** ISTRAC RTD-API requires MQTT v5 (ST-D-13). Paho `mqttv3` does not support it; the separate Paho `mqttv5` client does. The `mqtt` transport needs a version option.

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
  istrac-rtd-api: # ISTRAC RTD-API + STC ICD (ST-D-12..18, ST-D-30, ST-D-31); one connection, station = topic
    tm:         { transport: mqtt, mqttVersion: 5, broker: "ssl://{host}:8883", tls: mutual,
                  topic: "/{user}/{spacecraft}/telemetry/{station}", envelope: stc-block }
    tc:         { transport: mqtt, mqttVersion: 5, broker: "ssl://{host}:8883", tls: mutual,
                  topic: "/{user}/{spacecraft}/command-transmit/{station}",
                  ackTopic: "/{user}/{spacecraft}/command-response/{station}",
                  envelope: stc-block, uplinkCoding: [none], session: stc-tc-session }

stations:
  - { id: HA2,  profile: ksat, host: 10.0.0.2 }
  - { id: HA7,  profile: ksat, host: 10.0.0.7 }      # add without any MCS restart
  - { id: LEAF, profile: leafspace, credentials: ${env.LEAFSPACE_CREDS} }
  - { id: biak-1, profile: istrac-rtd-api, host: TBD,  # name as in pass details (ST-D-11); host: ST-Q-11
      credentials: ${env.RTDAPI_CREDS} }

missions:
  - id: JTYU
    norad: TBD
    scid: TBD
    user: TBD          # RTD-API <user>/<spacecraft> values: ST-Q-19
    yamcs: { primary: "main centre", secondary: "redundant centre" }   # ST-D-09; switch-over: Q12
    stations: [biak-1, HA2]   # HA2 stands in for the LEOP vendor (Q6); removed after LEOP
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
Single list of everything still open across both plans, as of 2026-11-24. IDs are stable; other sections refer to them. When an item is answered, move it to 6.5 with the date. ST-, D- and PM- IDs live in the `gss-jtyu-prd` repo, `MCS/` folder (see "New mission inputs").

### 6.1 Pixxel decisions (owner)
| ID | Question | Current lean | Blocks |
|---|---|---|---|
| D1 | Approve the architecture: Option A (gateway) or Option D (one RTD-API link per kind in Yamcs). | Decide on Q6: Option D if the LEOP vendor comes through RTD-API; Option A (two profiles: RTD-API + LEOP vendor) if it connects directly. | All design work |
| Q2 | One gateway shared across missions, or one per mission? (Option A only.) | Shared ("cross-mission" goal), routing frames by SCID or NORAD. | Gateway config model, HA |
| Q3 | Gateway HA: active/standby, or one gateway per site? (Option A only.) | Now that jTYU has an MCS in each of two control centres (ST-D-09), one gateway per centre is the natural fit [inference]. Waits for Q12. | Deployment |
| Q9 | What happens to a TC released outside a comm window (reject, hold, or MOS decides), and which UMACS interface carries link/station status (TM parameter over MS-TM-ICD-002, or alarm over MS-AS-ICD-002)? | Reject visibly: a single TC's TC-003 status is SUCCESS or FAILED + Reason only (D-29), so this is FAILED with a reason. Publish station status (including the RTD-API ack timeouts, ST-D-33, ST-D-36) as TM parameters. Neither UMACS nor ISTRAC ICD covers station status yet. | TC handler, UMACS ICD |
| Q11 | Which TC paths need a continuous sequence count (`seqCounterName`) on `pixxel/master`? See §2. | Every PUS TC path that crosses a station change. Under Option D there is one TC link per instance, so a station change does not change links. | Instance config |

### 6.2 External inputs (ISRO, vendor)
| ID | Question | From | Blocks |
|---|---|---|---|
| Q6 | Which commercial vendor serves LEOP, and **does it reach the MCS through ISTRAC RTD-API** (RTD-API Sec. 1 lists KSAT, Leaf Space and others as agencies ISTRAC buys from) **or directly**? Does it give ERT with each frame (PM-40)? Can one pass hand over between stations? Known: routine ops use ISTRAC and SBS-3 stations (ST-D-10). | Pixxel ops / ISRO | D1, LEOP profile, controller rules |
| Q7 | Comm-window interface from the Master Scheduler: format, transport, push or pull, updates and cancellations, time reference. Known: arrives before AOS (D-25), goes to Pass Mgmt (D-35), names the station (ST-D-11, station name form is an assumption). The Master Scheduler ICD has not been received. | ISRO | Controller input |
| Q10 | Who schedules the commercial LEOP passes? If the Master Scheduler does not issue windows for them, the controller needs a second source (vendor booking API or a Pixxel-side plan). If LEOP passes come through RTD-API (Q6), the Master Scheduler likely covers them [inference]. | Pixxel ops / ISRO | Controller input |
| Q12 | How do the four RTD-API servers (primary and redundant in each of two control centres) work together: which publish jTYU TM, where to publish TC, which send acks, how a client handles a server switch-over? And how do the main and redundant MCS share the work: which is active, does the standby receive TM, does each MCS use only its own centre's servers? (Added 2026-11-24; tracked as ST-Q-15 and PM-39; Pixxel follow-up ST-Q-10 on de-duplicating TM.) | ISTRAC / NSIL | Q3, §4 point 4, TC rule for the secondary |
| Q13 | ISTRAC items that set controller parameters (added 2026-11-24): can a TC session open before AOS, how early, and how is it closed (ST-Q-47; decides whether the `aos-60` margin works for TC); init-ack timeout (ST-Q-56); transmit-ack timeouts and how a rejected TC is reported (ST-Q-62); pending TC requests allowed before the 2nd ack (ST-Q-59); wildcard subscription across stations (ST-Q-21); one STC block per MQTT message (ST-Q-23). | ISTRAC | Controller timing, TC link design |

### 6.3 Checks Pixxel can do alone
| ID | Check | Where | Needed if |
|---|---|---|---|
| V1 | SRS4 nonce/IV handling on master: is any state tied to a link? | `tctm/ccsds/srs4/` on `pixxel/master` (§2 table) | Always |
| V2 | Does `AbstractTmFrameLink.handleFrame` (master :134) keep the 5.10.x redirection and SCID logic? | `pixxel/master` | Always (the `GatewayTmFrameLink` or RTD-API TM link extends it) |
| Q1 | Does LeafSpace expect a CLTU or a bare TC frame on `{norad}/uplink`? (`MqttTcFrameLink.java:238` publishes `readyFrame`; encoding not checked.) Not needed for RTD-API stations: the MCS sends bare frames and ISTRAC codes them (ST-D-30). | ff-mcs | Q6 answer is LeafSpace, connected directly |
| V4 | Re-estimate Option A (4–7 weeks), B (3–5 days, a 5.10.x estimate) and the new Option D against `pixxel/master` and the jTYU scope. | this plan §3 | Before D1 sign-off |
| V5 | Where does `pixxel/master` keep the SDLS ARSN per security association: per link or shared, in memory or persisted? jTYU needs it to survive restarts (MCS-DR-009). Added 2026-11-24. | SDLS code on `pixxel/master` (`CcsdsTcSdlsConfiguration` and its users) | Always |

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
| Q5: station-side ICD | The MCS never talks to the baseband; it is an MQTT v5 / TLS 1.3 client of ISTRAC's RTD-API servers, one STC block per message, station chosen by topic, ISTRAC does the CLTU coding. Byte layouts still pending with ISTRAC (ST-Q-29, ST-Q-51, ST-Q-52). | 2026-11-24 (ISTRAC drafts received 2026-11-12; Pixxel decisions 2026-11-13 to 2026-11-17) | "ISTRAC station ICDs"; ST-D-03, ST-D-12 to ST-D-18, ST-D-30 |
| Q8: redundancy | A main and a redundant MCS, one in each spacecraft control centre, each the other's failsafe. How the switch-over works moved to Q12. | 2026-11-24 (decided 2026-11-13) | ST-D-07, ST-D-09 |
| Q4: where the controller runs | The Pass Mgmt module inside ESW. It owns the comm window, AOS and LOS; the MCS does not detect AOS/LOS itself. Not built yet (PM-16). | 2026-11-24 (decided 2026-10-30) | D-35 |
| V3: `yamcs-sle` compatibility | Dropped: the station interface is MQTT (RTD-API), not SLE. | 2026-11-24 | "ISTRAC station ICDs" |

## Verification (of the claims above)
1. `grep -n "registerLink(\|unregisterLink(" yamcs-core/src/main/java/org/yamcs/management/LinkManager.java`: only internal call sites.
2. `grep -n "static" yamcs-core/src/main/java/org/yamcs/tctm/CcsdsSeqCountFiller.java` on `9fbfc03a2`: the seq map is static and keyed by APID. On `pixxel/master` the same grep shows only the named map (`namedSeqCounts`). The default per-instance map is set in the constructor, so check `PusCommandPostprocessor.java:121,216-217` too.
3. In each ff-mcs branch, `grep -c -i "useCop1: *true" src/main/yamcs/etc/yamcs.FFLY01-primary.yaml` (`ffly/prod/base`) or `yamcs.FF01-primary.yaml` (`ff/prod/base`) returns 0.
4. On a test server, `POST /api/instances/FFLY01-primary:restart` picks up a newly added link without a JVM restart. (Matters only for Option C.)
