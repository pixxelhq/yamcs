# Feasibility: move link management out of the MCS

## Context
The roadmap item reads: *"TM-TC Chain: Cross-Mission Link Management. Move link management outside of the MCS (Owner: Nikhil). Objective: eliminate MCS restarts when adding or removing links. Dependency: confirm whether Yamcs keeps fixed sequence counters per data link."* This section checks that item against release `5.10.12-Pixxel-9.4.2` (`pixxel/5.10.x`) and ff-mcs.

## Verdict
The redesign is **feasible**, and the dependency question is answered **in favour** of it: no counter that matters is tied to a link in the current setup. The real work is elsewhere: station attribution, TC routing, primary/secondary fan-out, and status reporting all have to move into a new component.

## 1. Why adding a link needs a restart today (verified)
- `LinkManager` reads `dataLinks:` only in its constructor. It has no public `addLink` or `removeLink`.
  - `registerLink`/`unregisterLink` (`LinkManager.java:344/372`) are private and are called only from `createDataLink` (:234) and `stopLinks` (:331).
  - `LinkListener.linkAdded/linkRemoved` therefore fire only at instance start and stop.
- Restarting the instance alone is enough; the JVM can stay up. `YamcsServer.restartInstance` (:383) reloads the instance YAML (:398), and REST exposes it as `POST /api/instances/{inst}:restart`.
  - The restart still drops processors, command queues, in-flight CFDP transactions and the in-memory sequence counters (see §2).
- In practice, links are added only when a new station appears. All 50 KSAT stations are already defined, disabled.
  - The `HA7` gap (in WDS `ANTENNAS` but missing from the ff-mcs YAML) is the live example of this restart requirement.

## 2. Dependency answer: sequence counters per data link (verified)
| Counter | Where it lives | Tied to a link? | Impact of moving links out |
|---|---|---|---|
| PUS/CCSDS packet seq count (TC) | `PusTcManager.java:81` → `CcsdsSeqCountFiller.java:15`, a **`static` map keyed by APID** | **No**, it is assigned before link selection | None, as long as `PusTcManager` stays in Yamcs. ⚠ It is in memory only and resets to 0 on every restart; it is also JVM-wide. |
| TC frame seq N(S) | `Cop1TcPacketHandler` vS, per link per VC (`TcManagedParameters.java:100-104`) | Would be, but **COP-1 is off** (`useCop1` appears 0 times in ff-mcs) | None today. BD frames (`TcPacketHandler.java:117`) carry no meaningful seq. If COP-1 is turned on later, one link per station would split vS. A single gateway-facing link avoids that. |
| SRS3 AES-GCM nonce | `GCM.java:43,59-62`: a random 12-byte nonce for each frame | No | No state to synchronise. |
| TM VC frame counters | Per-link VC handlers and ff-mcs `TcpTmFrameLink` counters | Yes, but statistics only | Only the per-station counters in `getExtraInfo` are lost. |
| Monitoring `sequenceCount` | `WebsocketsParameterDataLink.java:36`, `MqttParameterDataLink.java:55` | Yes | Cosmetic only. |

**Conclusion:** Yamcs does **not** keep sequence counters per data link that need protecting in this configuration. The counter that matters (PUS seq per APID) is per JVM and is lost on **restart**. That is a further argument for removing restarts.

## 3. Options (recommendation first)
| # | Option | What changes | Effort (rough) | Meets the objective? |
|---|---|---|---|---|
| **A (rec.)** | **Ground-station gateway service** outside Yamcs. It owns the KSAT TCP (56030/51103), KSAT websocket and LeafSpace MQTT connections. Each instance gets **one stable link per kind** (Tm, Tc, Cfdp, Monitoring) pointing to the gateway. | New service; WDS drives the gateway instead of the Yamcs REST API; the ff-mcs YAML shrinks from 204 links to about 4. | **4-7 weeks** (service with the configurable plug-in model of §5, Yamcs-side links, migration, KSAT and LeafSpace testing) | Yes. Adding a station becomes gateway config only. |
| B | Patch the fork: add `LinkManager.addLink(spec)/removeLink(name)` plus a `LinksApi` create/delete and proto. | Wire the TC stream subscriber, register system parameters, persist the memento. | **3-5 days** in the fork, plus a WDS change | Partly: no restart, but link management stays inside the MCS. |
| C | No code: use `POST /api/instances/{inst}:restart` | None | 0 | No. Still a restart, and still resets counters. |

