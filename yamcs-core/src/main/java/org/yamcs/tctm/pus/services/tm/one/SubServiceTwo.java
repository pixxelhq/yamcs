package org.yamcs.tctm.pus.services.tm.one;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tm.PusTmPacket;

// FIXME: Update the error codes
enum AcceptanceRejectionCode {
    R1,
    R2,
    R3
}

public class SubServiceTwo implements PusSubService {
    Map<Integer, AcceptanceRejectionCode> errorCodes = new HashMap<>();
    EventProducer eventProducer;

    static final String source = "Service: 1 | SubService: 2";
    static final String TC_ACCEPTANCE_FAILED = "TC_ACCEPTANCE_FAILED";

    public SubServiceTwo(String yamcsInstance) {
        // FIXME: Confirm the repeatedEventTimeoutMillisec value, which most likely depends on the datarate of TM
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, this.getClass().getSimpleName(), 10);
        eventProducer.setSource(source);

        populateErrorCodes();
    }

    @Override
    public void process(PusTmPacket pusTmPacket) {
        byte[] dataField = pusTmPacket.getDataField();

        int errorCode = Byte.toUnsignedInt(dataField[0]);
        byte[] deducedPresence = Arrays.copyOfRange(dataField, 1, dataField.length);

        eventProducer.sendCritical(TC_ACCEPTANCE_FAILED,
                "TC with Destination ID: " + pusTmPacket.getDestinationID() + " has been rejected | Error Code: " + errorCodes.get(errorCode) + " Deduced: " + deducedPresence);
    }

    public void populateErrorCodes() {
        errorCodes.put(1, AcceptanceRejectionCode.R1);
        errorCodes.put(2, AcceptanceRejectionCode.R2);
        errorCodes.put(3, AcceptanceRejectionCode.R3);
    }
}