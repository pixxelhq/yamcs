package org.yamcs.tctm.pus.services.tm.one;

import java.util.Arrays;
import java.util.ArrayList;

import org.yamcs.TmPacket;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.commanding.Verifier;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.http.api.ProcessingApi;
import org.yamcs.logging.Log;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.protobuf.Event.EventSeverity;
import org.yamcs.tctm.pus.PusTmManager;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.utils.ValueUtility;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.streamsql.StreamSqlResult;


public class SubServiceEight implements PusSubService {
    EventProducer eventProducer;
    String yamcsInstance;
    protected Log log;

    private YarchDatabaseInstance ydb;

    static final String source = "Service: 1 | SubService: 8 | Failure";
    static final String TC_COMPLETION_EXECUTION_FAILED = "TC_COMPLETION_EXECUTION_FAILED";

    public SubServiceEight(String yamcsInstance) {        
        this.yamcsInstance = yamcsInstance;
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, source, 10_000, false);
    
        ydb = YarchDatabase.getInstance(yamcsInstance);
        log = new Log(getClass(), yamcsInstance);
    }

    @Override
    public ArrayList<TmPacket> process(TmPacket tmPacket) {
        PusTmCcsdsPacket pPkt = new PusTmCcsdsPacket(tmPacket.getPacket());

        byte[] dataField = pPkt.getDataField();
        int tcCcsdsApid = ByteArrayUtils.decodeUnsignedShort(dataField, 0) & 0x07FF;
        int tcCcsdsSeqCount = ByteArrayUtils.decodeUnsignedShort(dataField, 2) & 0x3FFF;

        if (PusTmManager.destinationId != pPkt.getDestinationID())
            return null;

        byte[] failureNotice = Arrays.copyOfRange(dataField, ServiceOne.REQUEST_ID_LENGTH, dataField.length);
        long errorCode = ByteArrayUtils.decodeCustomInteger(failureNotice, 0, ServiceOne.failureCodeSize);
        long errorReason = ByteArrayUtils.decodeCustomInteger(failureNotice, ServiceOne.failureCodeSize, ServiceOne.failureDataSize);

        try {
            eventProducer.sendEvent(EventSeverity.CRITICAL, TC_COMPLETION_EXECUTION_FAILED,
                "TC with (Source ID: " + pPkt.getDestinationID() + " | Apid: " + ServiceOne.ccsdsApids.get(tcCcsdsApid) + " | Packet Seq Count: "
                    + tcCcsdsSeqCount
                    + ") has failed to complete execution | Error Code: " + ServiceOne.failureCodes.get(Integer.valueOf((int) errorCode))
                    + "| Failure reason: " + errorReason,
                tmPacket.getGenerationTime());

        } catch (Exception e) {
            eventProducer.sendEvent(EventSeverity.CRITICAL, TC_COMPLETION_EXECUTION_FAILED,
                "TC with (Source ID: " + pPkt.getDestinationID() + " | Apid: " + ServiceOne.ccsdsApids.get(tcCcsdsApid) + " | Packet Seq Count: "
                    + tcCcsdsSeqCount
                    + ") has failed to complete execution | Error Code: " + errorCode + " (No enumeration found)"
                    + "| Failure reason: " + errorReason,
                tmPacket.getGenerationTime());
        }

        try {
            String query = String.format(
                "SELECT * FROM %s WHERE Timetag_CommandApid=? AND Timetag_CommandCcsdsSeqCount=? ORDER DESC LIMIT 1",
                ServiceOne.CMDHIST_TABLE
            );

            StreamSqlResult res = ydb.execute(query, tcCcsdsApid, tcCcsdsSeqCount);

            if (res.hasNext()) {
                Tuple t = res.next();

                PreparedCommand pc = PreparedCommand.fromTuple(t, MdbFactory.getInstance(yamcsInstance));
                long gentime = tmPacket.getGenerationTime();

                ParameterValue returnPv = new ParameterValue(Verifier.YAMCS_PARAMETER_RETURN_VALUE);
                returnPv.setAcquisitionTime(gentime);
                returnPv.setGenerationTime(gentime);
                returnPv.setEngValue(ValueUtility.getStringValue("FAILURE"));

                CommandHistoryPublisher publisher = ProcessingApi.verifyProcessor(yamcsInstance, "realtime").getCommandHistoryPublisher();
                publisher.publishAck(
                    pc.getCommandId(),
                    CommandHistoryPublisher.CommandComplete_KEY,
                    gentime,
                    AckStatus.NOK,
                    "FAILURE",
                    returnPv
                );
            }

        } catch (Exception e) {
            log.error(
                "Failed to query database for previous file listings from cmdhist | S(1, 8)",
                e.toString()
            );
        }

        ArrayList<TmPacket> pktList = new ArrayList<>();
        pktList.add(tmPacket);

        return pktList;
    }

    @Override
    public PreparedCommand process(PreparedCommand pusTelecommand) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'process'");
    }
}
