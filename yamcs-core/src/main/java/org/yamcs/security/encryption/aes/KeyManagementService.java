package org.yamcs.security.encryption.aes;

import java.lang.RuntimeException;

import org.yamcs.AbstractYamcsService;
import org.yamcs.YConfiguration;
import org.yamcs.logging.Log;
import org.yamcs.InitException;
import org.yamcs.Spec.OptionType;
import org.yamcs.Spec;
import org.yamcs.security.encryption.SymmetricEncryption;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.yarch.*;
import org.yamcs.yarch.streamsql.StreamSqlException;
import org.yamcs.yarch.streamsql.StreamSqlResult;

import java.util.Optional;

public class KeyManagementService extends AbstractYamcsService implements StreamSubscriber {
    private static final Log log = new Log(SymmetricEncryption.class);

    public static final String TABLE_NAME = "activekeys";
    public static final String STREAM_NAME = "active_keys";

    public static final TupleDefinition ACTIVE_KEY_TUPLE_DEFINITION = new TupleDefinition();
    static {
        ACTIVE_KEY_TUPLE_DEFINITION.addColumn("inserttime", DataType.TIMESTAMP);
        ACTIVE_KEY_TUPLE_DEFINITION.addColumn("keyid", DataType.STRING);
        ACTIVE_KEY_TUPLE_DEFINITION.addColumn("family", DataType.ENUM);
    }

    protected String yamcsInstance;

    private String tcKey;
    private String tmKey;
    private String payKey;

    String spacecraftId;
    Stream stream;

    private String tmKeyId;
    private String tcKeyId;
    private String payKeyId;

