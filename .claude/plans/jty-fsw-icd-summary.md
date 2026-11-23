# JTY FSW Space-to-Ground TM/TC ICD: summary

Living summary of the Jatayu FSW Space-to-Ground TM/TC ICD, for any feature in this fork (time-tag queue, PUS, links, frames). Update it when the ICD changes. Keep the "Source and status" and "Open issues" sections current.

Source: `/Users/nikhil/Downloads/JTY_FSW_SpaceToGround_TM_TC_ICD_v2 - WIP.docx` (WIP copy, file mtime 2026-11-12). Text extracted to `/private/tmp/claude-503/-Users-nikhil-GSS-Workspace-yamcs-Pixxel-fork/e302608b-b391-4745-8469-33be2aae7dc8/scratchpad/icd.txt`. The scratchpad is session-only, so re-extract with `textutil -convert txt -stdout "<docx>"` if it is gone.

## 1. Source and status

- Document: "Space to Ground TM/TC ICD", Mission JATAYU, Pixxel Technologies, subsystem FSW, document code JTY-FSW-Space-to-Ground-TM-TC-ICD, version V2.
- Dates conflict: cover says Sep 24, 2025; revision table says V02 on Dec 10, 2025 (V01 Feb 21, 2025).
- Sign-off table lists one signatory (Yogesh Modi, FSW). Marked "preliminary"; formats "prone to changes".
- Several items are "Will be added in the next release": Appendix 5 (PUS source data structures), Appendix 6 (SDLS PDUs), Appendix 8 (UDP IP/port numbers).
- Applicable documents: AD-01 CCSDS 132.0-B-3 (TM SDLP); AD-02 CCSDS 232.0-B-4 (TC SDLP); AD-03 CCSDS 355.0-B-1 (SDLS; the body text cites B-2); AD-04 CCSDS 355.1-B-1; AD-05 ECSS-E-70-41C (PUS; the standard is ECSS-E-ST-70-41C); AD-06 CCSDS 131.0-B-4; AD-07 CCSDS 231.0-B-4; AD-08 CCSDS 301.0-B-4 (time codes); AD-09 CCSDS 232.1-B-2 (COP-1).

## 2. Data flow and layers

Five layers (ICD §3.1):

| Layer | Protocol | Function |
|---|---|---|
| 5 | ECSS PUS; CCSDS CFDP (727.0-B-5) | Services; file delivery |
| 4 | CCSDS Space Packet Protocol (SPP) | Packet structure, APID addressing |
| 3 | CCSDS SDLS | Authentication, encryption |
| 2 | CCSDS TM/TC SDLP | Frames, sync, error detection |
| 1 | CSP + UDP | Routing, physical interface |

Encapsulation figures are not in the text export (Figures 1 and 2).

## 3. Data link layer

### 3.1 TM Space Data Link Protocol (§4.1, Appendix 3)

- Transfer frame length: **1020 bytes** (§4.1, "max supported by Radio"). Appendix 3 says **980 bytes** (fixed). The two disagree.
- Version 0b00. Spacecraft ID: TBD.
- Frame error control: CRC-32 over the whole frame except the CRC field.
- OCF flag 0 (not used). Secondary header flag 0. Master channel secondary header and MC_OCF absent. VC secondary header and VC_OCF absent.
- Master and VC frame counts: modulo 256.
- Data field sync flag 0, packet order flag 0, segment length identifier `11`.
- First Header Pointer points to the first packet starting in the frame data field.
- Data field content: packets (not VCA_SDU).
- Virtual channels (Master Channel, Appendix 3, VCIDs 0–7):

| VCID | Channel |
|---|---|
| 0 | OBC RTM (real-time) |
| 1 | OBC STM (stored) |
| 2 | OBC File |
| 3 | OBC IDLE |
| 4 | Off-nominal RTM |
| 5 | Off-nominal File |
| 6 | Payload RTM |
| 7 | Payload File |

