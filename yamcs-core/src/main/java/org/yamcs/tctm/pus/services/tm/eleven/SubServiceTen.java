package org.yamcs.tctm.pus.services.tm.eleven;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.tctm.pus.PusTcManager;
import org.yamcs.tctm.pus.PusTmManager;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tc.PusTcCcsdsPacket;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.YarchException;
import org.yamcs.yarch.rocksdb.protobuf.Tablespace.ObjectProperties;


public class SubServiceTen implements PusSubService {
    String yamcsInstance;
    Log log;

    private static final int packIdPackSeqControlLength = 4;
    private static final int messageTypeSize = PusTmCcsdsPacket.messageTypeSize;
    private static final int messageSubTypeSize = PusTmCcsdsPacket.messageSubTypeSize;

    private static int DEFAULT_UNIQUE_SIGNATURE_SIZE = 4;
    private static int DEFAULT_REPORT_INDEX_SIZE = 1;
    private static int DEFAULT_REPORT_COUNT_SIZE = 1;

    protected int uniqueSignatureSize;
    protected int reportIndexSize;
    protected int reportCountSize;

    Bucket timetagScheduleDetailReportBucket;
    Map<Integer, String> folders = new HashMap<>();

    public SubServiceTen(String yamcsInstance, YConfiguration config) {
        this.yamcsInstance = yamcsInstance;
        log = new Log(getClass(), yamcsInstance);

        uniqueSignatureSize = config.getInt("tcIndexSize", DEFAULT_UNIQUE_SIGNATURE_SIZE);
        reportIndexSize = config.getInt("reportIndexSize", DEFAULT_REPORT_INDEX_SIZE);
        reportCountSize = config.getInt("reportCountSize", DEFAULT_REPORT_COUNT_SIZE);

        for(YConfiguration cc: config.getConfigList("folders")) {
            folders.put(cc.getInt("apid"), cc.getString("name"));
        }
        timetagScheduleDetailReportBucket = PusTmManager.reports;

        try {
            for (Map.Entry<Integer, String> folderN: folders.entrySet())
                timetagScheduleDetailReportBucket.putObject("timetagScheduleDetailReport/" + folderN.getValue() + "/", "application/octet-stream", new HashMap<>(), new byte[0]);

        } catch (IOException e) {
            log.error("Unable to create a directory `" + timetagScheduleDetailReportBucket.getName() + "/timetagScheduleDetailReport` for (Service - 11 | SubService - 10)", e);
            throw new YarchException("Failed to create a directory `" + timetagScheduleDetailReportBucket.getName() + "/timetagScheduleDetailReport` for (Service - 11 | SubService - 10)", e);
        }
    }

    public ObjectProperties findObject(int uniqueSignature) throws IOException {
        List<ObjectProperties> fileObjects = timetagScheduleDetailReportBucket.listObjects();
        for (ObjectProperties prop: fileObjects) {
            Map<String, String> metadata = prop.getMetadataMap();

            if (metadata != null) {
                String sig = metadata.get("UniqueSignature");
                if (sig != null) {
                    int signature = Integer.parseInt(sig);
                    if(signature == uniqueSignature)
                        return prop;
                }
            }
        }
        return null;
    }

