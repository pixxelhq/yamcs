package org.yamcs.archive;

import java.util.List;

import org.yamcs.ConfigurationException;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.StreamConfig;
import org.yamcs.StreamConfig.StandardStreamType;
import org.yamcs.StreamConfig.StreamConfigEntry;
import org.yamcs.YConfiguration;
import org.yamcs.api.AbstractYamcsService;
import org.yamcs.api.InitException;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.streamsql.StreamSqlException;

/**
 * Records alarms. Uses a 'simple' upsert_append solution for now.
 */
public class AlarmRecorder extends AbstractYamcsService {

    public static final String PARAMETER_ALARM_TABLE_NAME = "alarms";
    public static final String EVENT_ALARM_TABLE_NAME = "event_alarms";

    @Override
    public void init(String yamcsInstance, YConfiguration config) throws InitException {
        super.init(yamcsInstance, config);

        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        try {
            if (ydb.getTable(PARAMETER_ALARM_TABLE_NAME) == null) {
                String cols = StandardTupleDefinitions.PARAMETER_ALARM.getStringDefinition1();
                String query = "create table " + PARAMETER_ALARM_TABLE_NAME + "(" + cols
                        + ", primary key(triggerTime, parameter, seqNum)) table_format=compressed";
                ydb.execute(query);
            }
            setupRecording(yamcsInstance, PARAMETER_ALARM_TABLE_NAME, StandardStreamType.parameterAlarm);

            if (ydb.getTable(EVENT_ALARM_TABLE_NAME) == null) {
                String cols = StandardTupleDefinitions.EVENT_ALARM.getStringDefinition1();
                String query = "create table " + EVENT_ALARM_TABLE_NAME + "(" + cols
                        + ", primary key(triggerTime, eventSource, seqNum)) table_format=compressed";
                ydb.execute(query);
            }

            setupRecording(yamcsInstance, EVENT_ALARM_TABLE_NAME, StandardStreamType.eventAlarm);
        } catch (ParseException | StreamSqlException e) {
            throw new InitException(e);
        }
    }

    private void setupRecording(String yamcsInstance, String tblName, StandardStreamType stype)
            throws StreamSqlException, ParseException {
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        StreamConfig sc = StreamConfig.getInstance(yamcsInstance);
        List<StreamConfigEntry> sceList = sc.getEntries(stype);
        for (StreamConfigEntry sce : sceList) {
            Stream inputStream = ydb.getStream(sce.getName());
            if (inputStream == null) {
                throw new ConfigurationException("Cannot find stream '" + sce.getName() + "'");
            }
            ydb.execute("upsert_append into " + tblName + " select * from " + sce.getName());
        }
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        notifyStopped();
    }
}
