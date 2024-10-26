package org.yamcs.tctm.ccsds;

import static org.yamcs.parameter.SystemParametersService.getPV;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.logging.Log;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.PacketPreprocessor;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.TmPacketDataLink;
import org.yamcs.tctm.TmSink;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.ccsds.VcDownlinkManagedParameters.TMDecoder;
import org.yamcs.time.Instant;
import org.yamcs.time.TimeService;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.UnitType;
import org.yamcs.utils.DataRateMeter;

/**
 * Handles packets from one VC
 *
 * @author nm
 *
 */
public class VcTmPacketHandler implements TmPacketDataLink, VcDownlinkHandler, SystemParametersProducer {
    public static String LINK_NAMESPACE = "links/";
    
    TmSink tmSink;
    final Log log;

    volatile boolean disabled = false;
    long lastFrameSeq = -1;
    EventProducer eventProducer;

    PacketDecoder packetDecoder;
    PixxelPacketDecoder pPacketDecoder;
    PixxelPacketMultipleDecoder pMultipleDecoder;

    protected AtomicLong idleFrameCount = new AtomicLong();
    protected AtomicLong packetCount = new AtomicLong();
    DataRateMeter idleFrameCountRateMeter = new DataRateMeter();
    DataRateMeter packetCountRateMeter = new DataRateMeter();

    PacketPreprocessor packetPreprocessor;
    final String name;
    final VcDownlinkManagedParameters vmp;

    AggregatedDataLink parent;
    private TimeService timeService;
    private Instant ertime;

    boolean isIdleVcid;

    // Create as systemParameters for data collection
    private Parameter spDataInCount, spDataInRate;


    public VcTmPacketHandler(String yamcsInstance, String name, VcDownlinkManagedParameters vmp) {
        this.vmp = vmp;
        this.name = name;
        timeService = YamcsServer.getTimeService(yamcsInstance);

        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, this.getClass().getSimpleName(), 10000);
        log = new Log(this.getClass(), yamcsInstance);
        log.setContext(name);

        // Temporary Packet Decoder
        pPacketDecoder = new PixxelPacketDecoder(vmp.maxPacketLength, p -> handlePacket(p));
        pMultipleDecoder = new PixxelPacketMultipleDecoder(vmp.maxPacketLength, p -> handlePacket(p));

        packetDecoder = new PacketDecoder(vmp.maxPacketLength, p -> handlePacket(p));
        packetDecoder.stripEncapsulationHeader(vmp.stripEncapsulationHeader);

        // Check if idle vcid?
        isIdleVcid = vmp.isIdleVcid;