    public ArrayList<byte[]> generateTimetagScheduleDetailReport(long gentime, Map<Long, byte[]> requestTcPacketsMap, ObjectProperties foundObject, int apid) {
        String filename;
        String content;

        if (foundObject == null) {
            filename = "timetagScheduleDetailReport/" + folders.get(apid) + "/" + LocalDateTime.ofInstant(
                Instant.ofEpochSecond(gentime),
                ZoneId.of("GMT")
            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")) + ".csv";

            try (StringWriter stringWriter = new StringWriter();
                BufferedWriter writer = new BufferedWriter(stringWriter)) {

                // Write header
                writer.write("ReleaseTimetag,SourceId,CommandApid,CommandCcsdsSeqCount,PusService,PusSubService");
                writer.newLine();
                writer.flush();
                
                content = stringWriter.getBuffer().toString();

            } catch (IOException e) {
                throw new UncheckedIOException("S(11, 10) | Cannot save timetag summary report in bucket: " + filename + " " + (timetagScheduleDetailReportBucket != null ? " -> " + timetagScheduleDetailReportBucket.getName() : ""), e);
            }
        } else {
            filename = foundObject.getName();
            try {
                // Fetch content from foundObject
                content = new String(timetagScheduleDetailReportBucket.getObject(filename), StandardCharsets.UTF_8);

                // Delete File
                timetagScheduleDetailReportBucket.deleteObject(filename);

            } catch (IOException e) {
                throw new UncheckedIOException("S(11, 10) | Cannot delete previous timetag summary report in bucket: " + filename + " " + (timetagScheduleDetailReportBucket != null ? " -> " + timetagScheduleDetailReportBucket.getName() : ""), e);
            }
        }

        ArrayList<byte[]> releaseAndRequestTimes = new ArrayList<>();
        try (StringWriter stringWriter = new StringWriter();
            BufferedWriter writer = new BufferedWriter(stringWriter)) {

            // Add content
            writer.write(content);

            for(Map.Entry<Long, byte[]> requestTcMap: requestTcPacketsMap.entrySet()) {
                byte[] tcPacket = requestTcMap.getValue();
                long timetag = requestTcMap.getKey();

                String timetagStr = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timetag),
                    ZoneId.of("GMT")
                ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

                int commandApid = PusTcCcsdsPacket.getAPID(tcPacket);
                int sourceId = PusTcCcsdsPacket.getSourceId(tcPacket);
                int commandCcsdsSeqCount = PusTcCcsdsPacket.getSequenceCount(tcPacket);
                int commandPusService = PusTcCcsdsPacket.getMessageType(tcPacket);
                int commandPusSubService = PusTcCcsdsPacket.getMessageSubType(tcPacket);

                writer.write(timetagStr + "," + sourceId + "," + commandApid + "," + commandCcsdsSeqCount + "," + commandPusService + "," + commandPusSubService);
                writer.newLine();

                // Create new Payload
                ByteBuffer bb = ByteBuffer.wrap(new byte[PusTcManager.timetagLength + ServiceEleven.sourceIdSize + ServiceEleven.apidSize + ServiceEleven.seqCountSize + messageTypeSize + messageSubTypeSize]);
                bb.put(ByteArrayUtils.encodeCustomInteger(timetag, PusTcManager.timetagLength));
                bb.put(ByteArrayUtils.encodeCustomInteger(sourceId, ServiceEleven.sourceIdSize));
                bb.put(ByteArrayUtils.encodeCustomInteger(commandApid, ServiceEleven.apidSize));
                bb.put(ByteArrayUtils.encodeCustomInteger(commandCcsdsSeqCount, ServiceEleven.seqCountSize));
                bb.put(ByteArrayUtils.encodeCustomInteger(commandPusService, messageTypeSize));
                bb.put(ByteArrayUtils.encodeCustomInteger(commandPusSubService, messageSubTypeSize));

                releaseAndRequestTimes.add(bb.array());
            }
            writer.flush();

            // Put report in the bucket
            timetagScheduleDetailReportBucket.putObject(filename, "csv", null, stringWriter.getBuffer().toString().getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            throw new UncheckedIOException("S(11, 10) | Cannot save timetag detail report in bucket: " + filename + " | " + (timetagScheduleDetailReportBucket != null ? " -> " + timetagScheduleDetailReportBucket.getName() : ""), e);
        }

        return releaseAndRequestTimes;
    }

    @Override
    public PreparedCommand process(PreparedCommand telecommand) {
        throw new UnsupportedOperationException("Unimplemented method 'process'");
    }

    @Override
    public ArrayList<TmPacket> process(TmPacket tmPacket) {
        PusTmCcsdsPacket pPkt = new PusTmCcsdsPacket(tmPacket.getPacket());
        byte[] dataField = pPkt.getDataField();

        int apid = pPkt.getAPID();
        Map<Long, byte[]> requestTcPacketsMap = getRequestTcPacketMap(dataField);

        // Check if a unique file already exists
        long generationTime = ByteArrayUtils.decodeCustomInteger(pPkt.getGenerationTime(), 0, PusTmManager.absoluteTimeLength);
        String filename = "timetagScheduleDetailReport/" + folders.get(apid) + "/" + LocalDateTime.ofInstant(
            Instant.ofEpochSecond(generationTime),
            ZoneId.of("GMT")
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")) + ".csv";

        ArrayList<byte[]> newPayload = new ArrayList<>();
        try {
            ObjectProperties foundObject = timetagScheduleDetailReportBucket.findObject(filename);
            newPayload = generateTimetagScheduleDetailReport(generationTime, requestTcPacketsMap, foundObject, apid);

        } catch (IOException e) {
            throw new UncheckedIOException("S(11, 10) | Unable to find object with filename: " + filename + " in bucket: " + (timetagScheduleDetailReportBucket != null ? " -> " + timetagScheduleDetailReportBucket.getName() : ""), e);
        }

        // Create a new TmPacket similar S(11, 10)
        byte[] primaryHeader = pPkt.getPrimaryHeader();
        byte[] secondaryHeader = pPkt.getSecondaryHeader();
        byte[] numOfReportsArr = Arrays.copyOfRange(dataField, 0, ServiceEleven.reportCountSize);

        ByteBuffer bb = ByteBuffer.wrap(new byte[PusTmManager.PRIMARY_HEADER_LENGTH + PusTmManager.secondaryHeaderLength + ServiceEleven.reportCountSize + PusTcManager.timetagLength + ServiceEleven.sourceIdSize + ServiceEleven.apidSize + ServiceEleven.seqCountSize + messageTypeSize + messageSubTypeSize]);
        bb.put(primaryHeader);
        bb.put(secondaryHeader);
        bb.put(numOfReportsArr);
        newPayload.forEach(bb::put);

        ArrayList<TmPacket> pPkts = new ArrayList<>();
        pPkts.add(tmPacket);
        
        return pPkts;
    }

    private static Map<Long, byte[]> getRequestTcPacketMap(byte[] dataField) {
        int numOfReports = (int) ByteArrayUtils.decodeCustomInteger(dataField, 0, ServiceEleven.reportCountSize);
        byte[] reportArr = Arrays.copyOfRange(dataField, ServiceEleven.reportCountSize, dataField.length);

        Map<Long, byte[]> requestTcPacketsMap = new HashMap<>(numOfReports);

        for (int reportIndex = 0; reportIndex < numOfReports; reportIndex++) {
            long releaseTime = ByteArrayUtils.decodeCustomInteger(reportArr, 0, PusTcManager.timetagLength);
            int requestTcPacketLength = (ByteArrayUtils.decodeShort(reportArr,
                    PusTcManager.timetagLength + packIdPackSeqControlLength) + 1) + 6;

            byte[] requestTcPacket = Arrays.copyOfRange(reportArr, PusTcManager.timetagLength,
                    PusTcManager.timetagLength + requestTcPacketLength);
            requestTcPacketsMap.put(releaseTime, requestTcPacket);

            reportArr = Arrays.copyOfRange(reportArr, PusTcManager.timetagLength + requestTcPacketLength,
                    reportArr.length);
        }
        
        return requestTcPacketsMap;
    }
}
