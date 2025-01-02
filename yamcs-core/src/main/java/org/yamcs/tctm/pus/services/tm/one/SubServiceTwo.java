package org.yamcs.tctm.pus.services.tm.one;

import java.util.ArrayList;
import java.util.Arrays;

import org.yamcs.TmPacket;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.protobuf.Event.EventSeverity;
import org.yamcs.tctm.pus.PusTmManager;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;


public class SubServiceTwo implements PusSubService {
    EventProducer eventProducer;

    static final String source = "Service: 1 | SubService: 2 | Rejected";
    static final String TC_ACCEPTANCE_FAILED = "TC_ACCEPTANCE_FAILED";

    public SubServiceTwo(String yamcsInstance) {
        // FIXME: Confirm the repeatedEventTimeoutMillisec value, which most likely depends on the datarate of TM
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, this.getClass().getSimpleName(), 10);
        eventProducer.setSource(source);
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
            eventProducer.sendEvent(EventSeverity.CRITICAL, TC_ACCEPTANCE_FAILED,
                "TC with (Source ID: " + pPkt.getDestinationID() + " | Apid: " + ServiceOne.ccsdsApids.get(tcCcsdsApid) + " | Packet Seq Count: "
                    + tcCcsdsSeqCount
                    + ") has been rejected | Error Code: " + ServiceOne.failureCodes.get(Integer.valueOf((int) errorCode))
                    + "| Failure reason: " + errorReason,
                tmPacket.getGenerationTime());

        } catch (Exception e) {
            eventProducer.sendEvent(EventSeverity.CRITICAL, TC_ACCEPTANCE_FAILED,
                "TC with (Source ID: " + pPkt.getDestinationID() + " | Apid: " + ServiceOne.ccsdsApids.get(tcCcsdsApid) + " | Packet Seq Count: "
                    + tcCcsdsSeqCount
                    + ") has been rejected | Error Code: " + errorCode + " (No enumeration found)"
                    + "| Failure reason: " + errorReason,
                tmPacket.getGenerationTime());
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