- Packet delivery (Appendix 3): incomplete packets **Required** to be delivered. §7.1 says **Not required**. Conflict. See §8.

### 3.2 TC Space Data Link Protocol (§4.2, Appendix 1)

- TF version 0b00. Bypass flag 0 (COP not used). Control command flag 0 (FDU, type D).
- Frame length: 10-bit field, value = octets − 1.
- Max frame length 980 bytes (ETH limit). Max data unit to coding sublayer 980 bytes. Max bit rate 64 kbps. Max frames per data unit: 1. Repetitions: 1.
- Valid spacecraft IDs: 1–n. Frame error control: present.
- Valid VCIDs (Master Channel):

| VCID | Channel |
|---|---|
| 0 | OBC |
| 1 | SUP A |
| 2 | SUP B |
| 3 | EPC P |
| 4 | EPC S |
| 5 | IN P |
| 6 | IN S |
| 10 | PAY |

- COP in effect: 1 (COP not in use in practice). CLCW version 1.
- Packet transfer: incomplete packets **Not required** to be delivered.

## 4. SDLS (§5)

- Services: data authentication, confidentiality, integrity, anti-replay (sequence numbers).
- Key management: OTAR (over-the-air rekeying), key lifecycle, key verification, key inventory.
- Security association (SA) states: Null, Unkeyed, Keyed, Operational. Procedures: create/delete SA, start/stop SA, rekey/expire SA, set and read ARSN (and window), SA status, read ARSN.
- Monitoring and control: ping, log status / dump log / erase log, self-test, alarm flag reset.
- Frame Security Report (FSR) in the OCF reports bad MAC and bad sequence number flags.
- SDLS PDUs: Appendix 6, "Will be added in the next release".
- Field list in §5.2 (SPI, IV, sequence number, PAD length, MAC) has no widths yet.

## 5. Space packet protocol (§6, Appendix 4)

### 5.1 Common packet header

- Version: 000 for all onboard TM. Ground rejects others with an alarm. (Protocol version number, Appendix 4: 0.)
- Type indicator: 0 for TM. The TC section repeats "must be zero for telemetry" (copy error; TC type is 1 in CCSDS).
- Secondary header flag: 1, except the time packet (APID 0) and idle packets (APID 2047) which use 0.
- APID: 11 bits = node ID (5 bits) + app ID (6 bits). Reserved: APID 0 (time packet), APID `11111111111` (2047, idle).
- Sequence flags (2 bits): `00` continuation, `01` first, `10` last, `11` standalone.
- Sequence count (14 bits): per APID; wraps at 2^14 − 1 to 0. The ground raises an alarm for each missing block.
- Packet data length: value = octets in data field − 1.
- Max packet length: 256 bytes (Appendix 4, flexible). Max user data length (data field, excluding header): **≤ 248 bytes (approx.)** (Appendix 4).
- Maximum packet length (Appendix 3 TM): not fixed in the text export.
- Time code: CUC, 4 bytes coarse + 2 bytes fine (App 4).

### 5.2 Grouping flags: TM vs TC

- **TM (§6.1):** the grouping flags are defined (01 first, 00 continuation, 10 last, 11 standalone). The note says: "We will be mostly using the standalone packets, but grouping might be required in services like memory dump."
- **TC (§6.2):** Jatayu sets the grouping flags to `11` (standalone) for every TC.

### 5.3 APID map (Appendix 4)

Node ID (5 bits) + app ID (6 bits). Hex values are "for illustration — final values subject to ICD confirmation".

| Node | Node ID | Apps (app ID → APID hex) |
|---|---|---|
| OBC | 00001 | TIME 000001 (0x21), FSW 000010 (0x22), AOC 000011 (0x23), EPS 000100 (0x24), THM 000101 (0x25), COM 000110 (0x26), PRP 000111 (0x27), PAY 001000 (0x28) |
| EPC | 00010 | TIME (0x41), FSW (0x42), EPS (0x43), THM (0x44) |
| IN | 00011 | TIME (0x61), FSW (0x62), EPS (0x63), AVI 000100 (0x64) |
| SUP | 00100 | (table not given in the text export) |

