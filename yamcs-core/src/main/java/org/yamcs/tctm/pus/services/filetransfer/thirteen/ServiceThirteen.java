package org.yamcs.tctm.pus.services.filetransfer.thirteen;

import static org.yamcs.tctm.pus.services.filetransfer.thirteen.CompletedTransfer.TDEF;
import static org.yamcs.tctm.pus.services.filetransfer.thirteen.CompletedTransfer.COL_CREATION_TIME;
import static org.yamcs.tctm.pus.services.filetransfer.thirteen.CompletedTransfer.COL_DIRECTION;
import static org.yamcs.tctm.pus.services.filetransfer.thirteen.CompletedTransfer.COL_TRANSFER_STATE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Processor;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.cfdp.EntityConf;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.commanding.CommandingManager;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.filetransfer.AbstractFileTransferService;
import org.yamcs.filetransfer.FileDownloadRequests;
import org.yamcs.filetransfer.FileSaveHandler;
import org.yamcs.filetransfer.FileTransfer;
import org.yamcs.filetransfer.FileTransferFilter;
import org.yamcs.filetransfer.InvalidRequestException;
import org.yamcs.filetransfer.RemoteFileListMonitor;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.filetransfer.TransferOptions;
import org.yamcs.protobuf.EntityInfo;
import org.yamcs.protobuf.FileProxyOperationOption;
import org.yamcs.protobuf.FileTransferCapabilities;
import org.yamcs.protobuf.FileTransferOption;
import org.yamcs.protobuf.ListFilesResponse;
import org.yamcs.protobuf.TransferState;
import org.yamcs.protobuf.TransferDirection;
import org.yamcs.security.Directory;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.OngoingS13Transfer.FaultHandlingAction;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.S13FileTransfer.PredefinedTransferTypes;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.packets.DownlinkS13Packet;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.packets.DownlinkS13Packet.PacketType;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.requests.CancelRequest;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.requests.FilePutRequest;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.requests.PauseRequest;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.requests.ResumeRequest;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.Sequence;
import org.yamcs.yarch.SqlBuilder;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.streamsql.StreamSqlException;
import org.yamcs.yarch.streamsql.StreamSqlResult;


public class ServiceThirteen extends AbstractFileTransferService implements StreamSubscriber, TransferMonitor {
    static final String ETYPE_UNEXPECTED_S13_PACKET = "UNEXPECTED_S13_PACKET";
    static final String ETYPE_TRANSFER_STARTED = "TRANSFER_STARTED";
    static final String ETYPE_TRANSFER_FINISHED = "TRANSFER_FINISHED";
    static final String ETYPE_TRANSFER_SUSPENDED = "TRANSFER_SUSPENDED";
    static final String ETYPE_TRANSFER_RESUMED = "TRANSFER_RESUMED";
    static final String ETYPE_TRANSFER_PACKET_ERROR = "TRANSFER_PACKET_ERROR";
    static final String ETYPE_TRANSFER_PACKET_ERROR_NOK = "TRANSFER_PACKET_ERROR_NOK";

    static final String BUCKET_OPT = "bucket";
    static final String TABLE_NAME = "s13";
    static final String SEQUENCE_NAME = "s13";

    private Stream dbStream;
    private Sequence transferInstanceId;

    static final Map<String, ConditionCode> VALID_CODES = new HashMap<>();
    static {
        VALID_CODES.put("CancelRequestReceived", ConditionCode.CANCEL_REQUEST_RECEIVED);
        VALID_CODES.put("InactivityDetected", ConditionCode.INACTIVITY_DETECTED);
        VALID_CODES.put("PreparedCommandNotFormed", ConditionCode.UNSUPPORTED_CHECKSUM_TYPE);
        VALID_CODES.put("RunOutOfRetry", ConditionCode.NAK_LIMIT_REACHED);
        VALID_CODES.put("OnboardTimeout", ConditionCode.ONBOARD_TIMEOUT);
        VALID_CODES.put("OnboardDiscontinuity", ConditionCode.ONBOARD_DISCONTINUITY);
        VALID_CODES.put("OnboardMemoryError", ConditionCode.ONBOARD_MEMORY_ERROR);
    }

    Map<S13TransactionId.S13UniqueId, OngoingS13Transfer> pendingTransfers = new ConcurrentHashMap<>();
    FileDownloadRequests fileDownloadRequests = new FileDownloadRequests();

    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(6);
    Map<ConditionCode, FaultHandlingAction> receiverFaultHandlers;
    Map<ConditionCode, FaultHandlingAction> senderFaultHandlers;

