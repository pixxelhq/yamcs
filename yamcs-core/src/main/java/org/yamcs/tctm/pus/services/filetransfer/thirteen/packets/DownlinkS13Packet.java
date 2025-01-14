package org.yamcs.tctm.pus.services.filetransfer.thirteen.packets;

import org.yamcs.tctm.pus.services.filetransfer.thirteen.S13TransactionId.S13UniqueId;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;

import java.util.HashMap;
import java.util.Map;

import org.yamcs.protobuf.TransferDirection;

public class DownlinkS13Packet extends FileTransferPacket {
    public static final TupleDefinition S13_TM = new TupleDefinition();

    public static enum PacketType {
        FIRST("FIRST"), INTERMEDIATE("INTERMEDIATE"), LAST("LAST"), ABORTION("ABORTION");

        private final String packetType;

        PacketType(String packetType) {
            this.packetType = packetType;
        }

        public String getText() {
            return this.packetType;
        }

        public static PacketType fromString(String text) {
            for (PacketType b : PacketType.values()) {
                if (b.packetType.equalsIgnoreCase(text)) {
                    return b;
                }
            }
            return null;
        }
    }

    static final String COL_LARGE_PACKET_TRANSACTION_ID = "largePacketTransactionId";
    static final String COL_FILE_PART = "filePart";
    static final String COL_PART_SEQUENCE_NUMBER = "partSequenceNumber";
    static final String COL_PACKET_TYPE = "packetType";
    static final String COL_FAILURE_REASON = "failureReason";

    static {
        S13_TM.addColumn(COL_LARGE_PACKET_TRANSACTION_ID, DataType.LONG);
        S13_TM.addColumn(COL_FILE_PART, DataType.BINARY);
        S13_TM.addColumn(COL_PART_SEQUENCE_NUMBER, DataType.LONG);
        S13_TM.addColumn(COL_PACKET_TYPE, DataType.ENUM);
        S13_TM.addColumn(COL_FAILURE_REASON, DataType.INT);
    }

    final static Map<Integer, Integer> failureCodeTranslation = new HashMap<>();
    static {
        failureCodeTranslation.put(0, 16);
        failureCodeTranslation.put(1, 17);
        failureCodeTranslation.put(2, 18);
    }

    protected PacketType packetType;
    protected long partSequenceNumber;
    protected byte[] filePart;
    protected Integer failureCode;

    DownlinkS13Packet(S13UniqueId uniquenessId, long partSequenceNumber, byte[] filePart, PacketType packetType, Integer failureCode) {
        super(uniquenessId);
        this.packetType = packetType;
        this.partSequenceNumber = partSequenceNumber;
        this.filePart = filePart;
        this.failureCode = failureCode;
    }

    public static DownlinkS13Packet fromTuple(Tuple t) {
        long largePacketTransactionId = (Long) t.getLongColumn(COL_LARGE_PACKET_TRANSACTION_ID);
        PacketType packetType = PacketType.fromString((String) t.getColumn(COL_PACKET_TYPE));
        Integer fc = t.getColumn(COL_FAILURE_REASON) == null? null: failureCodeTranslation.get((Integer) t.getColumn(COL_FAILURE_REASON));

        // In the case of S13, largePacketTransactionId is the sourceId (i.e the remoteId)
        return new DownlinkS13Packet(new S13UniqueId(largePacketTransactionId, TransferDirection.DOWNLOAD), -1, null, packetType, fc);
    }
    
    public PacketType getPacketType() {
        return packetType;
    }

    public byte[] getFilePart() {
        return filePart;
    }

    public long getPartSequenceNumber() {
        return partSequenceNumber;
    }

    public Integer getFailureCode() {
        return failureCode;
    }

    @Override
    public String toString() {
        return "LargePacketTransactionId: " + this.uniquenessId.getLargePacketTransactionId() + " | FailureCode: " + failureCode;
    }
}