package org.yamcs.tctm.pus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.logging.Log;
import org.yamcs.tctm.pus.services.tm.eleven.ServiceEleven;
import org.yamcs.tctm.pus.services.tm.fifteen.ServiceFifteen;
import org.yamcs.tctm.pus.services.tm.fourteen.ServiceFourteen;
import org.yamcs.tctm.pus.services.tm.six.ServiceSix;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.YarchException;
import org.yamcs.time.Instant;
import org.yamcs.time.TimeService;
import org.yamcs.tctm.pus.services.PusService;
import org.yamcs.tctm.pus.services.PusSink;
import org.yamcs.tctm.pus.services.tm.twenty.ServiceTwenty;
import org.yamcs.tctm.pus.services.tm.seventeen.ServiceSeventeen;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;
import org.yamcs.tctm.pus.services.tm.one.ServiceOne;
import org.yamcs.tctm.pus.services.tm.two.ServiceTwo;
import org.yamcs.tctm.pus.services.tm.three.ServiceThree;
import org.yamcs.tctm.pus.services.tm.five.ServiceFive;
import org.yamcs.tctm.pus.services.tm.nine.ServiceNine;
import org.yamcs.tctm.pus.services.tm.thirteen.ServiceThirteen;


public class PusTmManager extends AbstractYamcsService implements StreamSubscriber {
    Log log;
    String yamcsInstance;

    // Static Members
    public static int PRIMARY_HEADER_LENGTH = 6;
    public static int DEFAULT_SECONDARY_HEADER_LENGTH = 64;
    public static int DEFAULT_ABSOLUTE_TIME_LENGTH = 4;
    public static int PUS_HEADER_LENGTH;

    public static int secondaryHeaderLength;
    public static int absoluteTimeLength;
    public static int destinationId;

    public static TimeService timeService;

    Map<Integer, PusService> pusServices = new HashMap<>();
    YConfiguration serviceConfig;
    PusSink tmSink;
    HashMap<Stream, Stream> streamMatrix = new HashMap<>();
    YarchDatabaseInstance ydb;

    public static Bucket reports;

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();

        Spec streamMatrixSpec = new Spec();
        streamMatrixSpec.addOption("inStream", OptionType.STRING);
        streamMatrixSpec.addOption("outStream", OptionType.STRING);

        Spec bucketSpec = new Spec();
        bucketSpec.addOption("name", OptionType.STRING);
        bucketSpec.addOption("global", OptionType.BOOLEAN);
        bucketSpec.addOption("maxObjects", OptionType.INTEGER);

