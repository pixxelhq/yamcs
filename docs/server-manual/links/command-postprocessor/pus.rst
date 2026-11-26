PUS Command Postprocessor
=========================

A postprocessor for handling ECSS PUS commands, according to ECSS-E-ST-70-41C.

This postprocessor will set the length and sequence count in the CCSDS primary header. If configured, it can also calculate a checksum.


Class Name
----------

:javadoc:`org.yamcs.pus.PusCommandPostprocessor`


Configuration Options
---------------------

seqCounterName (string)
    Name of a shared CCSDS sequence counter. By default each link maintains its own per-APID
    sequence counter independently of all other links. When two or more links specify the same
    ``seqCounterName`` they share a single per-APID counter, so the target receives a continuous
    sequence across those links.

    If not specified, the counter is local to this link.

    The name is global to the Yamcs server, not to the instance: links in different instances that
    use the same ``seqCounterName`` share one counter. In a setup with one instance per spacecraft,
    follow both rules:

    * Use a name that is unique per spacecraft (for example the spacecraft name). A name reused
      across instances makes each spacecraft see gaps in its sequence count.
    * Give every TC link of one spacecraft (for example one link per ground station) the same name.
      Without it each link counts from 0 on its own, so two links can send the same
      ``(APID, sequence count)``. The spacecraft then holds two commands with the same PUS request
      ID, and a later ``TC[11,4]`` scheduled command, or a PUS 11 request that addresses entries by
      request ID, becomes ambiguous.

    .. code-block:: yaml

        # yamcs.sat1.yaml, on every TC link of this instance
        commandPostprocessorArgs:
            seqCounterName: sat1

persistSeqCount (boolean)
    If ``true``, the CCSDS sequence counters are stored in the instance memento and restored when
    Yamcs restarts. Without it every APID restarts at 0, while commands sent before the restart can
    still be in the on-board schedule: a new command can then get the same ``(APID, sequence count)``,
    so the same PUS request ID, as one already on board.

    The next sequence count is saved before each command is sent, including changes made with the
    ``change-seq-count`` link action. The memento key is ``ccsds-seqcount/named/<seqCounterName>``
    for a shared counter, otherwise ``ccsds-seqcount/link/<link name>``. Renaming the link therefore
    restarts its counter at 0.

    With ``seqCounterName``, set ``persistSeqCount`` the same on every link that shares the counter:
    the first link that creates the counter decides whether it is persisted.

    Default: ``false``

errorDetection (map)
    If specified, a checksum is appended at the end of each command.
    Detailed below.

timeEncoding (map)
    Configures how the release time is encoded in the generated ``TC[11,4]`` command (see the
    ``pus11`` option below). Sub-keys: ``implicitPField`` (boolean, default ``true``), ``pField``
    (integer) and ``pFieldCont`` (integer, default ``-1``). If not specified, an implicit CUC
    P-field of ``0x2e`` is used.

    ``epoch`` (one of ``TAI``, ``J2000``, ``UNIX``, ``GPS``, ``CUSTOM`` or ``NONE``, default ``UNIX``)
    is the epoch the release time is encoded against when no ``tcoService`` is configured. ``NONE``
    encodes the Yamcs internal time. ``CUSTOM`` requires ``epochUTC`` (string) and optionally
    ``timeIncludesLeapSeconds`` (boolean, default ``true``).

tcoService (string)
    Name of a :abbr:`TCO (Time Correlation)` service. When set, the release time written in the
    ``TC[11,4]`` command is the on-board time obtained from that service instead of the raw UTC
    value.

pus11Crc (boolean)
    If true, a checksum is appended to the generated ``TC[11,4]`` commands. Requires
    ``errorDetection``. Default: ``true`` if ``errorDetection`` is configured, ``false`` otherwise.

spareBytes (integer)
    Number of zero spare bytes written after the source id of the generated ``TC[11,4]`` and
    ``TC[22,4]`` commands, before the sub-schedule id. ``0`` or ``4``. The schedule tracker parses
    the ``TC[11,4]`` wrappers it tracks with the same layout, and the on-board parser expects it, so
    a manual ``INSERT_ACTIVITIES`` sent on this link must carry the same spare bytes. Default: ``0``.

