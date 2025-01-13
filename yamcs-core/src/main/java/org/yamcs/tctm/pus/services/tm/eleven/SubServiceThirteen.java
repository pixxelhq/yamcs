package org.yamcs.tctm.pus.services.tm.eleven;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
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
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.tctm.pus.services.tm.one.ServiceOne;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.YarchException;
import org.yamcs.yarch.rocksdb.protobuf.Tablespace.ObjectProperties;


public class SubServiceThirteen implements PusSubService {
    String yamcsInstance;
    Log log;
    private static int requestIdSize;

    private static int DEFAULT_UNIQUE_SIGNATURE_SIZE = 4;
    private static int DEFAULT_REPORT_INDEX_SIZE = 1;
    private static int DEFAULT_REPORT_COUNT_SIZE = 1;

    protected int uniqueSignatureSize;
    protected int reportIndexSize;
    protected int reportCountSize;

    Bucket timetagScheduleSummaryReportBucket;
    Map<Integer, String> folders = new HashMap<>();

    public SubServiceThirteen(String yamcsInstance, YConfiguration config) {
        this.yamcsInstance = yamcsInstance;
        log = new Log(getClass(), yamcsInstance);

        uniqueSignatureSize = config.getInt("tcIndexSize", DEFAULT_UNIQUE_SIGNATURE_SIZE);
        reportIndexSize = config.getInt("reportIndexSize", DEFAULT_REPORT_INDEX_SIZE);
        reportCountSize = config.getInt("reportCountSize", DEFAULT_REPORT_COUNT_SIZE);

        requestIdSize = ServiceEleven.sourceIdSize + ServiceEleven.apidSize + ServiceEleven.seqCountSize;

        for(YConfiguration cc: config.getConfigList("folders")) {
            folders.put(cc.getInt("apid"), cc.getString("name"));
        }

        timetagScheduleSummaryReportBucket = PusTmManager.reports;

        try {
            for (Map.Entry<Integer, String> folderN: folders.entrySet())
                timetagScheduleSummaryReportBucket.putObject("timetagScheduleSummaryReport/" + folderN.getValue() + "/", "application/octet-stream", new HashMap<>(), new byte[0]);

        } catch (IOException e) {
            log.error("Unable to create a directory `" + timetagScheduleSummaryReportBucket.getName() + "/timetagScheduleSummaryReport` for (Service - 11 | SubService - 13)", e);
            throw new YarchException("Failed to create a directory `" + timetagScheduleSummaryReportBucket.getName() + "/timetagScheduleSummaryReport` for (Service - 11 | SubService - 13)", e);
        }
    }