    Stream s13In;
    Stream cmdhistRealtime;

    Bucket defaultIncomingBucket;
    EventProducer eventProducer;

    static String spaceSystem;
    static Map<Long, String> spaceSubSystems = new HashMap<>();
    static Map<Long, String> contentTypeMap = new HashMap<>();

    private final Set<TransferMonitor> transferListeners = new CopyOnWriteArraySet<>();
    
    protected Map<String, EntityConf> localEntities = new LinkedHashMap<>();
    protected Map<String, EntityConf> remoteEntities = new LinkedHashMap<>();

    private int maxExistingFileRenames;
    boolean allowConcurrentFileOverwrites;

    private boolean canChangePacketDelay;
    private List<Integer> packetDelayPredefinedValues;
    private boolean canChangeNumberOfRetries;
    private List<Integer> filePartRetriesPredefinedValues;

    private static Processor processor;
    private static Directory directory;
    private static CommandingManager commandingManager;

    public static String commandReleaseUser;

    private final String PDU_DELAY_OPTION = "pduDelay";
    private final String FILE_PART_RETRY_OPTION = "filePartRetryNumber";

    @Override
    public Spec getSpec() {
        Spec entitySpec = new Spec();
        entitySpec.addOption("name", OptionType.STRING);
        entitySpec.addOption("id", OptionType.INTEGER);
        entitySpec.addOption(BUCKET_OPT, OptionType.STRING).withDefault(null);
        entitySpec.addOption("subSystem", OptionType.STRING);
        entitySpec.addOption("contentType", OptionType.STRING);

        Spec spec = new Spec();
        spec.addOption("inStream", OptionType.STRING).withDefault("cfdp_in");
        spec.addOption("outStream", OptionType.STRING).withDefault("cfdp_out");
        spec.addOption("incomingBucket", OptionType.STRING).withDefault("cfdpDown");
        spec.addOption("maxExistingFileRenames", OptionType.INTEGER).withDefault(1000);
        spec.addOption("localEntities", OptionType.LIST).withElementType(OptionType.MAP).withSpec(entitySpec);
        spec.addOption("remoteEntities", OptionType.LIST).withElementType(OptionType.MAP).withSpec(entitySpec);
        spec.addOption("receiverFaultHandlers", OptionType.MAP).withSpec(Spec.ANY);
        spec.addOption("senderFaultHandlers", OptionType.MAP).withSpec(Spec.ANY);
        spec.addOption("allowConcurrentFileOverwrites", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("spaceSystem", OptionType.STRING).withDefault("FF");
        spec.addOption("commandReleaseUser", OptionType.STRING).withDefault("administrator");

        spec.addOption("checkAckTimeout", OptionType.INTEGER).withDefault(10000l);
        spec.addOption("checkAckLimit", OptionType.INTEGER).withDefault(5);
        spec.addOption("inactivityTimeout", OptionType.INTEGER).withDefault(10000);

        spec.addOption("maxPacketSize", OptionType.INTEGER).withDefault(512);
        spec.addOption("firstPacketCmdName", OptionType.STRING).withDefault("FirstUplinkPart");
        spec.addOption("intermediatePacketCmdName", OptionType.STRING).withDefault("IntermediateUplinkPart");
        spec.addOption("lastPacketCmdName", OptionType.STRING).withDefault("LastUplinkPart");
        spec.addOption("skipAcknowledgement", OptionType.BOOLEAN).withDefault(true);
        spec.addOption("cmdhistStream", OptionType.STRING).withDefault("cmdhist_realtime");
        
        spec.addOption("canChangePacketDelay", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("packetDelayPredefinedValues", OptionType.LIST).withDefault(Collections.emptyList())
                .withElementType(OptionType.INTEGER);
        spec.addOption("sleepBetweenPackets", OptionType.INTEGER).withDefault(500);


        spec.addOption("canChangeNumberOfRetries", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("filePartRetriesPredefinedValues", OptionType.LIST).withDefault(Collections.emptyList())
                .withElementType(OptionType.INTEGER);
        spec.addOption("filePartRetries", OptionType.INTEGER).withDefault(1);

        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);

        String inStream = config.getString("inStream");

        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        s13In = ydb.getStream(inStream);
        if (s13In == null) {
            throw new ConfigurationException("cannot find stream " + inStream);
        }
        String cmdhistStream = config.getString("cmdhistStream", "cmdhist_realtime");
        cmdhistRealtime = ydb.getStream(cmdhistStream);
        if (cmdhistRealtime == null) {
            throw new ConfigurationException("cannot find stream " + cmdhistStream);
        }

        defaultIncomingBucket = getBucket(config.getString("incomingBucket"), true);
        allowConcurrentFileOverwrites = config.getBoolean("allowConcurrentFileOverwrites");
        spaceSystem = config.getString("spaceSystem");
        maxExistingFileRenames = config.getInt("maxExistingFileRenames", 1000);
        commandReleaseUser = config.getString("commandReleaseUser", "admin");

        canChangePacketDelay = config.getBoolean("canChangePacketDelay");
        packetDelayPredefinedValues = config.getList("packetDelayPredefinedValues");

        canChangeNumberOfRetries = config.getBoolean("canChangeNumberOfRetries");
        filePartRetriesPredefinedValues = config.getList("filePartRetriesPredefinedValues");

        initSrcDst(config);
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, "PusService-13", 10000);
        transferInstanceId = ydb.getSequence(SEQUENCE_NAME, true);
        if (config.containsKey("senderFaultHandlers")) {
            senderFaultHandlers = readFaultHandlers(config.getMap("senderFaultHandlers"));
        } else {
            senderFaultHandlers = Collections.emptyMap();
        }

        if (config.containsKey("receiverFaultHandlers")) {
            receiverFaultHandlers = readFaultHandlers(config.getMap("receiverFaultHandlers"));
        } else {
            receiverFaultHandlers = Collections.emptyMap();
        }
        setupRecording(ydb);

        processor = YamcsServer.getServer().getInstance(yamcsInstance).getProcessor("realtime");
        directory = YamcsServer.getServer().getSecurityStore().getDirectory();
        commandingManager = processor.getCommandingManager();
    }

    public static Processor getProcessor() {
        return processor;
    }

    public static Directory getUserDirectory() {
        return directory;
    }

    public static CommandingManager getCommandingManager() {
        return commandingManager;
    }

    private Map<ConditionCode, FaultHandlingAction> readFaultHandlers(Map<String, String> map) {
        Map<ConditionCode, FaultHandlingAction> m = new EnumMap<>(ConditionCode.class);
        for (Map.Entry<String, String> me : map.entrySet()) {
            ConditionCode code = VALID_CODES.get(me.getKey());
            if (code == null) {
                throw new ConfigurationException(
                        "Unknown condition code " + me.getKey() + ". Valid codes: " + VALID_CODES.keySet());
            }
            FaultHandlingAction action = FaultHandlingAction.fromString(me.getValue());
            if (action == null) {
                throw new ConfigurationException(
                        "Unknown action " + me.getValue() + ". Valid actions: " + FaultHandlingAction.actions());
            }
            m.put(code, action);
        }
        return m;
    }

    private void setupRecording(YarchDatabaseInstance ydb) throws InitException {
        try {
            if (ydb.getTable(TABLE_NAME) == null) {
                String query = "create table " + TABLE_NAME + "(" + TDEF.getStringDefinition1()
                        + ", primary key(id, serverId))";
                ydb.execute(query);
            }
            String streamName = TABLE_NAME + "table_in";
            if (ydb.getStream(streamName) == null) {
                ydb.execute("create stream " + streamName + TDEF.getStringDefinition());
            }
            ydb.execute("upsert_append into " + TABLE_NAME + " select * from " + streamName);
            dbStream = ydb.getStream(streamName);
        } catch (ParseException | StreamSqlException e) {
            throw new InitException(e);
        }
    }

    private void initSrcDst(YConfiguration config) throws InitException {
        if (config.containsKey("localEntities")) {
            for (YConfiguration c : config.getConfigList("localEntities")) {
                long id = c.getLong("id");
                String name = c.getString("name");
                if (localEntities.containsKey(name)) {
                    throw new ConfigurationException("Duplicate local entity '" + name + "'.");
                }
                Bucket bucket = null;
                if (c.containsKey(BUCKET_OPT)) {
                    bucket = getBucket(c.getString(BUCKET_OPT), c.getBoolean("global", true));
                }
                EntityConf ent = new EntityConf(id, name, bucket);
                localEntities.put(name, ent);
            }
        }

        if (config.containsKey("remoteEntities")) {
            for (YConfiguration c : config.getConfigList("remoteEntities")) {
                long id = c.getLong("id");
                
                String subSystem = c.getString("subSystem");
                spaceSubSystems.put(id, subSystem);

                String contentType = c.getString("contentType");
                contentTypeMap.put(id, contentType);

                String name = c.getString("name");
                if (remoteEntities.containsKey(name)) {
                    throw new ConfigurationException("Duplicate remote entity '" + name + "'.");
                }
                Bucket bucket = null;
                if (c.containsKey(BUCKET_OPT)) {
                    bucket = getBucket(c.getString(BUCKET_OPT), c.getBoolean("global", true));
                }
                EntityConf ent = new EntityConf(id, name, bucket);
                remoteEntities.put(name, ent);
            }
        }

        if (localEntities.isEmpty()) {
            throw new ConfigurationException("No local entity specified");
        }
        if (remoteEntities.isEmpty()) {
            throw new ConfigurationException("No remote entity specified");
        }
    }

    private Bucket getBucket(String bucketName, boolean global) throws InitException {
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
    
    public static String getCmdSubsystem(long remoteEntityId) {
        return "/" + spaceSystem + "/" + spaceSubSystems.get(remoteEntityId);

    }

    public static String constructFullyQualifiedCmdName(String cmdName, long remoteEntityId) {
        return ServiceThirteen.getCmdSubsystem(remoteEntityId) + "/" + cmdName;
    }

    @Override
    public FileTransfer getFileTransfer(long id) {
        Optional<OngoingS13Transfer> r = pendingTransfers.values().stream().filter(c -> c.getId() == id).findAny();
        if (r.isPresent()) {
            return r.get();

        } else {
            return searchInArchive(id);
        }
    }

    public FileTransfer getOngoingUploadFileTransfer(long largePacketTransactionId) {
        Optional<OngoingS13Transfer> r = pendingTransfers.values().stream()
                .filter(c -> c.getTransactionId().getLargePacketTransactionId() == largePacketTransactionId)
                .filter(c -> c.getTransactionId().getTransferDirection() == TransferDirection.UPLOAD).findAny();

        return r.orElse(null);

    }

    public FileTransfer getOngoingDownloadFileTransfer(long largePacketTransactionId) {
        Optional<OngoingS13Transfer> r = pendingTransfers.values().stream()
                .filter(c -> c.getTransactionId().getLargePacketTransactionId() == largePacketTransactionId)
                .filter(c -> c.getTransactionId().getTransferDirection() == TransferDirection.DOWNLOAD).findAny();

        return r.orElse(null);
    }

    private FileTransfer searchInArchive(long id) {
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        try {
            StreamSqlResult res = ydb.execute("select * from " + TABLE_NAME + " where id=?", id);
            FileTransfer r = null;
            if (res.hasNext()) {
                r = new CompletedTransfer(res.next());
            }
            res.close();
            return r;

        } catch (Exception e) {
            log.error("Error executing query", e);
            return null;
        }
    }

    private S13FileTransfer processPutRequest(long transferInstanceId, long largePacketTransactionId, long creationTime, 
            FilePutRequest request, 
            Bucket bucket, String transferType, Integer filePartRetries, Integer customPacketDelay, String username) {
        S13OutgoingTransfer transfer = new S13OutgoingTransfer(yamcsInstance, transferInstanceId, largePacketTransactionId, creationTime,
                executor, cmdhistRealtime, request, config, bucket, null, customPacketDelay, eventProducer, this, transferType, senderFaultHandlers, filePartRetries, username);

        dbStream.emitTuple(CompletedTransfer.toInitialTuple(transfer));

        stateChanged(transfer);
        pendingTransfers.put(transfer.getTransactionId().getUniquenessId(), transfer);

        eventProducer.sendInfo(ETYPE_TRANSFER_STARTED,
            "Starting new S13 upload TXID[" + transfer.getTransactionId() + "] " + transfer.getObjectName()
                    + " -> " + transfer.getRemotePath());

        transfer.start();
        return transfer;
    }

    static boolean isRunning(OngoingS13Transfer trsf) {
        return trsf.state == TransferState.RUNNING || trsf.state == TransferState.PAUSED
                || trsf.state == TransferState.CANCELLING;
    }

    private void processPauseRequest(PauseRequest request) {
        OngoingS13Transfer transfer = request.getTransfer();
        transfer.pauseTransfer();
    }

    private void processResumeRequest(ResumeRequest request) {
        OngoingS13Transfer transfer = request.getTransfer();
        transfer.resumeTransfer();
    }

    private void processCancelRequest(CancelRequest request) {
        OngoingS13Transfer transfer = request.getTransfer();
        transfer.cancelTransfer();
    }

    @Override
    public void onTuple(Stream stream, Tuple tuple) {
        DownlinkS13Packet packet = DownlinkS13Packet.fromTuple(tuple);

        // Check if it is an Uplink abortion packet
        if (packet.getPacketType() == PacketType.ABORTION) {
            FileTransfer filetransfer = getOngoingUploadFileTransfer(packet.getUniquenessId().getLargePacketTransactionId());
            if (filetransfer != null) {
                S13OutgoingTransfer outgoingTransfer = (S13OutgoingTransfer) filetransfer;
                ConditionCode failureCode = packet.getFailureCode() != null? 
                        ConditionCode.reaConditionCodeAsIs((byte) packet.getFailureCode().byteValue()): ConditionCode.RESERVED; 
                outgoingTransfer.cancel(failureCode);

            } else {
                String errorMsg = "Erroneous Uplink abortion request received | " + packet;
                eventProducer.sendWarning(ETYPE_UNEXPECTED_S13_PACKET, "TXID[S13-UNKNOWN] " + errorMsg);
                log.warn("Erroneous Uplink abortion request received | " + packet);
            }

            return;
        }
    }

    @SuppressWarnings("unused")
    private OngoingS13Transfer instantiateIncomingTransaction(DownlinkS13Packet packet) {
        S13TransactionId.S13UniqueId uniquenessId = packet.getUniquenessId();
        S13TransactionId txId = new S13TransactionId(uniquenessId.getLargePacketTransactionId(), transferInstanceId.next(), uniquenessId.getLargePacketTransactionId(), uniquenessId.getTransferDirection());

        EntityConf remoteEntity = getRemoteEntity(txId.getLargePacketTransactionId());
        if (remoteEntity == null) {
            eventProducer.sendWarning(ETYPE_UNEXPECTED_S13_PACKET,
                    "Received a transaction start for an unknown remote entity Id " + txId.getLargePacketTransactionId());
            return null;
        }

        eventProducer.sendInfo(ETYPE_TRANSFER_STARTED,
                "Starting new S13 downlink TXID[" + txId + "] | from: " + remoteEntity);

        Bucket bucket = defaultIncomingBucket;

        if (remoteEntity.getBucket() != null) {
            bucket = remoteEntity.getBucket();
        }

        long creationTime = YamcsServer.getTimeService(yamcsInstance).getMissionTime();
        final FileSaveHandler fileSaveHandler = new FileSaveHandler(yamcsInstance, bucket, fileDownloadRequests,
                false, false, false, maxExistingFileRenames);

        return new S13IncomingTransfer(yamcsInstance, remoteEntity.getId(), creationTime, executor, config,
                txId, remoteEntity.getName(), fileSaveHandler, eventProducer, this, PredefinedTransferTypes.DOWNLOAD_LARGE_FILE_TRANSFER.toString(),
                contentTypeMap.get(remoteEntity.getId()), receiverFaultHandlers);
    }

    public EntityConf getRemoteEntity(long entityId) {
        return remoteEntities.values()
                .stream()
                .filter(entityConf -> entityConf.getId() == entityId)
                .findAny()
                .orElse(null);
    }

    public EntityConf getLocalEntity(long entityId) {
        return localEntities.values()
                .stream()
                .filter(entityConf -> entityConf.getId() == entityId)
                .findAny()
                .orElse(null);
    }

    @Override
    public void registerTransferMonitor(TransferMonitor listener) {
        transferListeners.add(listener);
    }

    @Override
    public void unregisterTransferMonitor(TransferMonitor listener) {
        transferListeners.remove(listener);
    }

    @Override
    public void registerRemoteFileListMonitor(RemoteFileListMonitor monitor) {
        return;
    }

    @Override
    public void unregisterRemoteFileListMonitor(RemoteFileListMonitor monitor) {
        return;
    }

    @Override
    public void notifyRemoteFileListMonitors(ListFilesResponse listFilesResponse) {
        return;
    }

    @Override
    public Set<RemoteFileListMonitor> getRemoteFileListMonitors() {
        return null;
    }

    @Override
    public void stateChanged(FileTransfer ft) {
        S13FileTransfer s13FileTransfer = (S13FileTransfer) ft;
        dbStream.emitTuple(CompletedTransfer.toUpdateTuple(s13FileTransfer));

        // Notify downstream listeners
        transferListeners.forEach(l -> l.stateChanged(s13FileTransfer));

        if (s13FileTransfer.getTransferState() == TransferState.COMPLETED
                || s13FileTransfer.getTransferState() == TransferState.FAILED)
            pendingTransfers.remove(s13FileTransfer.getTransactionId().getUniquenessId());
    }

    @Override
    public List<FileTransfer> getTransfers(FileTransferFilter filter) {
        List<FileTransfer> toReturn = new ArrayList<>();
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);

        pendingTransfers.values().stream()
                .filter(ServiceThirteen::isRunning)
                .forEach(toReturn::add);

        toReturn.removeIf(transfer -> {
            if (filter.start != TimeEncoding.INVALID_INSTANT) {
                if (transfer.getCreationTime() < filter.start) {
                    return true;
                }
            }
            if (filter.stop != TimeEncoding.INVALID_INSTANT) {
                if (transfer.getCreationTime() >= filter.stop) {
                    return true;
                }
            }
            if (!filter.states.isEmpty() && !filter.states.contains(transfer.getTransferState())) {
                return true;
            }
            if (filter.direction != null && !Objects.equals(filter.direction, transfer.getDirection())) {
                return true;
            }
            if (filter.localEntityId != null && !Objects.equals(filter.localEntityId, transfer.getLocalEntityId())) {
                return true;
            }
            if (filter.remoteEntityId != null && !Objects.equals(filter.remoteEntityId, transfer.getRemoteEntityId())) {
                return true;
            }

            return false;
        });

        if (toReturn.size() >= filter.limit) {
            return toReturn;
        }

        // Query only for COMPLETED or FAILED, while respecting the incoming requested states
        // (want to avoid duplicates with the in-memory data structure)
        if (filter.states.isEmpty() || filter.states.contains(TransferState.COMPLETED)
                || filter.states.contains(TransferState.FAILED)) {

            var sqlb = new SqlBuilder(TABLE_NAME);

            if (filter.start != TimeEncoding.INVALID_INSTANT) {
                sqlb.whereColAfterOrEqual(COL_CREATION_TIME, filter.start);
            }
            if (filter.stop != TimeEncoding.INVALID_INSTANT) {
                sqlb.whereColBefore(COL_CREATION_TIME, filter.stop);
            }

            if (filter.states.isEmpty()) {
                sqlb.whereColIn(COL_TRANSFER_STATE,
                        Arrays.asList(TransferState.COMPLETED.name(), TransferState.FAILED.name()));
            } else {
                var queryStates = new ArrayList<>(filter.states);
                queryStates.removeIf(state -> {
                    return state != TransferState.COMPLETED && state != TransferState.FAILED;
                });

                var stringStates = queryStates.stream().map(TransferState::name).toList();
                sqlb.whereColIn(COL_TRANSFER_STATE, stringStates);

            }
            if (filter.direction != null) {
                sqlb.where(COL_DIRECTION + " = ?", filter.direction.name());
            }
            if (filter.localEntityId != null) {
                // The 1=1 clause is a trick because Yarch is being difficult about multiple lparens
                sqlb.where("""
                        (1=1 and
                          (direction = 'UPLOAD' and sourceId = ?) or
                          (direction = 'DOWNLOAD' and destinationId = ?)
                        )
                        """, filter.localEntityId, filter.localEntityId);
            }
            if (filter.remoteEntityId != null) {
                // The 1=1 clause is a trick because Yarch is being difficult about multiple lparens
                sqlb.where("""
                        (1=1 and
                          (direction = 'UPLOAD' and destinationId = ?) or
                          (direction = 'DOWNLOAD' and sourceId = ?)
                        )
                        """, filter.remoteEntityId, filter.remoteEntityId);
            }

            sqlb.descend(filter.descending);
            sqlb.limit(filter.limit - toReturn.size());

            try {
                var res = ydb.execute(sqlb.toString(), sqlb.getQueryArgumentsArray());
                while (res.hasNext()) {
                    Tuple t = res.next();
                    toReturn.add(new CompletedTransfer(t));
                }
                res.close();
            } catch (ParseException | StreamSqlException e) {
                log.error("Error executing query", e);
            }
        }

        Collections.sort(toReturn, (a, b) -> {
            var rc = Long.compare(a.getCreationTime(), b.getCreationTime());
            return filter.descending ? -rc : rc;
        });
        return toReturn;
    }

    @Override
    public List<EntityInfo> getLocalEntities() {
        return localEntities.values().stream()
                .map(c -> EntityInfo.newBuilder().setName(c.getName()).setId(c.getId()).build())
                .collect(Collectors.toList());
    }

    @Override
    public List<EntityInfo> getRemoteEntities() {
        return remoteEntities.values().stream()
                .map(c -> EntityInfo.newBuilder().setName(c.getName()).setId(c.getId()).build())
                .collect(Collectors.toList());
    }

    @Override
    public List<FileTransferOption> getFileTransferOptions() {
        var options = new ArrayList<FileTransferOption>();

        if (canChangePacketDelay) {
            options.add(FileTransferOption.newBuilder()
                    .setName(PDU_DELAY_OPTION)
                    .setType(FileTransferOption.Type.DOUBLE)
                    .setTitle("Packet delay")
                    .setDefault(Integer.toString(config.getInt("sleepBetweenPackets")))
                    .addAllValues(packetDelayPredefinedValues.stream()
                            .map(value -> FileTransferOption.Value.newBuilder().setValue(value.toString()).build())
                            .collect(Collectors.toList()))
                    .setAllowCustomOption(true)
                    .build());
        }

        if (canChangeNumberOfRetries) {
            options.add(FileTransferOption.newBuilder()
                    .setName(FILE_PART_RETRY_OPTION)
                    .setType(FileTransferOption.Type.DOUBLE)
                    .setTitle("File Part Retries")
                    .setDefault(Integer.toString(config.getInt("filePartRetries")))
                    .addAllValues(filePartRetriesPredefinedValues.stream()
                            .map(value -> FileTransferOption.Value.newBuilder().setValue(value.toString()).build())
                            .collect(Collectors.toList()))
                    .setAllowCustomOption(true)
                    .build());
        }

        return options;
    }

    private static class OptionValues {
        HashMap<String, String> stringOptions = new HashMap<>();
        HashMap<String, Double> doubleOptions = new HashMap<>();
    }

    private OptionValues getOptionValues(Map<String, Object> extraOptions) {
        var optionValues = new OptionValues();

        for (Map.Entry<String, Object> option : extraOptions.entrySet()) {
            try {
                switch (option.getKey()) {
                case PDU_DELAY_OPTION:
                case FILE_PART_RETRY_OPTION:
                    optionValues.doubleOptions.put(option.getKey(), (double) option.getValue());
                    break;
                case "TRANSFER_USER":
                    optionValues.stringOptions.put(option.getKey(), (String) option.getValue());
                default:
                    log.warn("Unknown file transfer option: {} (value: {})", option.getKey(), option.getValue());
                }
            } catch (ClassCastException e) {
                log.warn("Failed to cast option '{}' to its correct type (value: {})", option.getKey(),
                        option.getValue());
            }
        }

        return optionValues;
    }

    @Override
    protected void addCapabilities(FileTransferCapabilities.Builder builder) {
        builder.setDownload(false)
                .setPauseResume(true)
                .setUpload(true)
                .setRemotePath(false)
                .setFileList(false)
                .setHasTransferType(true)
                .setFileProxyOperations(false);
    }

    private String getAbsoluteDestinationPath(String destinationPath, String localObjectName) {
        if (localObjectName == null) {
            throw new NullPointerException("local object name cannot be null");
        }
        if (destinationPath == null) {
            return localObjectName;
        }
        return destinationPath;
    }

    @Override
    public FileTransfer startUpload(String source, Bucket bucket, String objectName, String destination,
            String destinationPath, TransferOptions options) throws IOException {
        byte[] objData;
        objData = bucket.getObject(objectName);

        if (objData == null) {
            throw new InvalidRequestException("No object named '" + objectName + "' in bucket " + bucket.getName());
        }

        long sourceId = getEntityFromName(source, localEntities).getId();
        long destinationId = getEntityFromName(destination, remoteEntities).getId();

        String absoluteDestinationPath = getAbsoluteDestinationPath(destinationPath, objectName);
        if (!allowConcurrentFileOverwrites) {
            if (pendingTransfers.values().stream()
                    .filter(ServiceThirteen::isRunning)
                    .anyMatch(trsf -> getEntityFromId(trsf.getTransactionId()
                            .getLargePacketTransactionId(), remoteEntities).getId() == destinationId)) {
                throw new InvalidRequestException(
                        "There is already a transfer ongoing to LargePacketId: '" + getEntityFromName(destination, remoteEntities).getId() + " - " + getEntityFromName(
                                destination, remoteEntities).getName() + "'");
            }
        }

        OptionValues optionValues = getOptionValues(options.getExtraOptions());
        Double filePartRetries = optionValues.doubleOptions.get(FILE_PART_RETRY_OPTION);
        Double packetDelay = optionValues.doubleOptions.get(PDU_DELAY_OPTION);

        String username = optionValues.stringOptions.get("TRANSFER_USER");

        FilePutRequest request = new FilePutRequest(sourceId, destinationId, objectName, absoluteDestinationPath, bucket, objData);
        long creationTime = YamcsServer.getTimeService(yamcsInstance).getMissionTime();

        return processPutRequest(transferInstanceId.next(), destinationId, creationTime, request, bucket, 
                PredefinedTransferTypes.UPLOAD_LARGE_FILE_TRANSFER.toString(),
                filePartRetries != null? filePartRetries.intValue(): null, packetDelay != null? packetDelay.intValue(): null, username);
    }

    @Override
    public FileTransfer startDownload(String sourceEntity, String sourcePath, String destinationEntity, Bucket bucket,
            String objectName, TransferOptions options) throws IOException, InvalidRequestException {
        throw new InvalidRequestException("Downloading is not enabled on this S13 service");
    }

    @Override
    public ListFilesResponse getFileList(String source, String destination, String remotePath, Map<String, Object> options) {
        throw new InvalidRequestException("File listing is not enabled on this S13 service");
    }

    protected static EntityConf getEntityFromId(long entityId, Map<String, EntityConf> entities) {
        for(Map.Entry<String, EntityConf> entityMap: entities.entrySet()) {
            EntityConf entity = entityMap.getValue();

            if (entity.getId() == entityId)
                return entity;
        }
        return null;
    }

    protected EntityConf getEntityFromName(String entityName, Map<String, EntityConf> entities) {
        if (entityName == null || entityName.isBlank()) {
            return entities.values().iterator().next();
        } else {
            if (!entities.containsKey(entityName)) {
                throw new InvalidRequestException(
                        "Invalid entity '" + entityName + "' (should be one of " + entities + "");
            }
            return entities.get(entityName);
        }
    }

    @Override
    public void fetchFileList(String source, String destination, String remotePath, Map<String, Object> options) {
        throw new InvalidRequestException("File listing is not enabled on this S13 service");
    }

    @Override
    public void saveFileList(ListFilesResponse listFilesResponse) {
        return;
    }


    @Override
    public void pause(FileTransfer transfer) {
        processPauseRequest(new PauseRequest(transfer));
    }

    @Override
    public void resume(FileTransfer transfer) {
        processResumeRequest(new ResumeRequest(transfer));
    } 

    @Override
    public void cancel(FileTransfer transfer) {
        if (transfer instanceof OngoingS13Transfer) {
            processCancelRequest(new CancelRequest(transfer));
        } else {
            throw new InvalidRequestException("Unknown transfer type " + transfer);
        }
    }

    @Override
    protected void doStart() {
        s13In.addSubscriber(this);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        for (OngoingS13Transfer trsf : pendingTransfers.values()) {
            if (trsf.state == TransferState.RUNNING || trsf.state == TransferState.PAUSED) {
                trsf.failTransfer("service shutdown");
            }
        }
        executor.shutdown();
        s13In.removeSubscriber(this);
        notifyStopped();
    }

    @Override
    public void streamClosed(Stream stream) {
        if (isRunning()) {
            log.debug("Stream {} closed", stream.getName());
            notifyFailed(new Exception("Stream " + stream.getName() + " closed"));
        }
    }

    @Override
    public FileProxyOperationOption getFileProxyOperationOptions() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getFileProxyOperationOptions'");
    }
}
