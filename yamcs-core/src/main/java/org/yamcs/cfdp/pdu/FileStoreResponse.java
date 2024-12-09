package org.yamcs.cfdp.pdu;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.yamcs.cfdp.pdu.FileStoreRequest.FilestoreType;

import com.google.common.primitives.Bytes;

public class FileStoreResponse extends TLV {
    FilestoreType actionCode;
    byte statusCode;

    LV firstFileName;
    LV secondFileName;

    public static Map<FilestoreType, Map<Byte, String>> actionStatusMapping;
    static {
        actionStatusMapping = new EnumMap<>(FilestoreType.class);

        for (FilestoreType ft : FilestoreType.values()) {
            Map<Byte, String> mm = new HashMap<>(Map.of(
                (byte) 0, "Successful",
                (byte) 15, "Not performed"
            ));

            switch (ft) {
                case CREATE -> mm.put((byte) 1, "Create not allowed");
                case DELETE -> {
                    mm.put((byte) 1, "File does not exist");
                    mm.put((byte) 2, "Delete not allowed");
                }
                case RENAME -> {
                    mm.put((byte) 1, "Old File Name does not exist");
                    mm.put((byte) 2, "New File Name already exists");
                    mm.put((byte) 3, "Rename not allowed");
                }
                case APPEND, REPLACE -> {
                    mm.put((byte) 1, "FirstFileName does not exist");
                    mm.put((byte) 2, "SecondFileName does not exist");
                    mm.put((byte) 3, ft == FilestoreType.APPEND ? "Append not allowed" : "Replace not allowed");
                }
                case CREATE_DIRECTORY -> mm.put((byte) 1, "Directory cannot be created");
                case REMOVE_DIRECTORY -> {
                    mm.put((byte) 1, "Directory does not exist");
                    mm.put((byte) 2, "Delete not allowed");
                }
                case DENY_FILE, DENY_DIRECTORY -> mm.put((byte) 2, "Delete not allowed");
                case COMPRESS -> {
                    mm.put((byte) 1, "Directory does not exist");
                    mm.put((byte) 2, "Command Failed");
                }
                case UNCOMPRESS -> {
                    mm.put((byte) 1, "Archive does not exist");
                    mm.put((byte) 2, "`mkdir` command failed");
                    mm.put((byte) 3, "Command Failed");
                }
                case VERIFY_CHECKSUM -> {
                    mm.put((byte) 1, "File does not exist");
                    mm.put((byte) 2, "Checksum does not exist");
                    mm.put((byte) 3, "File Open failed");
                    mm.put((byte) 4, "File Read failed");
                    mm.put((byte) 5, "Checksum open failed");
                    mm.put((byte) 6, "Checksum read failed");
                    mm.put((byte) 7, "CHecksum failed");
                    mm.put((byte) 8, "ile Close failed");
                    mm.put((byte) 9, "Checksum file close failed");
                }
                case UPDATE_XDI -> {
                    mm.put((byte) 1, "File does not exist");
                    mm.put((byte) 2, "Subaction invalid");
                    mm.put((byte) 3, "Command Failed");
                }
                default -> {}  // No additional handling needed for other cases
            }

            actionStatusMapping.put(ft, mm);
        }
    }

    public FileStoreResponse(FilestoreType actionCode, byte statusCode, LV firstFileName) {
        this(actionCode, statusCode, firstFileName, null);
    }

    public FileStoreResponse(FilestoreType actionCode, byte statusCode, LV firstFileName, LV secondFileName) {
        super(TLV.TYPE_FILE_STORE_RESPONSE, encode(actionCode, statusCode, firstFileName, secondFileName));

        this.actionCode = actionCode;
        this.statusCode = statusCode;
        this.firstFileName = firstFileName;
        this.secondFileName = secondFileName;
    }

    public static byte[] encode(FilestoreType actionCode, byte statusCode, LV firstFileName, LV secondFileName) {
        if (secondFileName == null)
            return Bytes.concat(ByteBuffer.allocate(1).put((byte) ((actionCode.getByte() << 4 & 0xF0) | (statusCode & 0x0F))).array(), firstFileName.getBytes());
        
        return Bytes.concat(ByteBuffer.allocate(1).put((byte) ((actionCode.getByte() << 4 & 0xF0) | (statusCode & 0x0F))).array(), firstFileName.getBytes(), secondFileName.getBytes());
    }

    public static FileStoreResponse fromValue(byte[] value) {
        ByteBuffer bb = ByteBuffer.wrap(value);
        byte code = bb.get();

        FilestoreType action = FilestoreType.fromByte((byte) ((code & 0xF0) >> 4));
        byte statusCode = (byte) (code & 0x0F);

        LV firstFileName = LV.readLV(bb);

        LV secondFileName = null;
        if (bb.hasRemaining()) {
            byte bf = bb.get(0);
            if (bf != 0) {
                secondFileName = LV.readLV(bb);
            }
        }

        return new FileStoreResponse(action, statusCode, firstFileName, secondFileName);
    }

    public byte getStatusCode() {
        return statusCode;
    }

    @Override
    public String toString() {
        return "FileStoreResponse [action=" + actionCode.name() + ", status=" + actionStatusMapping.get(actionCode).get(statusCode) + ", f1=" + firstFileName + ", f2=" + secondFileName + "]\n";
    }
}
