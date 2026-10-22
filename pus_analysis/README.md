# PUS analysis reference

The current [Service 15 profile](pus15.md) records the agreed Hermes/pymdb
implementation. Other service analyses retain their service-specific native or
simulator examples. An example MDB's wire format is not automatically the
Hermes mission format.

## Shared decisions

- PUS time configuration is supplied once when constructing `PusHeader`.
  It retains `cuctime_fields: CucTime` and optional `tco_service`; adapters read
  the header instead of passing epoch, p-field, size, and TCO through every
  builder.
- Service 15 uses CUC only. Default `pField=46` means four seconds bytes and two
  binary-fraction bytes, six bytes total. `.123` seconds uses `0x1F7C`, not
  literal milliseconds. P-fields are implicit metadata for this profile.
- Hermes `EPOCH` defaults to UNIX, `P_FIELD` to 46, and `TCO_SERVICE` has no
  default name. Header and payload time algorithms use the same optional TCO
  service; absent/blank TCO uses epoch conversion.
- New Yamcs time configuration uses `pField` consistently. The encoder's
  `implicitPField` is a boolean; the decoder has no boolean implicit flag.
  `CucTime.pfield_contd` supplies an optional extension octet, configured through
  Hermes `P_FIELD_CONTD` and emitted as `pFieldCont`. Header and payload widths
  and offsets are derived together. Unsupported layouts are rejected.
- Service 15 defines all 30 TC and seven TM subtypes. Its three TC[15,24]
  variants include only their applicable time tags. TC[15,33] deletes and
  TC[15,34] adds event-blocking entries.
- Service 15 includes the standard's optional fields in the agreed layouts,
  retains every count (including zero), and uses scoped aggregate-member
  counts in nested TM arrays. No presence flags or `include_*` options are
  introduced.
  Explicit all-store/empty command constructors fix the count to zero while
  retaining its wire field. Regular TM containers decode zero-length arrays directly; zero report
  counts are not all-store wildcards.
- Store IDs and other ordinary Service 15 fields default to eight bits; APIDs
  are packed 11-bit fields. Service type/subtype and virtual channel are plain
  unsigned integers. Mission-specific structure/event IDs retain their enum
  mappings. The [field table](pus15.md#field-types-and-presence) gives details.

The [simulator compatibility table](pus15.md#simulator-compatibility) identifies
remaining differences in `examples/pus` and `Pus15Service`. A documentation
update does not migrate those parsers or the example MDB. The shared
[time-codec notes](pus15.md#shared-yamcs-runtime-fixes) identify the patched
Yamcs runtime needed with generated Hermes XTCE.

## Service analyses

| Service | Analysis |
|---|---|
| ST[02] | [Device access](pus02.md) |
| ST[03] | [Housekeeping](pus03.md) |
| ST[05] | [Event reporting](pus05.md) |
| ST[06] | [Memory management](pus06.md) |
| ST[09] | [Time management](pus09.md) |
| ST[11] | [Time-based scheduling](pus11.md) |
| ST[12] | [On-board monitoring](pus12.md) |
| ST[13] | [Large packet transfer](pus13.md) |
| ST[14] | [Real-time forwarding control](pus14.md) |
| ST[15] | [On-board storage and retrieval: current Hermes profile](pus15.md) |
| ST[17] | [Test service](pus17.md) |
| ST[19] | [Event-action service](pus19.md) |
| ST[20] | [On-board parameter management](pus20.md) |
| ST[21] | [Request sequencing](pus21.md) |

## Architecture references

- [Native Yamcs PUS architecture](pus_native_arch.md): command handlers,
  processor services, and native configuration examples.
- [PUS simulator architecture](pus_simulator_architecture.md): packet classes,
  example MDB patterns, shared array mechanisms, and on-board emulation.
- [Hermes Service 15 source](../../tools/hermes/app/services/adapters/xtce/pus/service15.py),
  [generic registration](../../tools/hermes/app/services/adapters/xtce/generic.py),
  and [pymdb header API](../../tools/pymdb/src/yamcs/pymdb/pus.py): source of truth
  for generated field names, engineering labels, and packet layouts.

Use explicit profile names when comparing schemas or reporting verification.
Generated Hermes commands now exercise the migrated simulator payload and
all seven Service 15 reports with default and extended CUC. The updated
example walkthrough uses that payload with the separately configured legacy
TM header; see [simulator compatibility](pus15.md#simulator-compatibility).