    public ObjectProperties findObject(int uniqueSignature) throws IOException {
        List<ObjectProperties> fileObjects = timetagScheduleSummaryReportBucket.listObjects();
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

    public void generateTimetagScheduleSummaryReport(long gentime, Map<Long, ArrayList<Integer>> requestTcPacketsMap, ObjectProperties foundObject, int apid) {
        String filename;
        String content;

        if (foundObject == null) {
            filename = "timetagScheduleSummaryReport/" + folders.get(apid) + "/" + LocalDateTime.ofInstant(
                Instant.ofEpochSecond(gentime),
                ZoneId.of("GMT")
            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")) + ".csv";

            try (StringWriter stringWriter = new StringWriter();
                BufferedWriter writer = new BufferedWriter(stringWriter)) {

                // Write header
                writer.write("ReleaseTimetag,SourceId,CommandApid,CommandCcsdsSeqCount");
                writer.newLine();
                writer.flush();
                
                content = stringWriter.getBuffer().toString();

            } catch (IOException e) {
                throw new UncheckedIOException("S(11, 13) | Cannot save timetag summary report in bucket: " + filename + " " + (timetagScheduleSummaryReportBucket != null ? " -> " + timetagScheduleSummaryReportBucket.getName() : ""), e);
            }

        } else {
            filename = foundObject.getName();

            try {
                // Fetch content from foundObject
                content = new String(timetagScheduleSummaryReportBucket.getObject(filename), StandardCharsets.UTF_8);

                // Delete File
                timetagScheduleSummaryReportBucket.deleteObject(filename);

            } catch (IOException e) {
                throw new UncheckedIOException("S(11, 13) | Cannot delete previous timetag summary report in bucket: " + filename + " " + (timetagScheduleSummaryReportBucket != null ? " -> " + timetagScheduleSummaryReportBucket.getName() : ""), e);
            }
        }

        try (StringWriter stringWriter = new StringWriter();
            BufferedWriter writer = new BufferedWriter(stringWriter)) {

            // Add content
            writer.write(content);

            for(Map.Entry<Long, ArrayList<Integer>> requestTcMap: requestTcPacketsMap.entrySet()) {
                ArrayList<Integer> requestId = requestTcMap.getValue();

                String timetagStr = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(requestTcMap.getKey()),
                    ZoneId.of("GMT")
                ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

                int sourceId = requestId.get(0);
                String commandApid = ServiceOne.ccsdsApids.get(requestId.get(1));
                int commandCcsdsSeqCount = requestId.get(2);

                writer.write(timetagStr + "," + sourceId + "," + commandApid + "," + commandCcsdsSeqCount);
                writer.newLine();
            }
            writer.flush();

            // Put report in the bucket
            timetagScheduleSummaryReportBucket.putObject(filename, "csv", null, stringWriter.getBuffer().toString().getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            throw new UncheckedIOException("S(11, 13) | Cannot save timetag summary report in bucket: " + filename + " " + (timetagScheduleSummaryReportBucket != null ? " -> " + timetagScheduleSummaryReportBucket.getName() : ""), e);
        }
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

        int numOfReports = (int) ByteArrayUtils.decodeCustomInteger(dataField, 0, ServiceEleven.reportCountSize);
        byte[] reportArr = Arrays.copyOfRange(dataField, ServiceEleven.reportCountSize, dataField.length);

        Map<Long, ArrayList<Integer>> requestTcPacketsMap = new HashMap<>(numOfReports);

        for (int index = 0; index < numOfReports; index++) {
            long releaseTime = ByteArrayUtils.decodeCustomInteger(reportArr, 0, PusTcManager.timetagLength);
            ArrayList<Integer> tcIdentification = extractFromRequestId(reportArr);

            requestTcPacketsMap.put(releaseTime, tcIdentification);
            reportArr = Arrays.copyOfRange(reportArr, PusTcManager.timetagLength + requestIdSize, reportArr.length);
        }

        // Check if a unique file already exists
        long generationTime = ByteArrayUtils.decodeCustomInteger(pPkt.getGenerationTime(), 0, PusTmManager.absoluteTimeLength);
        String filename = "timetagScheduleSummaryReport/" + folders.get(apid) + "/" + LocalDateTime.ofInstant(
                Instant.ofEpochSecond(generationTime),
                ZoneId.of("GMT")
            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")) + ".csv";;

        try {
            ObjectProperties foundObject = timetagScheduleSummaryReportBucket.findObject(filename);
            generateTimetagScheduleSummaryReport(generationTime, requestTcPacketsMap, foundObject, apid);

        } catch (IOException e) {
            throw new UncheckedIOException("S(11, 13) | Unable to find object with name: " + filename + " in bucket: " + (timetagScheduleSummaryReportBucket != null ? " -> " + timetagScheduleSummaryReportBucket.getName() : ""), e);
        }

        ArrayList<TmPacket> pPkts = new ArrayList<>();
        pPkts.add(tmPacket);
        
        return pPkts;
    }

    private static ArrayList<Integer> extractFromRequestId(byte[] reportArr) {
        byte[] requestIdArr = Arrays.copyOfRange(reportArr, PusTcManager.timetagLength, PusTcManager.timetagLength + requestIdSize);

        ArrayList<Integer> requestId = new ArrayList<>();
        requestId.add((int) ByteArrayUtils.decodeCustomInteger(requestIdArr, 0, ServiceEleven.sourceIdSize));
        requestId.add((int) ByteArrayUtils.decodeCustomInteger(requestIdArr, ServiceEleven.sourceIdSize, ServiceEleven.apidSize));
        requestId.add((int) ByteArrayUtils.decodeCustomInteger(requestIdArr, (ServiceEleven.sourceIdSize + ServiceEleven.apidSize), ServiceEleven.seqCountSize));
        return requestId;
    }
}
