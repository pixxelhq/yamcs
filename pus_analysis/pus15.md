# PUS ST[15] On-Board Storage and Retrieval

This document describes the agreed Hermes/pymdb ground-side profile for
ECSS-E-ST-70-41C §8.15, Figures 8-159 through 8-193. It replaces the earlier
16-bit-store-ID/raw-millisecond example profile. The Java example simulator uses this payload profile; see
[Simulator compatibility](#simulator-compatibility) for configuration and remaining
header/body differences in the legacy example instance.

## Implementation and responsibility

Hermes defines **30 TC subtypes and 7 TM subtypes** through 37 primary builders
in [service15.py](../../tools/hermes/app/services/adapters/xtce/pus/service15.py),
plus explicit zero-count convenience constructors. TC[15,24] produces three
concrete commands, giving 32 primary TC definitions and seven primary TM
containers. Subtypes 7, 8, and 10 are undefined and have no builders.
TC[15,24], TC[15,26], and TC[15,27] are included.

Ground-side work covers command encoding, standard TC verification, and telemetry
decoding. Packet storage, packet selection, copy operations, retrieval scheduling,
and capacity management execute on board or in a compatible simulator. A Yamcs
runtime containing the shared time-codec and aggregate-array fixes is required;
this does not introduce an on-board storage engine into `yamcs-core`.

| Component | Source and role |
|---|---|
| Service 15 builders | [Hermes service15.py](../../tools/hermes/app/services/adapters/xtce/pus/service15.py): explicit functions following the Service 14 style |
| Registration | [Hermes generic.py](../../tools/hermes/app/services/adapters/xtce/generic.py): registers every builder and all copy-window variants |
| Configuration entry point | [Hermes xtce_artifacts.py](../../tools/hermes/app/services/xtce_artifacts.py): supplies settings once when constructing the header |
| Shared header | [pymdb pus.py](../../tools/pymdb/src/yamcs/pymdb/pus.py): stores `cuctime_fields: CucTime` and optional `tco_service` |
| Time algorithms | [TimeBinaryEncoder](../yamcs-core/src/main/java/org/yamcs/algo/TimeBinaryEncoder.java) and [TimeBinaryDecoder](../yamcs-core/src/main/java/org/yamcs/algo/TimeBinaryDecoder.java): CUC with epoch or TCO conversion |
| Example simulator | [Pus15Service](../simulator/src/main/java/org/yamcs/simulator/pus/Pus15Service.java): on-board emulation of this payload profile |

## Field types and presence

Every count, including nested counts, is an unsigned 8-bit integer by default.
Relevant non-time width overrides remain keyword-only builder arguments.

| Field | Default representation |
|---|---|
| Application-process ID (`apid`) | 11 bits, unsigned; engineering labels from `system.extra["apids"]` |
| Service/message type and subtype | 8-bit unsigned integers; no choice mappings |
| `n_count`, `service_count`, `subservice_count`, `structure_id_count`, `event_count` | 8-bit unsigned integers |
| Packet-store IDs, including copy source/destination IDs | 8-bit unsigned integers |
| `packet_store_size` | 8-bit unsigned integer; allocation units are mission-defined |
| `retrieval_priority`, `subsampling_rate` | 8-bit unsigned integers |
| Both filling percentages | 8-bit unsigned integers |
| `virtual_channel` | 8-bit unsigned integer until channel definitions are finalized; no choice mapping |
| Store type and retrieval/storage status | 8-bit enumerations described below |
| Structure and event identifiers | 8-bit mission-specific enums; optional raw-value-to-label mappings, numeric string labels when absent |
| Every absolute-time field | CUC derived from the header's `CucTime`; default 48 bits |
| Other non-time fields | 8 bits unless a width override is supplied |

The wire layouts **always include fields marked optional by the standard** for
this profile: APID selection blocks, retrieval priorities, store type, virtual
channel, time-range retrieval status, and subsampling where specified. There
are no `include_*` switches or additional presence-flag bytes. The copy-window
selector still determines which time tags apply to TC[15,24].

APIDs are bit-packed without five-bit padding between entries. Arrays retain
explicit count fields even when their count is zero. A zero count produces an
empty array and retains the standard's applicable wildcard meaning; it does not
remove surrounding fields or select a different packet layout. Only TC[15,18]
and TC[15,22] have no application-data fields in the generated command set.

### Enumerations

| Field | Raw 0 | Raw 1 |
|---|---|---|
| `packet_store_type` | Circular type (`Circular_Type`) | Bounded type (`Bounded_Type`) |
| `storage_status` | Disabled (`Disabled`) | Enabled (`Enabled`) |
| `open_retrieval_status` | Suspended (`Suspended`) | In progress (`In_Progress`) |
| `time_range_retrieval_status` | Disabled (`Disabled`) | Enabled (`Enabled`) |

The storage-status raw mapping is a mission assumption. The other status and
store-type meanings follow the agreed standard mapping. Service types,
subtypes, priorities, percentages, and virtual channels are integer fields.
`service_type_choices`, `subservice_type_choices`, and `virtual_channel_choices`
are removed from the builders.

## Shared time configuration

Construct the header once, then pass that header to generic generation and the
service builders. The configuration is retained as a `CucTime` object, rather
than separate `p_field` and `epoch` fields on `PusHeader`.

```python
import json
import yamcs.pymdb as Y
import yamcs.pymdb.pus as pus
from app.services.adapters.xtce.generic import add_generic_pus_artifacts

system = Y.System(
    "Mission",
    extra={"apids": json.dumps({"Time": "0", "ADCS": "103"})},
)
pus_header = pus.add_pus_header(
    system=system,
    cuctime_fields=pus.CucTime(epoch=Y.Epoch.UNIX, pfield=46),
    tco_service=None,  # Optional Yamcs TimeCorrelationService name.
)
add_generic_pus_artifacts(system=system, pus_header=pus_header)
```

The common settings are read through:

```python
pus_header.cuctime_fields.pfield
pus_header.cuctime_fields.pfield_contd
pus_header.cuctime_fields.epoch
pus_header.tco_service
```

Service 15 and generic generation have no separate `time_size`, `p_field`,
`time_epoch`, extension-octet, or `tco_service` arguments. Individual service
builders do not repeat time-format defaults. Construction helpers accept the
mission configuration and forward it to `pus.add_pus_header`.

Hermes settings are:

```dotenv
EPOCH=UNIX
P_FIELD=46
# For an extended format: P_FIELD=0xAE, P_FIELD_CONTD=0x20
# P_FIELD_CONTD=0x20
# TCO_SERVICE=your_tco_service
```

`EPOCH` defaults to UNIX and supports UNIX, GPS, TAI, and J2000. `P_FIELD`
defaults to decimal 46 (`0x2E`). `TCO_SERVICE` has no default service name; an
omitted or blank name is normalized to `None`. `P_FIELD_CONTD` is optional and
accepts decimal or hexadecimal input; it is required when `P_FIELD` has bit 7
set. No database migration is needed.

### CUC layout and precision

Service 15 and the raw TM header derive their widths from the shared
`CucTime`. Its optional extension octet is `pfield_contd` in Python and is
emitted as Yamcs's canonical `pFieldCont` configuration key. Both octets are
implicit metadata and are excluded from the transmitted time-value width:

```python
basic_bytes = 1 + ((pField >> 2) & 0x03)
fractional_bytes = pField & 0x03
if pField & 0x80:
    basic_bytes += (pFieldContd >> 5) & 0x03
    fractional_bytes += (pFieldContd >> 2) & 0x07
time_bits = 8 * (basic_bytes + fractional_bytes)
```

| pField | Basic bytes | Fractional bytes | XTCE width |
|---|---:|---:|---:|
| 44 (`0x2C`) | 4 | 0 | 32 bits |
| 46 (`0x2E`, default) | 4 | 2 | 48 bits |
| 47 (`0x2F`) | 4 | 3 | 56 bits |
| 174 (`0xAE`), extension 32 (`0x20`) | 5 | 2 | 56 bits |
| 174 (`0xAE`), extension 64 (`0x40`) | 6 | 2 | 64 bits |
| 172 (`0xAC`), extension 8 (`0x08`) | 4 | 2 | 48 bits |

**Basic bytes carry whole seconds. Fractional bytes carry a standard binary
fraction of a second.** Both components are big-endian. With two fractional
bytes, the fraction is in units of 1/65536 second; Yamcs presents the result at millisecond precision. For a
millisecond value `m` within a second, encoding uses
`floor(m * 65536 / 1000)`. Thus `.123` seconds encodes as **8060 (`0x1F7C`)**,
not the literal integer 123 (`0x007B`). Exact `.000`, `.125`, and `.500`
fractions must not gain an extra millisecond during decoding.

The p-field is configuration metadata and is not transmitted before each time
tag. There is no FIXED binary-time schema or raw 64-bit millisecond payload.
Changing either octet recomputes the Service 15 and raw header widths,
including decoder sizes, container sizes, and overlay offsets. A zero-fraction
layout omits the raw `fractional_time` member. A narrower coarse field limits
the range of representable seconds from the chosen epoch.

An extended first octet requires an extension, and an extension without bit 7
set in the first octet is rejected. Only one extension is supported: its high
bit and low reserved bits must be clear. The current runtime-compatible profile
allows up to six basic bytes, three fractional bytes, and eight total time-value
bytes; unsupported layouts fail during generation rather than producing corrupt
timestamps. Configure an extended format once, for example:

```python
extended_time = pus.CucTime(
    epoch=Y.Epoch.UNIX, pfield=0xAE, pfield_contd=0x20,
)
assert extended_time.byte_sizes == (5, 2)
assert extended_time.bits == 56
```

### Encoder and decoder configuration

Default command configuration:

```json
{"type": "CUC", "epoch": "UNIX", "pField": 46, "implicitPField": true}
```

Default telemetry configuration:

```json
{"type": "CUC", "epoch": "UNIX", "pField": 46}
```

These maps configure Java `org.yamcs.algo.TimeBinaryEncoder(...)` and
`org.yamcs.algo.TimeBinaryDecoder(...)` algorithms through `BinaryTimeEncoding`.
Commands use `AbsoluteTimeArgument`; report aggregates use `AbsoluteTimeMember`.
The decoder has no boolean implicit flag: its configured `pField` describes the
bytes it reads. New generated configuration uses canonical `pField` spelling;
legacy names remain read aliases in Yamcs for older MDBs. Extended layouts
add `pFieldCont` to both maps, while neither p-field octet is sent on wire.

If the header has a TCO service, both maps additionally contain
`"tcoService": "<configured name>"`. The header's `onboard_cuctime` decoder
uses the same service. Without TCO, encoding shifts from Yamcs time to the
configured epoch and decoding reverses that shift. With TCO, the algorithms
correlate the packed raw CUC counter and TCO takes precedence over epoch
conversion. For the default layout that counter is `(coarse_seconds << 16) |
fraction`; correlation coefficients must match those units, not a flat
millisecond counter.

A configured service that cannot be found is a runtime configuration error.
Missing correlation coefficients fail command encoding and telemetry decoding;
an uncorrelated counter must not be displayed as an absolute timestamp. Missing
TCO configuration itself is supported and does not block XTCE generation or
application startup. Invalid adapter configuration is reported through the
existing `XtceGenerationError` path during artifact generation.

### Shared Yamcs runtime fixes

The required runtime includes CUC epoch handling, overflow checks, unavailable
TCO-coefficient errors, and the millisecond-rounding correction. The CUC
extension fixes use `(pfield & 0x80) != 0` and extract additional basic bytes
with `>> 5`, because the continuation's basic-byte field occupies bits 6–5.
The old equality against 1 could never detect bit 7; `>> 6` discarded one of
the basic-byte bits. Hermes and pymdb now use this extension support through
`CucTime.pfield_contd`, emitted as `pFieldCont`.

`TimeBinaryEncoder` and `TimeBinaryDecoder` are CUC-only. This statement is
about the binary-time algorithms; existing unrelated packet-preprocessor time
formats are outside this Service 15 profile.

## Payload catalogue

The notation `count × {...}` means the fields inside the braces repeat in that
order. `time` means the shared CUC absolute-time encoding. All unqualified
counts and integers below use the defaults above. Counts are present on wire
and are supplied explicitly with their arrays in generic commands. The explicit
all-store and empty-configuration commands instead fix the count to zero and do
not expose a count or array argument.

### Storage and retrieval

| Message | Application-data fields in wire order |
|---|---|
| TC[15,1] | `n_count`, `n_count × packet_store_id` |
| TC[15,2] | `n_count`, `n_count × packet_store_id` |
| TC[15,9] | `n_count`, `n_count × {packet_store_id, retrieval_priority, from_time:time, to_time:time}` |
| TC[15,11] | `storage_time:time`, `n_count`, `n_count × packet_store_id` |
| TC[15,12] | `n_count`, `n_count × packet_store_id` |
| TM[15,13] | `n_count`, `n_count × {packet_store_id, oldest_stored_packet_time:time, newest_stored_packet_time:time, open_retrieval_start_time:time, percentage_filled, from_open_retrieval_start_time_percentage_filled}` |
| TC[15,14] | `open_retrieval_start_time:time`, `n_count`, `n_count × packet_store_id` |
| TC[15,15] | `n_count`, `n_count × {packet_store_id, retrieval_priority}` |
| TC[15,16] | `n_count`, `n_count × packet_store_id` |
| TC[15,17] | `n_count`, `n_count × packet_store_id` |
| TC[15,18] | No application-data fields |
| TM[15,19] | `n_count`, `n_count × {packet_store_id, storage_status, open_retrieval_status, time_range_retrieval_status}` |
| TC[15,20] | `n_count`, `n_count × {packet_store_id, packet_store_size, packet_store_type, virtual_channel}` |
| TC[15,21] | `n_count`, `n_count × packet_store_id` |
| TC[15,22] | No application-data fields |
| TM[15,23] | `n_count`, `n_count × {packet_store_id, packet_store_size, packet_store_type, virtual_channel}` |
| TC[15,24] | See the three copy-window layouts below |
| TC[15,25] | `n_count`, `n_count × {packet_store_id, packet_store_size}` |
| TC[15,26] | `packet_store_id`; select circular store type |
| TC[15,27] | `packet_store_id`; select bounded store type |
| TC[15,28] | `packet_store_id`, `virtual_channel` |

TC[15,11] and TC[15,14] each have one shared time tag before the count, rather
than one tag per store. TC[15,9] and TC[15,15] include retrieval priority in each
store entry. The three time tags in each TM[15,13] entry use the same encoding
as retrieval requests and copy-window tags.

### Copy-window commands

The mission assumes selector values 0, 1, and 2 for between, after, and before.
Each variant fixes its selector on wire and exposes only applicable arguments.

| Command | TC[15,24] wire layout |
|---|---|
| `copy_packets_by_time_window_between` | `time_window_type=0`, `from_time`, `to_time`, `from_packet_store_id`, `to_packet_store_id` |
| `copy_packets_by_time_window_after` | `time_window_type=1`, `from_time`, `from_packet_store_id`, `to_packet_store_id` |
| `copy_packets_by_time_window_before` | `time_window_type=2`, `to_time`, `from_packet_store_id`, `to_packet_store_id` |

### Application-process selection

TC[15,3], TC[15,4], and TM[15,6] share this field hierarchy:

```text
packet_store_id:u8
n_count:u8
n_count × {
    apid:u11
    service_count:u8
    service_count × {
        service_type:u8
        subservice_count:u8
        subservice_count × subservices:u8
    }
}
```

TC[15,3] adds report types; TC[15,4] deletes them. TC[15,5] carries only
`packet_store_id` and requests TM[15,6]. `service_type` and the elements of
`subservices` are plain unsigned integers. The optional outer APID-selection
block is always present in this mission profile. Zero service/subservice
counts retain the whole-parent selection meaning defined for the operation.

### Housekeeping and diagnostic selection

TC[15,29], TC[15,31], TM[15,36], and TM[15,38] use:

```text
packet_store_id:u8
n_count:u8
n_count × {
    apid:u11
    structure_id_count:u8
    structure_id_count × {
        structure_id:enum8
        subsampling_rate:u8
    }
}
```

TC[15,30] and TC[15,32] use the same hierarchy but each structure entry
contains only `structure_id`; a delete entry has no subsampling field.
TC[15,35] carries one store ID and requests TM[15,36]. TC[15,37] carries one
store ID and requests TM[15,38]. Subsampling is mandatory in the add requests
and configuration reports where shown.

### Event blocking selection

TC[15,33], TC[15,34], and TM[15,40] use:

```text
packet_store_id:u8
n_count:u8
n_count × {
    apid:u11
    event_count:u8
    event_count × event_ids:enum8
}
```

**TC[15,33] deletes** event identifiers from the blocking configuration;
**TC[15,34] adds** them. TC[15,39] carries one store ID and requests TM[15,40].
Event blocking is an on-board selection policy; the XTCE schema represents
requests and reports and preserves applicable zero-count wildcard selections.

## XTCE construction

Each subtype has an explicit `add_pus_tc_15_<subtype>_command` or
`add_pus_tm_15_<subtype>_container` function. TC[15,1]'s original interface is
preserved. Builders use descriptive names, aggregates and counted arrays,
standard TC verifiers, and the shared header. All TM containers inherit from
`pus_header.pus_tm_nontime_container` and match service 15 plus their subtype.

- Command array counts use `ArgumentValue`, including sibling counts within
  nested command aggregates.
- Counts inside report aggregates use `AggregateMemberValue`. The generated
  `ParameterInstanceRef` has `yamcs:aggregateMember="true"`, so it resolves in
  the current aggregate scope rather than as a top-level parameter.
- Report arrays can contain different inner counts on different outer entries.
  Empty arrays remain valid through decoding and calibration.
- Container repetition is still valid for example MDBs, but is not required
  for Hermes's nested reports. The old claim that nested aggregate TM arrays
  cannot work is obsolete for the patched runtime.

See [array-in-array-tm.xml](../yamcs-core/src/test/resources/xtce/array-in-array-tm.xml)
and [array-in-array-arg.xml](../yamcs-core/src/test/resources/xtce/array-in-array-arg.xml)
for runtime reference layouts.

## Explicit zero-count constructors

The nine all-store constructors are `add_pus_tc_15_<subtype>_all_command` for
subtypes **1, 2, 11, 12, 14, 15, 16, 17, and 21**. They encode a fixed unsigned
zero count with the configured `n_count_size`; this is not a zero-payload
request. TC[15,11] retains its `storage_time` argument before the zero count,
and TC[15,14] retains `open_retrieval_start_time`. TC[15,15] has no per-store
retrieval-priority field when the repeated store list has zero entries.

The standard also defines explicit empty-configuration requests for TC[15,4],
TC[15,30], TC[15,32], and TC[15,33]. Their `_empty_command` constructors retain
`packet_store_id`, then encode a fixed zero count to clear that store's table.
All these commands use the ordinary PUS header and standard TC verifiers, and
are registered alongside the generic counted commands.

Regular telemetry containers decode zero-length arrays directly. There are no
special empty-report containers. A zero TM count means an empty report, not an
all-store wildcard.

Service 14 has standard empty commands for TC[14,2/6/10] and explicit nested
wildcard commands: `service_count=0` and `subservice_count=0` for TC[14,1/2],
`structure_id_count=0` for TC[14,5/6/9/10], and `event_id_count=0` for TC[14,13/14].
The nested convenience commands fix parent counts to one and select an APID
(and a service where applicable). No top-level N=0 whole-event-table operation
is defined for TC[14,13].

## Operator examples

Command names below are the Hermes names, relative to the mission root. APID
engineering labels come from that mission's `system.extra["apids"]`. The
examples assume `ADCS` and the current `Circular_Type` store-type label.

Create one store with an integer virtual channel:

```json
{"ccsds_apid": "ADCS", "n_count": 1, "packet_stores": [{"packet_store_id": 1, "packet_store_size": 200, "packet_store_type": "Circular_Type", "virtual_channel": 3}]}
```

Use `create_packet_stores`; the size value must fit the configured width and
its units must match the on-board ICD. Change its channel through
`change_packet_store_virtual_channel`:

```json
{"ccsds_apid": "ADCS", "packet_store_id": 1, "virtual_channel": 4}
```

Authorize service 3/subtype 25 through
`add_report_types_to_application_process_storage_control`:

```json
{"ccsds_apid": "ADCS", "packet_store_id": 1, "n_count": 1, "apid_reports": [{"apid": "ADCS", "service_count": 1, "services": [{"service_type": 3, "subservice_count": 1, "subservices": [25]}]}]}
```

Resume open retrieval through `resume_open_retrieval_of_packet_stores`:

```json
{"ccsds_apid": "ADCS", "n_count": 1, "packet_stores": [{"packet_store_id": 1, "retrieval_priority": 0}]}
```

Delete content through
`delete_content_of_packet_stores_up_to_specified_time`. Supply an absolute
time, rather than an integer uptime or a maximum raw-counter sentinel:

```json
{"ccsds_apid": "ADCS", "storage_time": "2020-01-01T00:00:00.123Z", "n_count": 1, "packet_store_ids": [1]}
```

## Simulator compatibility

`Pus15Service` and `examples/pus/src/main/yamcs/mdb/pus15.xml` now use the
agreed payload layout for all 30 TC and seven TM subtypes. The example keeps its
`/PUS15/TC_15_*` operator names; Hermes command names differ but encode the same
payloads. The example's `_ALL` commands encode N=0, including the shared time
prefix on TC[15,11/14]. TC[15,24] has three concrete copy commands and
TC[15,26/27] change the store type.

Simulator mission conventions:

- A uint8 capacity is measured in **1 KiB allocation units** (1..255), so the
  example calls it `size_units`; the in-memory engine tracks actual bytes.
  Hermes calls this wire field `packet_store_size`.
- Every store starts inactive, with an empty application-process selection
  that blocks storage. Enable storage and authorize report types explicitly.
- Higher numeric retrieval priority is sent first within each dispatch tick.
  TC[15,15] with N=0 resumes all stores using their retained priorities.
  Time-range retrieval sends one packet per store per 100 ms tick; open
  retrieval sends one per store every 500 ms. Replayed bytes bypass live
  forwarding/storage and use the store's configured virtual channel.
- Structure rates are uint8 in Service 15. Rates 0/1 retain every matching
  packet; N>1 retains the first and every Nth thereafter. Counters are per
  store/APID/structure and reset when its rate is configured.
- Copy preserves the source and packet bytes/timestamps. Between includes
  both endpoints; after/before use strict boundaries. A bounded destination
  with insufficient capacity rejects the whole copy; a circular destination
  evicts oldest inserted packets as required. Copying a store onto itself
  is rejected. Type changes require an inactive store, as do resizing and
  virtual-channel changes. Resizing below occupied capacity is rejected.
- Unknown stores, malformed/truncated payloads, zero capacities, invalid
  types/windows and oversized reports produce verification failures.
  CRC octets cannot stand in for omitted application-data fields.

Payload time defaults are implicit CUC pField=46, epoch UNIX. Internal storage
times are milliseconds in the configured epoch and are converted through
`PusTimeEncoding` at the wire boundary. Configure a different CUC layout once
under the simulator commander's `pus` settings:

```yaml
pus:
  storageTimeEncoding:
    pField: 174
    pFieldCont: 32
    epoch: UNIX
```

Match the corresponding Hermes `CucTime` and example MDB time type algorithms
and bit widths. The default MDB time types are 48 bits; the simulator computes
payload/report sizes from its configured codec. UNIX, GPS, TAI, J2000, and the
legacy SIMULATOR epoch are supported. The simulator produces onboard CUC
counts; optional ground TCO coefficients must describe those counts. It does
not run a ground `TimeCorrelationService` or manufacture coefficients.

The **legacy example header remains separately configured**: by default it
uses explicit pField=47, simulator epoch, and an eight-byte TM header time.
Other example services depend on that header. For a fully Hermes-generated MDB,
configure the simulator's existing `pus.timeEncoding` header settings to the
same implicit CUC layout and epoch as `PusHeader`; do not reuse the legacy
example header decoder unchanged. ST[3] report bodies also retain their
existing uint32 structure identifiers; the ST[14/15] selection tables use
uint8 identifiers and match those numeric IDs.

The updated `examples/pus/tests/test-pus15.py` walkthrough uses the legacy
header with the migrated Service 15 payload. It authorizes storage before
capture, supplies priorities and ISO absolute times, and decodes CUC summary
tags and allocation units.

## Verification and deployment

For changes to this profile, inspect generated XTCE and exercise actual command
encoding/report decoding against the patched Yamcs runtime. Verify all 30 TC
and seven TM subtypes, the three copy-window layouts, unsigned field types and
width overrides, standard enum values, and shared header configuration.

Nested checks need multiple APID groups with different counts, empty arrays,
and applicable zero-count wildcards. Time checks need pField-derived widths,
standard `.123 -> 0x1F7C` encoding, epoch conversion without TCO, correlation
with TCO, unavailable coefficients, invalid/missing extension octets, overflow, and
unchanged following-field offsets. Optional wire fields must always be present
where specified in the catalogue.

Existing Hermes XTCE-generation and Yamcs CUC/array regression checks can be
reused. No new persistent test files are part of the agreed delivery. Direct runtime
checks exercise generated Hermes commands against the simulator and decode
all seven reports for default and extended CUC formats; the example MDBs are
also loaded and their commands encoded. Deploy the modified
pymdb package and matching patched Yamcs runtime together with generated XTCE.
