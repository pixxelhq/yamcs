package org.yamcs.tctm.pus.services.tm.thirteen;

import static org.yamcs.tctm.pus.services.filetransfer.thirteen.packets.DownlinkS13Packet.S13_TM;

import java.util.ArrayList;
import java.util.Arrays;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.packets.DownlinkS13Packet.PacketType;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;

public class SubServiceTwo implements PusSubService {
    String yamcsInstance;

    public SubServiceTwo(String yamcsInstance, YConfiguration subServiceSixConfig) {
        this.yamcsInstance = yamcsInstance;
    }

    @Override
    public PreparedCommand process(PreparedCommand pusTelecommand) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'process'");
    }

    @Override
    public ArrayList<TmPacket> process(TmPacket tmPacket) {
        PusTmCcsdsPacket pkt = new PusTmCcsdsPacket(tmPacket.getPacket());

        byte[] dataField = pkt.getDataField();
        
        long largePacketTransactionId = (int) ByteArrayUtils.decodeCustomInteger(dataField, 0, ServiceThirteen.largePacketTransactionIdSize);
        long partSequenceNumber = (int) ByteArrayUtils.decodeCustomInteger(dataField, ServiceThirteen.largePacketTransactionIdSize, ServiceThirteen.partSequenceNumberSize);
        byte[] filePart = Arrays.copyOfRange(dataField, ServiceThirteen.largePacketTransactionIdSize + ServiceThirteen.partSequenceNumberSize, dataField.length);
        String packetType = PacketType.INTERMEDIATE.getText();

        TupleDefinition td = S13_TM.copy();
        ServiceThirteen.s13In.emitTuple(new Tuple(td, new Object[] {
            largePacketTransactionId,
            filePart,
            partSequenceNumber,
            packetType,
            null
        }));

        ArrayList<TmPacket> pPkts = new ArrayList<>();
        pPkts.add(tmPacket);

        return pPkts;
    }
}
