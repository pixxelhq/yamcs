package org.yamcs.tctm.pus.services.tm.five;

import java.util.ArrayList;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.logging.Log;
import org.yamcs.tctm.pus.services.PusSubService;

public class SubServiceOne implements PusSubService {
    String yamcsInstance;
    Log log;

    EventProducer eventProducer;
    static final String source = "Service: 5 | SubService: 1";

    public SubServiceOne(String yamcsInstance, YConfiguration subServiceSixConfig) {
        this.yamcsInstance = yamcsInstance;
        log = new Log(getClass(), yamcsInstance);

        // FIXME: Confirm the repeatedEventTimeoutMillisec value, which most likely depends on the datarate of TM
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, this.getClass().getSimpleName(), 10);
        eventProducer.setSource(source);
    }

    @Override
    public PreparedCommand process(PreparedCommand pusTelecommand) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'process'");
    }

    @Override
    public ArrayList<TmPacket> process(TmPacket tmPacket) {
        // FIXME: ToDo | Send event notification as per the deduced auxillary data

        ArrayList<TmPacket> pPkts = new ArrayList<>();
        pPkts.add(tmPacket);

        return pPkts;
    }

}
