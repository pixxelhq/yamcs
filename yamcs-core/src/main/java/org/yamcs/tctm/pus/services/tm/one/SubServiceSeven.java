package org.yamcs.tctm.pus.services.tm.one;

import java.util.ArrayList;

import org.yamcs.TmPacket;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.protobuf.Event.EventSeverity;
import org.yamcs.tctm.pus.PusTmManager;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;

public class SubServiceSeven implements PusSubService {
    EventProducer eventProducer;
    String yamcsInstance;

    static final String source = "Service: 1 | SubService: 7 | Completion";
    static final String TC_COMPLETION_EXECUTION_SUCCESS = "TC_COMPLETION_EXECUTION_SUCCESS";

    public SubServiceSeven(String yamcsInstance) {
        this.yamcsInstance = yamcsInstance;
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, source, 10_000, false);
    }

    @Override
    public ArrayList<TmPacket> process(TmPacket tmPacket) {
        PusTmCcsdsPacket pPkt = new PusTmCcsdsPacket(tmPacket.getPacket());

        byte[] dataField = pPkt.getDataField();
        int tcCcsdsApid = ByteArrayUtils.decodeUnsignedShort(dataField, 0) & 0x07FF;
        int tcCcsdsSeqCount = ByteArrayUtils.decodeUnsignedShort(dataField, 2) & 0x3FFF;

        if (PusTmManager.destinationId != pPkt.getDestinationID())
            return null;

        eventProducer.sendEvent(EventSeverity.INFO, TC_COMPLETION_EXECUTION_SUCCESS,
                "TC with (Source ID: " + pPkt.getDestinationID() + " | Apid: " + ServiceOne.ccsdsApids.get(tcCcsdsApid) + " | Packet Seq Count: " + tcCcsdsSeqCount + ") has succeeded execution",
                tmPacket.getGenerationTime());

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