## 6. Secondary headers and PUS (§7.1, Appendix 4)

### 6.1 TC secondary header (PUS version 2)

- PUS version 0010.
- Ack flags: bit 3 acceptance, bit 2 start, bit 1 progress, bit 0 completion. Each bit requests the report (1) or not (0).
- Service type, subtype, source ID (the commanding source), spare.

### 6.2 TM secondary header (PUS version 2)

- PUS version 0010. SC time reference status (valid or invalid on-board time).
- Service type, subtype, message type counter (set to 0 for every packet).
- Destination ID: 0x0000 = ground (FF1 always ground).
- Time: 8 bytes, "timestamp of the related report". **Conflict:** CUC is 4 + 2 = 6 bytes (Appendix 4). See §8.
- Spare: extra info for ground decoding "in some cases".

### 6.3 Services listed (§7.1)

| Service | Function |
|---|---|
| Telecommand verification | Acceptance and completion reports |
| Device command distribution | Route commands to devices |
| Housekeeping and diagnostic | Periodic status |
| Event reporting | Autonomous anomaly / status events |
| Memory management | Read/write memory |
| Time management | Time sync |
| On-board operations scheduling | Time-tagged command execution |
| On-board monitoring | Limit checking |
| On-board storage and retrieval | Data storage |
| Event action | Autonomous action on events |
| On-board parameter management | Parameter config |
| Parameter statistics | Statistics |

Scheduling (service 11) is the time-tag queue in this fork (see `time-tag-queue-plan.md`). The ICD does not give its TM/TC sub-services; Appendix 5 is pending.

## 7. CFDP (§7.2)

- CFDP Class 1 (unacknowledged, simplex). No ACKs.
- PDUs used: Metadata PDU (first in a transaction), File Data PDU (one segment per PDU).
- Fixed header: version 1; PDU type (0 directive, 1 data); direction; transmission mode 1 (Class 1); CRC flag 1 (CRC present); large file flag; PDU data field length; segmentation control (0 octet stream, 1 variable-length records); entity ID length; segmentation metadata flag; transaction sequence number length; source and destination entity IDs.

## 8. Telemetry types and modes (§9.1)

- Real-time modes:
  - **Beacon:** small health set, always pulsed.
  - **Burst:** nominal TM at max throughput, planned passes only.
  - **Stored:** when no planned pass, TM is kept on board.
- Stores: bounded stores (critical events, injection TM) and circular stores (nominal stored TM).
- Types: simply commutated (≤ 1 Hz); super commutated (1–20 Hz); event reports; request-based (config, schedule, raw memory read).

## 9. Telecommands (§9.2) and files (§9.3)

- Real-time TCs execute on receipt. Time-tagged TCs execute at the time in the command.
- Telecommand file: many TCs in one file, processed with one TC, real-time or time-tagged.
- File uses: log download, firmware patch uplink, stored TM downlink. File transfer via CFDP (§7.2).

## 10. Network layer (§8, Appendix 7)

### 10.1 CSP

- CSP v1.x, 32-bit header. Chosen for radio compatibility. TCP/IP-like design.
- CAN fragmentation (CFP): CAN carries 8-byte chunks; receiver reassembles.
- Routing via a route table; the radio is the "via" address from satellite to ground.
- Deduplication: redundant TC retransmissions; the dedup window depends on COP-1 retransmission count and TC transit time.
- Port map, OBC1/OBC2 (CSP-ID 1, cold-redundant hub; only one active):

| Service for node | Port |
|---|---|
| EPC requests | 11 |
| SUP requests | 12 |
| PAY requests | 13 |
| IN requests | 14 |
| SBT1 response (OBC S-port) | 15 |
| SBT2 response (OBC S-port) | 16 |
| Ground commands | 20 |