pus11Apid (integer)
    APID (0-2047) of the generated ``TC[11,4]`` commands, i.e. the on-board application process
    hosting the scheduling subservice. If omitted, the original command's APID is used.

pus11 (map)
    Enables the PUS 11 time-based scheduling support. Detailed below.

    Older releases configured the source id, acknowledgement flags, sub-schedule id and group id
    with the top-level ``pus11SourceId``, ``pus11AckFlags``, ``pus11SubscheduleId`` and
    ``pus11GroupId`` options. These are rejected; move them into the ``pus11`` block.

pus22Crc (boolean)
    Like ``pus11Crc``, but for generated ``TC[22,4]`` commands.

pus22Apid (integer)
    Like ``pus11Apid``, but for generated ``TC[22,4]`` commands. Defaults to the original command's APID.

pus22 (map)
    Enables PUS 22 position-based scheduling. Supports the same ``sourceId``, ``ackFlags``,
    ``subScheduleId`` and ``groupId`` settings as ``pus11``. Both ``pus22OrbitNumber`` (unsigned
    32-bit integer) and ``pus22OrbitAngle`` (finite number of degrees, normalized modulo 360)
    command options must be set together. PUS 11 and PUS 22 scheduling options cannot be combined.

embeddedTcCrc (boolean)
    If ``true``, a checksum (computed with the ``errorDetection`` algorithm) is appended to each TC
    packet embedded into a command, see :ref:`pus-embedded-tc` below. Default: ``true`` if
    ``errorDetection`` is configured, ``false`` otherwise. Setting it to ``true`` without
    ``errorDetection`` is a configuration error.


PUS 11 time-based scheduling
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

A command issued with the ``pus11ScheduleAt`` command option (a timestamp) is not sent as-is;
instead it is embedded into a ``TC[11,4] insert activities into the time-based schedule`` request
that releases it on board at the requested time. The ``pus11ScheduleAt`` option is only registered
when the ``pus11`` map is present.

The ``pus11`` map configures the optional sub-schedule and scheduling group identifiers that are
written into that request. Each sub-section is independent: when it is present the corresponding
field is written into the ``TC[11,4]`` command and the matching command option is registered so
operators can override the value per command; when it is absent neither the field nor the option
exist. A value that does not fit in the configured field width fails the command. Invalid configured
defaults are rejected at initialization.

subScheduleId (map)
    ``bytes`` (integer, 1..8, default ``1``): width of the sub-schedule id field.
    ``default`` (integer, default ``0``): value used when the ``pus11SubScheduleId`` command
    option is not set.

groupId (map)
    ``bytes`` (integer, 1..8, default ``1``): width of the scheduling group id field.
    ``default`` (integer, default ``0``): value used when the ``pus11GroupId`` command option is
    not set.

sourceId (integer)
    Source id (0-65535) written in the secondary header of the generated ``TC[11,4]`` command.
    Default ``0``.

ackFlags (integer)
    Acknowledgement flags (0-15) written in the secondary header of the generated ``TC[11,4]``
    command. Default ``0xD``.

Command history keeps the original command and its scheduling wrapper separate, using the upstream
attribute names:

``binary`` / ``ccsds-seqcount``
    The postprocessed original command and its sequence count. These keep the same meaning for
    unscheduled commands, PUS 11 scheduling and PUS 22 scheduling.

``ccsds-apid``
    The original command's APID, published once for verifiers that read it from command history.

``pus11Binary`` / ``pus11Apid`` / ``pus11CcsdsSeqCount``
    The generated ``TC[11,4]`` wrapper and its request ID. Scheduling verifiers use these attributes
    to verify insertion into the onboard schedule, independently of the original command's later execution.

``pus22Binary`` / ``pus22Apid`` / ``pus22CcsdsSeqCount``
    The equivalent wrapper attributes for ``TC[22,4]`` position-based scheduling.

