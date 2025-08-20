package org.yamcs.tctm.pus.acks;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.Spec.OptionType;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.commanding.Verifier;
import org.yamcs.http.api.ProcessingApi;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.logging.Log;
import org.yamcs.mdb.Mdb;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.tctm.pus.services.tm.one.ServiceOne;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.streamsql.StreamSqlException;
import org.yamcs.yarch.streamsql.StreamSqlResult;
import org.yamcs.time.TimeService;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.utils.parser.ParseException;


public class TimetagCommandHistoryManager extends AbstractYamcsService implements StreamSubscriber {

    public static final String CMDHIST_REALTIME_TIMETAG_STREAM = "cmdhist_realtime_timetag";
    Stream cmdhistStream;

    private YarchDatabaseInstance ydb;
    private TimeService timeService;

    private Map<String, Integer> commands = new HashMap<>();

    private enum Action {
        DELETE, DISPATCH, TIMEOUT
    }

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();

        Spec commandSpec = new Spec();
        commandSpec.addOption("name", OptionType.STRING);
        commandSpec.addOption("apid", OptionType.INTEGER);

        spec.addOption("commands", OptionType.LIST).withElementType(OptionType.MAP).withSpec(commandSpec);
        return spec;
    }

    public long getTimetagCommandInUnixEpoch(String timetag) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
        Instant instant = formatter.parse(timetag, Instant::from);

        // Convert to Unix timestamp (seconds since epoch)
        return instant.getEpochSecond();
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        this.yamcsInstance = yamcsInstance;

        log = new Log(getClass(), yamcsInstance);

        ydb = YarchDatabase.getInstance(yamcsInstance);
        timeService = YamcsServer.getTimeService(yamcsInstance);

        try {
            if (ydb.getStream(CMDHIST_REALTIME_TIMETAG_STREAM) == null) {
                ydb.execute("create stream " + CMDHIST_REALTIME_TIMETAG_STREAM + StandardTupleDefinitions.TC.getStringDefinition());
            }
            cmdhistStream = ydb.getStream(CMDHIST_REALTIME_TIMETAG_STREAM);
        } catch (ParseException | StreamSqlException e) {
            throw new InitException(e);
        }

        if (!config.containsKey("commands"))
            throw new ConfigurationException(this.getClass() + ": commands needs to be defined to know the cmdhist manipulation strategy");

        for(YConfiguration c: config.getConfigList("commands")) {
            String name = c.getString("name");
            int apid = c.getInt("apid");

            commands.put(name, apid);
        }
    }

    private void updateHistory(Action action, PreparedCommand pc, int commandApid) {
        try {
            // Extract and convert timetags directly to LocalDateTime in UTC
            String startTimetag = formatUtc(toUtcDateTime(pc, "Timetag-1"));
            String endTimetag = formatUtc(toUtcDateTime(pc, "Timetag-2"));

            String query = String.format(
                "SELECT * FROM %s WHERE CommandApid=? AND Timetag>=? AND Timetag<=? ORDER DESC",
                ServiceOne.CMDHIST_TABLE
            );

            StreamSqlResult res = ydb.execute(query, commandApid, startTimetag, endTimetag);
            CommandHistoryPublisher publisher = ProcessingApi
                    .verifyProcessor(yamcsInstance, "realtime")
                    .getCommandHistoryPublisher();

            long gentime = timeService.getMissionTime();
            ParameterValue returnPv = new ParameterValue(Verifier.YAMCS_PARAMETER_RETURN_VALUE);
            returnPv.setAcquisitionTime(gentime);
            returnPv.setGenerationTime(gentime);
            returnPv.setEngValue(ValueUtility.getStringValue("DELETED"));

            while (res.hasNext()) {
                PreparedCommand pcL = PreparedCommand.fromTuple(res.next(), MdbFactory.getInstance(yamcsInstance));
                publisher.publishAck(
                    pcL.getCommandId(),
                    CommandHistoryPublisher.CommandComplete_KEY,
                    gentime,
                    AckStatus.NOK,
                    "DELETED",
                    returnPv
                );
            }

        } catch (Exception e) {
            log.error("Unable to update command history for {} with APID {}: {}",
                    pc.getCommandName(), commandApid, e.getMessage(), e);
        }
    }

    // --- Helpers ---
    private LocalDateTime toUtcDateTime(PreparedCommand pc, String argName) {
        long raw = pc.getArgAssignment(
                        PreparedCommand.findArgument(pc.getMetaCommand(), argName))
                    .toGpb().getEngValue().getTimestampValue();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(TimeEncoding.toUnixMillisec(raw)), ZoneOffset.UTC);
    }

    private String formatUtc(LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss' UTC'"));
    }

    @Override
    public void onTuple(Stream stream, Tuple tuple) {
        Mdb mdb = MdbFactory.getInstance(yamcsInstance);
        PreparedCommand pc = PreparedCommand.fromTuple(tuple, mdb);

        commands.forEach((name, apid) -> {
            if (name.equals(pc.getCommandName())) {
                updateHistory(Action.DELETE, pc, apid);
                return;
            }
        });
    }

    @Override
    protected void doStart() {
        cmdhistStream.addSubscriber(this);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        cmdhistStream.removeSubscriber(this);
        notifyStopped();
    }
}