## 4. Design points Option A must cover
1. **Where to split.** Anything **mission-specific** stays in Yamcs; anything **station-specific** moves to the gateway (detail in §5).
   - **Stays in Yamcs:** SRS3 encryption/decryption, the SCID check, TC frame building and VC routing (`AbstractTmFrameLink.handleFrame`). Keys never leave the MCS.
   - **Moves to the gateway:** sockets, envelopes (KSAT T501, LeafSpace JSON), uplink channel coding (RS/CLTU/ASM) and the SatLab pre/postamble. Today these all sit in the `addNonsense()` tail.
2. **Station attribution.** Today the link name *is* the station: it is a TM tuple column and part of command history. The gateway must pass the station id with every frame, and the Yamcs-side link has to surface it as a parameter or tuple column.
3. **TC routing.** The "first enabled link wins" rule (§5 gap 2) becomes an explicit gateway rule: one active uplink station per satellite. WDS ON/OFF tasks call the gateway instead. This also fixes confirmed issue 1 (primary TC never enabled).
4. **Primary/secondary.** The gateway fans TM out to both instances and accepts TC and CFDP only from the primary. Today WDS calls both instances.
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

stations:
  - { id: HA2,  profile: ksat, host: 10.0.0.2 }
  - { id: HA7,  profile: ksat, host: 10.0.0.7 }      # add without any MCS restart
  - { id: LEAF, profile: leafspace, credentials: ${env.LEAFSPACE_CREDS} }

missions:
  - id: FFLY01
    norad: 99999
    scid: 0x01
    yamcs: { primary: "...", secondary: "..." }
    stations: [HA2, HA7, LEAF]
```

### 5.3 Fixed interfaces (the parts that do not vary)
1. **Northbound to Yamcs.** One envelope for every station: `{kind, stationId, ert, frame}` over a single TCP or WebSocket connection per instance.
   - Yamcs needs only 3 generic link classes: `GatewayTmFrameLink` (extends `AbstractTmFrameLink`), `GatewayTcFrameLink` and `GatewayParameterLink`.
   - The ff-mcs YAML goes from 204 links to about 4, and never changes when a station is added.
2. **Control API for WDS and operators:**
   - `PUT/DELETE /stations/{id}`: add, update or remove a station hot. Persisted.
   - `POST /missions/{m}/sessions {station, aos, los, uplink}`: start or stop a pass session.
   - A status stream that reports per-station state as Yamcs parameters (for example `/GS/<station>/status`).
3. **Plug-in SPI:** three interfaces, `Transport`, `Envelope` and `UplinkCoder`.
   - A new vendor on existing transports is a config change only.
   - A new transport or envelope is one class plus a registry entry.

### 5.4 Open questions to verify before design sign-off
1. Does LeafSpace expect a CLTU or a bare TC frame on `{norad}/uplink`? (`MqttTcFrameLink.java:238` publishes `readyFrame`; its encoding has not been checked.)
2. Should there be one gateway shared across missions or one per mission? "Cross-mission" points to shared, with frames routed by SCID or NORAD.
3. Where does gateway HA come from: active/standby, or one gateway per site?

## Verification (of the claims above)
1. `grep -n "registerLink(\|unregisterLink(" yamcs-core/src/main/java/org/yamcs/management/LinkManager.java`: only internal call sites.
2. `grep -n "static" yamcs-core/src/main/java/org/yamcs/tctm/CcsdsSeqCountFiller.java`: the seq map is static and keyed by APID.
3. `grep -c -i "useCop1: *true" ff-mcs/src/main/yamcs/etc/yamcs.FFLY01-primary.yaml` returns 0.
4. On a test server, `POST /api/instances/FFLY01-primary:restart` picks up a newly added link without a JVM restart.
