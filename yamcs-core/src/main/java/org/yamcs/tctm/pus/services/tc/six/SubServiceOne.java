package org.yamcs.tctm.pus.services.tc.six;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.mdb.CommandEncodingException;
import org.yamcs.tctm.pus.PusTcManager;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tc.PusTcCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SubServiceOne implements PusSubService {
    String yamcsInstance;
    YConfiguration config;

    SubServiceOne(String yamcsInstance, YConfiguration config) {
        this.yamcsInstance = yamcsInstance;
        this.config = config;
    }

    @Override
    public PreparedCommand process(PreparedCommand telecommand) {
        PusTcCcsdsPacket.setPusHeadersSpareFieldAndSourceID(telecommand);

        byte[] binary = telecommand.getBinary();
        int apid = PusTcCcsdsPacket.getAPID(binary);
        byte[] dataField = PusTcCcsdsPacket.getDataField(binary);

        int memoryId = (int) ByteArrayUtils.decodeCustomInteger(dataField, 0, ServiceSix.memoryIdSize);
        int baseId = (int) ByteArrayUtils.decodeCustomInteger(dataField, ServiceSix.memoryIdSize, ServiceSix.baseIdSize);
        int nFields = (int) ByteArrayUtils.decodeCustomInteger(dataField, ServiceSix.memoryIdSize + ServiceSix.baseIdSize, ServiceSix.nfieldsSize);
        byte[] cmdLoadData = Arrays.copyOfRange(dataField, ServiceSix.memoryIdSize + ServiceSix.baseIdSize + ServiceSix.nfieldsSize, dataField.length);

        List<ServiceSix.Pair<Integer, Integer>> baseIdMap = ServiceSix.memoryIds.get(new ServiceSix.Pair<>(apid, memoryId)).get(baseId);
        ArrayList<byte[]> loadData = new ArrayList<>();

        if (nFields != baseIdMap.size())
            throw new CommandEncodingException("Number of the offsets provided in the config does not match the command populated in the MDb");

        for(ServiceSix.Pair<Integer, Integer> offsetMap: baseIdMap) {
            int offsetValue = offsetMap.getFirst();
            int dataLength = offsetMap.getSecond();

            ByteBuffer bb = ByteBuffer.wrap(new byte[ServiceSix.offsetSize + ServiceSix.lengthSize + dataLength]);
            bb.put(ByteArrayUtils.encodeCustomInteger(offsetValue, ServiceSix.offsetSize));
            bb.put(ByteArrayUtils.encodeCustomInteger(dataLength, ServiceSix.lengthSize));
            bb.put(Arrays.copyOfRange(cmdLoadData, 0, dataLength));

            loadData.add(bb.array());
            cmdLoadData = Arrays.copyOfRange(cmdLoadData, dataLength, cmdLoadData.length);
        }

        // Construct new TC
        int loadDataSize = loadData.stream()
                            .mapToInt(arr -> arr.length)
                            .sum();
        ByteBuffer bb = ByteBuffer.wrap(new byte[PusTcManager.DEFAULT_PRIMARY_HEADER_LENGTH + PusTcManager.secondaryHeaderLength + ServiceSix.memoryIdSize + ServiceSix.baseIdSize + ServiceSix.nfieldsSize + loadDataSize]);
        bb.put(Arrays.copyOfRange(binary, 0, PusTcManager.DEFAULT_PRIMARY_HEADER_LENGTH + PusTcManager.secondaryHeaderLength + ServiceSix.memoryIdSize + ServiceSix.baseIdSize + ServiceSix.nfieldsSize));
        loadData.forEach(bb::put);

        telecommand.setBinary(bb.array());
        return telecommand;
    }

    @Override
    public ArrayList<TmPacket> process(TmPacket tmPacket) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'process'");
    }
    
}