        spec.addOption("streamMatrix", OptionType.LIST).withElementType(OptionType.MAP).withSpec(streamMatrixSpec);
        spec.addOption("secondaryHeaderLength", OptionType.INTEGER);
        spec.addOption("absoluteTimeLength", OptionType.INTEGER);
        spec.addOption("destinationId", OptionType.INTEGER);
        spec.addOption("services", OptionType.MAP).withSpec(Spec.ANY);
        spec.addOption("bucket", OptionType.MAP).withSpec(bucketSpec);
        // FIXME:
        // Add pus spec options
        return spec;
    }

    public Bucket getOrCreateBucket(String bucketName, boolean global) throws InitException {
        YarchDatabaseInstance ydb = global ? YarchDatabase.getInstance(YamcsServer.GLOBAL_INSTANCE)
                : YarchDatabase.getInstance(yamcsInstance);
        try {
            Bucket bucket = ydb.getBucket(bucketName);
            if (bucket == null) {
                bucket = ydb.createBucket(bucketName);
            }
            return bucket;
        } catch (IOException e) {
            throw new InitException(e);
        }
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        this.yamcsInstance = yamcsInstance;

        serviceConfig = config.getConfigOrEmpty("services");
        secondaryHeaderLength = config.getInt("secondaryHeaderLength", DEFAULT_SECONDARY_HEADER_LENGTH);
        absoluteTimeLength = config.getInt("absoluteTimeLength", DEFAULT_ABSOLUTE_TIME_LENGTH);
        destinationId = config.getInt("destinationId");
        PUS_HEADER_LENGTH = 7 + absoluteTimeLength;

        ydb = YarchDatabase.getInstance(yamcsInstance);
        timeService = YamcsServer.getTimeService(yamcsInstance);

        if (!config.containsKey("streamMatrix"))
            throw new ConfigurationException(this.getClass() + ": streamMatrix needs to be defined to know the inputStream -> outStream mapping");

        for(YConfiguration c: config.getConfigList("streamMatrix")) {
            String inStream = c.getString("inStream");
            String outStream = c.getString("outStream");

            streamMatrix.put(
                Objects.requireNonNull(ydb.getStream(inStream)),
                Objects.requireNonNull(ydb.getStream(outStream))
            );
        }

        if (!config.containsKey("bucket")) {
            throw new ConfigurationException(this.getClass() + ": `bucket` config needs to be set");
        }

        YConfiguration bConfig = config.getConfigOrEmpty("bucket");
        String bucketName = bConfig.getString("name");
        boolean global = bConfig.getBoolean("global");
        int maxObjects = bConfig.getInt("maxObjects", 1000);
        try {
            reports = getOrCreateBucket(bucketName, global);
            reports.setMaxObjects(maxObjects);
        } catch (InitException e) {
            log.error("Unable to create a `" + bucketName + " bucket` at class: " + this.getClass(), e);
            throw new YarchException("Failed to create RDB based bucket: " + bucketName + " at class: " + this.getClass(), e);

        } catch (IOException e) {
            log.error("Unable to set maxObjects for `" + bucketName + " bucket` at class: " + this.getClass(), e);
            throw new YarchException("Failed to set maxObjects for RDB based bucket: " + bucketName + " at class: " + this.getClass(), e);
        }

        tmSink = new PusSink() {
            @Override
            public void emitTmTuple(TmPacket tmPacket, Stream stream, String tmLinkName) {
                Long obt = tmPacket.getObt() == Long.MIN_VALUE ? null : tmPacket.getObt();

                Tuple t = new Tuple(StandardTupleDefinitions.TM, new Object[] {
                    tmPacket.getGenerationTime(),
                    tmPacket.getSeqCount(),
                    tmPacket.getReceptionTime(),
                    tmPacket.getStatus(),
                    tmPacket.getPacket(),
                    tmPacket.getEarthReceptionTime(),
                    obt,
                    tmLinkName,
                    null
                });
                stream.emitTuple(t);
            }
            @Override
            public void emitTcTuple(PreparedCommand pc, Stream stream) {
                throw new UnsupportedOperationException("Unimplemented method 'emitTcTuple'");
            }
        };

        initializePUSServices();
    }

    private void initializePUSServices() {
        pusServices.put(1, new ServiceOne(yamcsInstance, serviceConfig.getConfigOrEmpty("one")));
        pusServices.put(2, new ServiceTwo(yamcsInstance, serviceConfig.getConfigOrEmpty("two")));
        pusServices.put(3, new ServiceThree(yamcsInstance, serviceConfig.getConfigOrEmpty("three")));
        pusServices.put(5, new ServiceFive(yamcsInstance, serviceConfig.getConfigOrEmpty("five")));
        pusServices.put(6, new ServiceSix(yamcsInstance, serviceConfig.getConfigOrEmpty("six")));
        pusServices.put(9, new ServiceNine(yamcsInstance, serviceConfig.getConfigOrEmpty("nine")));
        pusServices.put(11, new ServiceEleven(yamcsInstance, serviceConfig.getConfigOrEmpty("eleven")));
        pusServices.put(13, new ServiceThirteen(yamcsInstance, serviceConfig.getConfigOrEmpty("thirteen")));
        pusServices.put(14, new ServiceFourteen(yamcsInstance, serviceConfig.getConfigOrEmpty("fourteen")));
        pusServices.put(15, new ServiceFifteen(yamcsInstance, serviceConfig.getConfigOrEmpty("fifteen")));
        pusServices.put(17, new ServiceSeventeen(yamcsInstance, serviceConfig.getConfigOrEmpty("seventeen")));
        pusServices.put(20, new ServiceTwenty(yamcsInstance, serviceConfig.getConfigOrEmpty("twenty")));
    }

    public void acceptTmPacket(TmPacket tmPacket, String tmLinkName, Stream stream) {
        byte[] b = tmPacket.getPacket();
        if(!pusServices.containsKey(PusTmCcsdsPacket.getMessageType(b))) {
            log.error("Invalid service packet: {}", PusTmCcsdsPacket.getMessageType(b));
            return;
        }
        ArrayList<TmPacket> pkts = pusServices.get(PusTmCcsdsPacket.getMessageType(b)).extractPusModifiers(tmPacket);

        if (pkts != null){
            for (TmPacket pkt: pkts) {
                tmSink.emitTmTuple(pkt, stream, tmLinkName);
            }
        }
    }

    @Override
    public void onTuple(Stream stream, Tuple tuple) {
        long rectime = (Long) tuple.getColumn(StandardTupleDefinitions.TM_RECTIME_COLUMN);
        long gentime = (Long) tuple.getColumn(StandardTupleDefinitions.GENTIME_COLUMN);
        Instant ertime = (Instant) tuple.getColumn(StandardTupleDefinitions.TM_ERTIME_COLUMN);

        int seqCount = (Integer) tuple.getColumn(StandardTupleDefinitions.SEQNUM_COLUMN);
        byte[] pkt = (byte[]) tuple.getColumn(StandardTupleDefinitions.TM_PACKET_COLUMN);
        String tmLinkName = (String) tuple.getColumn(StandardTupleDefinitions.TM_LINK_COLUMN);

        TmPacket tmPacket = new TmPacket(rectime, gentime, seqCount, pkt);
        tmPacket.setEarthReceptionTime(ertime);

        Stream outStream = streamMatrix.get(stream);
        acceptTmPacket(tmPacket, tmLinkName, outStream);
    }

    @Override
    protected void doStart() {
        for (Map.Entry<Stream, Stream> streamMap : streamMatrix.entrySet()) {
            Stream inStream = streamMap.getKey();
            inStream.addSubscriber(this);
        }
        notifyStarted();
    }

    @Override
    protected void doStop() {
        for (Map.Entry<Stream, Stream> streamMap : streamMatrix.entrySet()) {
            Stream inStream = streamMap.getKey();
            inStream.removeSubscriber(this);
        }
        notifyStopped();
    }

}