        try {
            if (vmp.packetPreprocessorArgs != null) {
                packetPreprocessor = YObjectLoader.loadObject(vmp.packetPreprocessorClassName, yamcsInstance,
                        vmp.packetPreprocessorArgs);
            } else {
                packetPreprocessor = YObjectLoader.loadObject(vmp.packetPreprocessorClassName, yamcsInstance);
            }
        } catch (ConfigurationException e) {
            log.error("Cannot instantiate the packet preprocessor", e);
            throw e;
        }
    }

    @Override
    public void handle(DownlinkTransferFrame frame) {
        if (disabled) {
            log.trace("Dropping frame for VC {} because the link is disabled", frame.getVirtualChannelId());
            return;
        }

        if (frame.containsOnlyIdleData()) {
            if (log.isTraceEnabled()) {
                log.trace("Dropping idle frame for VC {}, SEQ {}", frame.getVirtualChannelId(), frame.getVcFrameSeq());
            }
            lastFrameSeq = frame.getVcFrameSeq();
            idleFrameIn(1, frame.getDataEnd() - frame.getDataStart());

            // Set Earth Reception time
            ertime = frame.getEarthRceptionTime();
            if (isIdleVcid) {
                /*
                 * Do not attempt to parse the idle frames through the packetDecoders
                 * Instead, directly send it to the handlePacket handler function
                 */
                int frameHeaderLength = 6;
                handlePacket(Arrays.copyOfRange(frame.getData(), frame.getDataStart() - frameHeaderLength, frame.getDataEnd()));
            }

            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Processing frame VC {}, SEQ {}, FHP {}, DS {}, DE {}", frame.getVirtualChannelId(),
                    frame.getVcFrameSeq(),
                    frame.getFirstHeaderPointer(), frame.getDataStart(), frame.getDataEnd());
        }
        ertime = frame.getEarthRceptionTime();
        int dataStart = frame.getDataStart();
        int packetStart = frame.getFirstHeaderPointer();
        int dataEnd = frame.getDataEnd();
        byte[] data = frame.getData();

        if (vmp.tmDecoder == TMDecoder.CCSDS) {     // Multiple packets from frame | With Segmentation
            try {
                int frameLoss = frame.lostFramesCount(lastFrameSeq);
                lastFrameSeq = frame.getVcFrameSeq();

                if (packetDecoder.hasIncompletePacket()) {
                    if (frameLoss != 0) {
                        log.warn("Incomplete packet dropped because of frame loss ");
                        packetDecoder.reset();
                    } else {
                        if (packetStart != -1) {
                            packetDecoder.process(data, dataStart, packetStart - dataStart);
                        } else {
                            packetDecoder.process(data, dataStart, dataEnd - dataStart);
                        }
                    }
                }
                if (packetStart != -1) {
                    if (packetDecoder.hasIncompletePacket()) {
                        eventProducer
                                .sendWarning("Incomplete packet decoded when reaching the beginning of another packet");
                        packetDecoder.reset();
                    }
                    packetDecoder.process(data, packetStart, dataEnd - packetStart);
                }
            } catch (TcTmException e) {
                packetDecoder.reset();
                eventProducer.sendWarning(e.toString());
            }

        } else if (vmp.tmDecoder == TMDecoder.SINGLE) {     // Single packet per frame | No Segmentation
            try {   
                int frameLoss = frame.lostFramesCount(lastFrameSeq);
                lastFrameSeq = frame.getVcFrameSeq();

                if (frameLoss != 0) {
                    log.warn("Frames have been dropped in transit, sigh");
                }

                if (packetStart != -1) {
                    pPacketDecoder.process(data, packetStart, dataEnd - packetStart);
                    pPacketDecoder.reset();
                }   
            } catch (TcTmException e) {
                pPacketDecoder.reset();
                eventProducer.sendWarning(e.toString());
            } catch (ArrayIndexOutOfBoundsException e) {
                pPacketDecoder.reset();
                log.warn(e.toString() + "\n"
                        + "     Full Frame: " + StringConverter.arrayToHexString(data, true) + "\n"
                        + "     Packet Start: " + packetStart + "\n"
                        + "     Data (i.e Frame) End: " + dataEnd + "\n"
                );
                eventProducer.sendWarning(e.toString());
            }
        } else {    // Multiple packets per frame | No segmentation
            try {
                int frameLoss = frame.lostFramesCount(lastFrameSeq);
                lastFrameSeq = frame.getVcFrameSeq();

                if (frameLoss != 0) {
                    log.warn("Frames have been dropped in transit, sigh");
                }

                if (packetStart != -1) {
                    pMultipleDecoder.process(data, packetStart, dataEnd - packetStart);
                    pMultipleDecoder.reset();
                }   
            } catch (TcTmException e) {
                pMultipleDecoder.reset();

                List<String> params = List.of(
                    StringConverter.arrayToHexString(data, true),
                    Integer.toString(packetStart),
                    Integer.toString(dataEnd),
                    this.name
                );

                String message = "Full Frame: %s\n\nPacket Start: %s\n\nData (i.e Frame) End: %s\n\nLink: %s";
                log.logSentryFatal(e, message, getClass().getName(), params);

                eventProducer.sendWarning(e.toString());

            } catch (ArrayIndexOutOfBoundsException e) {
                pMultipleDecoder.reset();
                log.warn(e.toString() + "\n"
                        + "     Full Frame: " + StringConverter.arrayToHexString(data, true) + "\n"
                        + "     Packet Start: " + packetStart + "\n"
                        + "     Data (i.e Frame) End: " + dataEnd + "\n"
                );

                List<String> params = List.of(
                    StringConverter.arrayToHexString(data, true),
                    Integer.toString(packetStart),
                    Integer.toString(dataEnd),
                    this.name
                );
                String message = "Full Frame: %s\n\nPacket Start: %s\n\nData (i.e Frame) End: %s\n\nLink: %s";
                log.logSentryFatal(e, message, getClass().getName(), params);

                eventProducer.sendWarning(e.toString());

            } catch (Exception e) {
                pMultipleDecoder.reset();
                log.warn(e.toString() + "\n"
                        + "     Full Frame: " + StringConverter.arrayToHexString(data, true) + "\n"
                        + "     Packet Start: " + packetStart + "\n"
                        + "     Data (i.e Frame) End: " + dataEnd + "\n"
                );
                eventProducer.sendWarning(e.toString());

                List<String> params = List.of(
                    StringConverter.arrayToHexString(data, true),
                    Integer.toString(packetStart),
                    Integer.toString(dataEnd),
                    this.name
                );
                String message = "Full Frame: %s\n\nPacket Start: %s\n\nData (i.e Frame) End: %s\n\nLink: %s";
                log.logSentryFatal(e, message, getClass().getName(), params);
            }
        }
    }

    private void handlePacket(byte[] p) {
        if (log.isTraceEnabled()) {
            log.trace("VC {}, SEQ {} decoded packet of length {}", vmp.vcId, lastFrameSeq, p.length);
        }

        // Increment only for non-idle vcId's
        if (!isIdleVcid)
            packetCountIn(1, p.length);

        TmPacket pwt = new TmPacket(timeService.getMissionTime(), p);
        pwt.setEarthReceptionTime(ertime);

        pwt = packetPreprocessor.process(pwt);
        if (pwt != null) {
            tmSink.processPacket(pwt);
        }
    }

    @Override
    public Status getLinkStatus() {
        return disabled ? Status.DISABLED : Status.OK;
    }

    @Override
    public void enable() {
        this.disabled = false;
    }

    @Override
    public void disable() {
        this.disabled = true;
    }

    @Override
    public boolean isDisabled() {
        return disabled;
    }

    @Override
    public long getDataInCount() {
        return isIdleVcid? idleFrameCount.get(): packetCount.get();
    }

    public double getDataInCountRate() {
        return isIdleVcid? idleFrameCountRateMeter.getFiveSecondsRate(): packetCountRateMeter.getFiveSecondsRate();
    }

    @Override
    public long getDataOutCount() {
        return 0;
    }

    @Override
    public void resetCounters() {
        packetCount.set(0);
        idleFrameCount.set(0);
    }

    @Override
    public void setTmSink(TmSink tmSink) {
        this.tmSink = tmSink;
    }

    @Override
    public YConfiguration getConfig() {
        return vmp.config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public AggregatedDataLink getParent() {
        return parent;
    }

    @Override
    public void setParent(AggregatedDataLink parent) {
        this.parent = parent;
    }

    @Override
    public Map<String, Object> getExtraInfo() {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("Idle CCSDS Frames", idleFrameCount.get());
        extra.put("Valid CCSDS Packets", packetCount.get());
        extra.put("Is Idle vcId link?", isIdleVcid);
        return extra;
    }

    /**
     * returns statistics with the number of datagram received and the number of
     * invalid datagrams
     */
    @Override
    public String getDetailedStatus() {
        if (isDisabled()) {
            return "DISABLED";

        } else {
            return String.format("Idle CCSDS Frames: %d%n | Valid CCSDS Packets: %d%n", idleFrameCount.get(), packetCount.get());
        }
    }

    @Override
    public void setupSystemParameters(SystemParametersService sysParamService) {
        UnitType bps = new UnitType("Bps");
        spDataInCount = sysParamService.createSystemParameter(LINK_NAMESPACE + name + "/dataInCount", Type.UINT64,
                "The total number of items (e.g. telemetry packets) that have been received through this link");

        spDataInRate = sysParamService.createSystemParameter(LINK_NAMESPACE + name + "/dataInRate", Type.DOUBLE,
                bps, "The number of incoming bytes per second computed over a five second interval");
    }

    @Override
    public Collection<ParameterValue> getSystemParameters(long time) {
        ArrayList<ParameterValue> list = new ArrayList<>();
        try {
            collectSystemParameters(time, list);
        } catch (Exception e) {
            log.error("Exception caught when collecting link system parameters", e);
        }
        return list;
    }

    /**
     * adds system parameters link status and data in/out to the list.
     * <p>
     * The inheriting classes should call super.collectSystemParameters and then add their own parameters to the list
     * 
     * @param time
     * @param list
     */
    protected void collectSystemParameters(long time, List<ParameterValue> list) {
        list.add(getPV(spDataInCount, time, getDataInCount()));
        list.add(getPV(spDataInRate, time, getDataInCountRate()));
    }


    protected void idleFrameIn(long inCount, long size) {
        idleFrameCount.addAndGet(inCount);
        idleFrameCountRateMeter.mark(size);
    }

    protected void packetCountIn(long inCount, long size) {
        packetCount.addAndGet(inCount);
        packetCountRateMeter.mark(size);
    }
}