The redundant ``pus11-inner-apid`` and ``pus11-inner-seqcount`` attributes are no longer published.
Consumers that used them should use ``ccsds-apid`` and ``ccsds-seqcount``. Consumers that previously
used the generic attributes for the PUS 11 wrapper must use ``pus11Apid`` and ``pus11CcsdsSeqCount``.

Example:

.. code-block:: yaml

    commandPostprocessorClassName: org.yamcs.pus.PusCommandPostprocessor
    commandPostprocessorArgs:
        errorDetection:
            type: CRC-16-CCIIT
        pus11Apid: 1
        pus11:
            subScheduleId: { bytes: 1, default: 1 }
            groupId: { bytes: 1, default: 1 }


Schedule tracker
^^^^^^^^^^^^^^^^

The ``org.yamcs.pus.Pus11ScheduleTracker`` service keeps a ground model of the on-board time-based
schedule, and reconciles it with the spacecraft. It is configured as a service of the instance:

.. code-block:: yaml

    services:
      - class: org.yamcs.pus.Pus11ScheduleTracker
        args:
          releaseGrace: 300      # seconds after the release time before an unconfirmed entry is presumed gone
          sweepInterval: 10      # seconds
          tcLink: tc_realtime    # optional: the TC link whose TC(11,4) layout is used
          processor: realtime    # processor that decodes the status and summary reports
          reconcile:
            enabled: true        # request the on-board schedule at each pass
            aosGap: 600          # seconds without TM that mark the end of a pass
            aosSettle: 30        # seconds after the first TM packet of a pass before the request
            tmStream: tm_realtime

releaseGrace (integer)
    Seconds after its release time before an unconfirmed entry is presumed gone. Default: ``300``.

sweepInterval (integer)
    Seconds between checks for entries past ``releaseGrace``. Default: ``10``.

tcLink (string)
    The TC link whose ``PusCommandPostprocessor`` gives the TC(11,4) layout. Optional when exactly one
    link has ``pus11Apid`` configured.

processor (string)
    The processor that decodes the status and summary reports. Default: ``realtime``.

subScheduleStatus, groupStatus (map)
    The status report parameters used to flag disabled sub-schedules and groups, with ``parameter``,
    ``idMember`` and ``statusMember`` keys. Defaults: ``/PUS11/status_report`` and
    ``/PUS11/group_status_report``.

reconcile (map)
    ``enabled``, ``aosGap``, ``aosSettle`` and ``tmStream``, with the defaults shown above.

The TC(11,4) layout must come from a ``PusCommandPostprocessor`` with ``pus11Apid`` set.

The status of each scheduled command is published in the command history as the ``pus11-release``
acknowledgment, separate from ``CommandComplete``:

* ``SCHEDULED``: the command is on board, released at the time in the message;
* ``DISABLED``: it will be dropped at its release time, because its sub-schedule or group is
  reported disabled (TM(11,19) / TM(11,27));
* ``TIMEOUT``: it is presumed gone (not confirmed ``releaseGrace`` seconds after its release time),
  or it was not on board at a reconcile before its release time. Release acknowledgments are not
  read yet, so a command that was released also ends with ``TIMEOUT``.

Reconcile: the tracker requests the complete on-board schedule with ``TC[11,17]``, and reads the
``TM[11,13]`` summary report back. The report replaces the model's live entries:

* an entry on board is confirmed (``SCHEDULED``), and takes the on-board release time;
* an entry missing from the report before its release time is dropped (``TIMEOUT``);
* an entry missing after its release time is marked released;
* an activity on board that the ground did not insert is kept as unknown.

A reconcile runs at the first TM packet after start, and at the first TM packet after more than
``aosGap`` seconds without TM, once ``aosSettle`` seconds have passed. It is skipped, without an
error, when no TC link is enabled. A report of several packets is applied once its last packet
arrives: the CCSDS sequence flags of the ``TM[11,13]`` packets mark the first and last packet of a
report, and an unfinished report is discarded when a new one starts.

Limits:

* the reconcile reads the ``/PUS11/SUMMARY_REPORT/*`` parameters of the MDB, including the packet's
  ``grouping`` (CCSDS sequence flags, 2 bits at bit 16);
* a packet lost in the middle of a multi-packet report is not detected, so that report is applied
  as partial; whether the spacecraft splits reports this way is to be confirmed;
* the release times of the ground model come from the time correlation at insert. The on-board
  value can differ from it by a millisecond, which is logged when the release time is replaced;
* only commands sent with ``pus11ScheduleAt`` are tracked. Manual ``INSERT_ACTIVITIES``, deletes and
  time shifts are not tracked between reconciles yet;
* every ``TM[11,13]`` is applied as the complete schedule, including reports that answer a
  by-ID or by-filter request (``TC[11,12]``, ``TC[11,14]``). Request summary reports only through the
  tracker until this is fixed;
* the simulator pages ``TM[11,13]`` with a page number and page count in the TM spare field. The
  tracker uses only the CCSDS sequence flags, so a paged report is applied page by page and
  entries on other pages are reported as dropped;
* the model is kept in memory only. After a restart, entries on board are reconciled as unknown
  and no longer linked to their commands;
* the status reports are as fresh as the last report received;
* there is no API or web page for the model yet.


.. _pus-embedded-tc:

Embedded TC packets
^^^^^^^^^^^^^^^^^^^

Some commands carry complete TC packets as arguments, for example a manually built
``TC[11,4] insert activities into the time-based schedule`` or ``TC[22,4]``, where each activity
is a TC packet. To have these packets processed like top-level commands, annotate the binary
argument type with the ``Yamcs:EmbeddedTc`` ancillary data:

.. code-block:: xml

    <BinaryArgumentType name="EmbeddedTcType">
        <AncillaryDataSet>
            <AncillaryData name="Yamcs:EmbeddedTc"/>
        </AncillaryDataSet>
        <BinaryDataEncoding>
            <SizeInBits><FixedValue>-1</FixedValue></SizeInBits>
        </BinaryDataEncoding>
    </BinaryArgumentType>

The postprocessor finds the annotated values using the argument locations recorded when the
command is encoded. The type can be used for a top-level argument but also as member of an
aggregate inside an array, such as a list of activities. For each embedded packet, in the order in
which they appear in the command, the postprocessor:

* fills in the CCSDS packet length;
* fills in the CCSDS sequence count, using the counter of the embedded packet's APID. The value is
  published in the command history as ``ccsds-seqcount:<argument path>``, for example
  ``ccsds-seqcount:activities[1].tc``;
* if ``embeddedTcCrc`` is set, appends the checksum. The command grows by 2 bytes per embedded
  packet; the entries following an embedded packet are shifted accordingly.

The embedded packets can thus be entered with zero sequence count and length, and without
checksum. The outer command gets its length, sequence count and checksum afterwards, as usual.

The SCOS-2000 MIB loader (yamcs-scos2k) annotates the command parameters with
(PTC, PFC) = (12, 1) this way.

Limitations:

* the embedded packet has to be byte aligned.
* when the checksum is appended, the binary encoding must allow the size to change: either a size
  of ``-1`` (all the bytes of the value) or a leading size tag (which is updated). With a fixed size
  or a dynamic size (given by another argument) the command fails.
* entries located at an absolute position (``referenceLocation="containerStart"``) after an
  embedded packet are not shifted.


Error Detection sub-configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

type (string)
    **Required.** Can take one of the values:

    * ``16-SUM``: calculates a 16 bits checksum over the entire packet which has to contain an even number of bytes. This checksum is used in Columbus/:abbr:`ISS (International Space Station)` data.
    * ``CRC-16-CCIIT``: standard CRC algorithm used in PUS and also in CCSDS standards for frame encoding.
    * ``ISO-16``: specified in PUS as alternative to CRC-16-CCIIT.
    * ``NONE``: no error detection will be used, this is the default if the ``errorDetection`` map is not present.

initialValue (integer)
    Used when the type is ``CRC-16-CCIIT`` to specify the initial value used for the algorithm. Default: ``0xFFFF``.