- Other nodes: SUP (CSP-ID 2, 3), EPC (4, 5), IN (6, 7), PAY (8). Each listens on port 10 for OBC requests (only the active OBC) and port 20 for ground commands.

### 10.2 UDP

- Overheads: UDP 8 bytes, IPv4 20 bytes, Ethernet 14 bytes, total 42 bytes.
- Routing: same as CSP, table in Appendix 8 (IP and port numbers not yet given).

## 11. Open issues (conflicts and gaps)

| # | Issue | Where | Impact |
|---|---|---|---|
| 1 | TM incomplete-packet policy: "Required" (App 3) vs "Not required" (§7.1) | §7.1 vs App 3 | A lost middle packet of a multi-packet TM[11,13]. Blocks B1 |
| 2 | TM frame length 1020 (§4.1) vs 980 (App 3) | §4.1 vs App 3 | Downlink sizing (B6) |
| 3 | TM time field 8 bytes (§7.1) vs CUC 4+2 = 6 bytes (App 4) | §6.2 vs §5.1 | TM decode of the time field |
| 4 | TM grouping: "mostly standalone, grouping might be needed for memory dump" | §6.1 | Multi-packet TM[11,13] is not defined. Blocks B1 |
| 5 | Max user data ≤ 248 bytes vs max packet 256 bytes | App 4 | Split size for large reports |
| 6 | Appendix 5 (PUS source data structures) not written | App 5 | TM[11,13] layout and request IDs unknown. Blocks B2 |
| 7 | SDLS field widths and PDUs not written | §5.2, App 6 | SDLS decoding |
| 8 | UDP IP and port numbers not written | App 8 | UDP link config |
| 9 | Spacecraft ID TBD | §4.1 | Frame config |
| 10 | TC type indicator text says "telemetry", should be TC | §6.2 | Doc copy error |
| 11 | Cover date (Sep 2025) vs revision table (Dec 2025) | cover, p.2 | Version control |
| 12 | Duplicate figure numbers (e.g., Figure 4, 5, 8, 9 each used twice) | list of figures | Doc quality |
| 13 | SDLS cited as 355.0-B-2 in §5.1 but B-1 in AD-03 | §5.1 vs AD-03 | Reference |
| 14 | Appendix 1 (TC) and Appendix 3 (TM) packet-transfer tables: "Maximum packet length" blank | App 1, App 3 | Limits |
| 15 | APIDs marked "subject to ICD confirmation" | App 4 | Our APID mapping |

## 12. Relevance to the time-tag queue (PUS 11)

- **B1 (multi-packet TM[11,13]):** ICD §6.1 says TM grouping is used only for "services like memory dump". Packet splitting for schedule reports is not defined. Issue 1 and 4 above make a lost packet drop the whole report, which the Collector handles by discarding the partial report.
- **B2 (request-ID widths, `n` width):** Appendix 5 not written. Our 16/11/14 widths are unconfirmed.
- **B6 (queue size, downlink):** max user data ≤ 248 bytes per packet; TM frame 980 or 1020 bytes. Use these numbers for the size estimate.
- **Time tags:** CUC 4+2 bytes (App 4). Our release-time conversion uses the same time code. TM time field 8 bytes vs 6 bytes is unresolved (issue 3). Relevant to B3 (1 ms offset).
- **Virtual channels:** the TC uplink VCIDs and the TM VCIDs above are the ones to use in the link configs (`examples/pus-frames`).
- **Service 11 / scheduling:** listed in §7.1 as "time-tagged command execution". The ICD gives no sub-service list. Our TC[11,x] and TM[11,13] design rests on ECSS, not this ICD.

## 13. Update log

- 2026-11-12: first summary, from the WIP ICD (V2). Issues 1–15 recorded. Linked from `time-tag-queue-plan.md` §6.1 (B1, B6).
