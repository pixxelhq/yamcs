package org.yamcs.tctm.pus.services.tm.one;

import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tm.PusTmPacket;

public class SubServiceOne implements PusSubService {
    EventProducer eventProducer;

    static final String source = "Service: 1 | SubService: 1";
    static final String TC_ACCEPTANCE_SUCCESS = "TC_ACCEPTANCE_SUCCESS";

    public SubServiceOne(String yamcsInstance) {
        // FIXME: Confirm the repeatedEventTimeoutMillisec value, which most likely depends on the datarate of TM
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, this.getClass().getSimpleName(), 10);
        eventProducer.setSource(source);
    }

    @Override
    public void process(PusTmPacket pusTmPacket) {
        eventProducer.sendInfo(TC_ACCEPTANCE_SUCCESS,
                "TC with Destination ID: " + pusTmPacket.getDestinationID() + " has been accepted");
    }
}