    KeyParser parser;
    VaultClient client;
    YarchDatabaseInstance ydb;

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("vaultToken", OptionType.STRING);
        spec.addOption("vaultNamespace", OptionType.STRING);
        spec.addOption("vaultAddress", OptionType.STRING);
        spec.addOption("cipherText", OptionType.STRING);
        spec.addOption("spacecraftIdSrs", OptionType.STRING);
        spec.addOption("defaultTmKeyId", OptionType.STRING);
        spec.addOption("defaultTcKeyId", OptionType.STRING);
        spec.addOption("defaultPayKeyId", OptionType.STRING);
        spec.addOption("stream", OptionType.STRING).withDefault("active_key");
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);

        this.yamcsInstance = yamcsInstance;
        this.client = new VaultClient(config.getString("vaultToken"), config.getString("vaultNamespace"), config.getString("vaultAddress"));
        this.spacecraftId = config.getString("spacecraftIdSrs");

        String decryptedData = null;
        try {
            decryptedData = client.decrypt(config.getString("cipherText"));
            this.parser = new KeyParser();
            parser.parse(decryptedData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ydb = YarchDatabase.getInstance(yamcsInstance);
        try {
            if (ydb.getTable(TABLE_NAME) == null) {
                String query = "create table " + TABLE_NAME + "(" + ACTIVE_KEY_TUPLE_DEFINITION.getStringDefinition1()
                        + ", primary key(inserttime, family)) ";
                ydb.execute(query);
            }
            ydb.execute("create stream " + STREAM_NAME + ACTIVE_KEY_TUPLE_DEFINITION.getStringDefinition());
        } catch (ParseException | StreamSqlException e) {
            throw new InitException(e);
        }
        stream = ydb.getStream(STREAM_NAME);
    }

    /**
     * Fetches the key ID for a given family from the database.
     *
     * @param ydb       the database instance
     * @param tableName the table name
     * @param family    the family type
     * @return an Optional containing the key ID if found, or empty if not
     * @throws StreamSqlException if a database error occurs
     */
    private Optional<Integer> fetchKeyId(String tableName, String family) throws StreamSqlException, ParseException {
        StreamSqlResult result = null;
        try {
            String query = String.format("select keyid from %s where family='%s' order desc limit 1", tableName, family);
            result = ydb.execute(query);
            if (result.hasNext()) {
                return Optional.ofNullable(result.next().getColumn("keyid"));
            }

        } finally {
            if (result != null) {
                result.close(); // Manually close the resource
            }
        }
        return Optional.empty();
    }

    @Override
    protected void doStart() {
        stream.addSubscriber(this);
        try {
            Optional<Integer> tmKeyId = fetchKeyId(TABLE_NAME, "tm");
            Optional<Integer> tcKeyId = fetchKeyId(TABLE_NAME, "tc");
            Optional<Integer> payKeyId = fetchKeyId(TABLE_NAME, "pay");
            // Process TM Key
            this.tmKeyId = tmKeyId
                    .map(String::valueOf)
                    .orElseGet(() -> config.getString("defaultTmKeyId"));
            setDefaultTmKey(this.tmKeyId);

            // Process TC Key
            this.tcKeyId = tcKeyId
                    .map(String::valueOf)
                    .orElseGet(() -> config.getString("defaultTcKeyId"));
            setDefaultTcKey(this.tcKeyId);

            this.payKeyId = payKeyId
                    .map(String::valueOf)
                    .orElseGet(() -> config.getString("defaultPayKeyId"));
            setDefaultPayloadKey(this.payKeyId);
        
        } catch (StreamSqlException | ParseException e) {
            throw new RuntimeException(e);
        }

        notifyStarted();
    }

    // Key GET | Careful with this
    public String getTcKey() {
        return this.tcKey;
    }

    public String getTmKey() {
        return this.tmKey;
    }

    public String getPayloadKey(){
        return this.payKey;
    }

    // Key ID GET
    public String getTcKeyId() {
        return this.tcKeyId;
    }

    public String getTmKeyId() {
        return this.tmKeyId;
    }

    public String getPayloadKeyId(){
        return this.payKeyId;
    }

    public void setTcKeyId(String tcKeyId) {
        this.tcKeyId = tcKeyId;
        this.tcKey = this.parser.getKeySections().get("TC Keys").get(config.getString("spacecraftIdSrs")+"_TC_"+tcKeyId).getKey();
    }

    public void setTmKeyId(String tmKeyId) {
        this.tmKeyId = tmKeyId;
        this.tmKey = this.parser.getKeySections().get("TM Keys").get(config.getString("spacecraftIdSrs")+"_TM_"+tmKeyId).getKey();
    }

    public void setPayloadKeyId(String payloadKeyId){
        this.payKeyId = payloadKeyId;
    }

    public Stream getStream(){
        return stream;
    }

    private void setDefaultTcKey(String keyId) {
        log.debug("New TC KEY ID: {}", keyId);
        this.tcKey = this.parser.getKeySections()
                .get("TC Keys")
                .get(config.getString("spacecraftIdSrs") + "_TC_" + keyId)
                .getKey();
    }

    private void setDefaultTmKey(String keyId) {
        log.debug("New TM KEY ID: {}", keyId);
        this.tmKey = this.parser.getKeySections()
                .get("TM Keys")
                .get(config.getString("spacecraftIdSrs") + "_TM_" + keyId)
                .getKey();
    }

    private void setDefaultPayloadKey(String keyId) {
        log.debug("New PAY KEY ID: {}", keyId);
        this.payKey = this.parser.getKeySections()
                .get("PAY Keys")
                .get(config.getString("spacecraftIdSrs") + "_PAY_" + keyId)
                .getKey();
    }

    @Override
    protected void doStop() {
        notifyStopped();
    }

    @Override
    public String getYamcsInstance() {
        return yamcsInstance;
    }

    @Override
    public void onTuple(Stream stream, Tuple tuple) {

        try {
            long inserttime = (Long) tuple.getColumn("inserttime");
            String keyId = (String) tuple.getColumn("keyid");
            String family = (String) tuple.getColumn("family");

            ydb.execute("insert into " + TABLE_NAME + "(inserttime, keyid, family) values("+inserttime + ",'" + keyId +"','"+ family + "')");
            switch (family) {
                case "tc" -> this.setTcKeyId(keyId);
                case "tm" -> this.setTmKeyId(keyId);
                case "pay" -> this.setPayloadKeyId(keyId);
            }
        } catch (StreamSqlException | ParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void streamClosed(Stream stream) {
        log.error("stream {} closed. this should not happen", stream);
    }
}
