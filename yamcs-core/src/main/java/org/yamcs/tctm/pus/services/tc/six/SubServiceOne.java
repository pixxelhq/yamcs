package org.yamcs.tctm.pus.services.tc.six;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.pus.PusTcManager;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tc.PusTcCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.tctm.pus.tuples.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

        List<Pair<Integer, Integer>> baseIdMap = ServiceSix.memoryIds.get(new Pair<>(apid, memoryId)).get(baseId);
        ArrayList<byte[]> loadData = new ArrayList<>();

        for (int index = 0; index < nFields; index++) {
            int argOffsetValue = (int) ByteArrayUtils.decodeCustomInteger(cmdLoadData, 0, ServiceSix.offsetArgumentSize);

            int offsetValue, dataLength;
            for (Pair<Integer, Integer> offsetMap: baseIdMap) {
                offsetValue = offsetMap.getFirst();
                dataLength = offsetMap.getSecond();

                if (offsetValue == argOffsetValue) {
                    // Calculate CRC16 checksum
                    byte[] dataToBeLoaded = Arrays.copyOfRange(cmdLoadData, ServiceSix.offsetArgumentSize, ServiceSix.offsetArgumentSize + dataLength);

                    ByteBuffer bbN = ByteBuffer.wrap(new byte[dataLength]);
                    bbN.put(dataToBeLoaded);

                    int checksum = ServiceSix.crc.compute(bbN.array(), 0, dataLength);

                    // Create data to load for each N (dataLength + data)
                    ByteBuffer bb = ByteBuffer.wrap(new byte[ServiceSix.offsetSize + ServiceSix.lengthSize + dataLength + ServiceSix.checksumSize]);
                    bb.put(ByteArrayUtils.encodeCustomInteger(offsetValue, ServiceSix.offsetSize));
                    bb.put(ByteArrayUtils.encodeCustomInteger(dataLength, ServiceSix.lengthSize));
                    bb.put(dataToBeLoaded);
                    bb.put(ByteArrayUtils.encodeCustomInteger(checksum, ServiceSix.checksumSize));
                    
                    loadData.add(bb.array());
                    cmdLoadData = Arrays.copyOfRange(cmdLoadData, ServiceSix.offsetArgumentSize + dataLength, cmdLoadData.length);
                    break;
                }
            }
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
