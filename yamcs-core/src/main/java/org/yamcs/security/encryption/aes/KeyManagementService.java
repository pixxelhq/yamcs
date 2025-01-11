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

    String spacecraftId;
    Stream stream;

    private String tmKeyId;
    private String tcKeyId;

    KeyParser parser;
    VaultClient client = null;
    YarchDatabaseInstance ydb;

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();

        Spec vault = new Spec();
        Spec df = new Spec();

        df.addOption("tm", OptionType.STRING);
        df.addOption("tc", OptionType.STRING);

        vault.addOption("token", OptionType.STRING);
        vault.addOption("namespace", OptionType.STRING);
        vault.addOption("address", OptionType.STRING);
        vault.addOption("cipher", OptionType.STRING);
        vault.addOption("spacecraftId", OptionType.STRING);
        vault.addOption("fallback", OptionType.MAP).withSpec(df);

        spec.addOption("vault", OptionType.MAP).withSpec(vault);
        spec.addOption("default", OptionType.MAP).withSpec(df);
        spec.addOption("stream", OptionType.STRING).withDefault("active_key");

        return spec;
    }

    public VaultClient getClient() {
        return client;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        this.yamcsInstance = yamcsInstance;

        String cipherText;
        if (config.containsKey("vault")) {
            YConfiguration vc = config.getConfig("vault");
            client = new VaultClient(
                vc.getString("token"), vc.getString("namespace"), vc.getString("address"));
            spacecraftId = vc.getString("spacecraftId");
            cipherText = vc.getString("cipher");

            // Load from vault
            parser = new KeyParser();
            try {
                parser.parse(client.decrypt(cipherText));

            } catch (Exception e) {
                // FIXME: 
                throw new RuntimeException(e);
            }

        } else {
            YConfiguration dc = config.getConfig("default");
            tmKey = dc.getString("tm");
            tcKey = dc.getString("tc");
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
    private Optional<String> fetchKeyId(String tableName, String family) throws StreamSqlException, ParseException {
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
        if (client != null) {
            stream.addSubscriber(this);
            YConfiguration fallback = config.getConfig("vault").getConfig("fallback");

            try {
                Optional<String> tmKeyId = fetchKeyId(TABLE_NAME, "tm");
                Optional<String> tcKeyId = fetchKeyId(TABLE_NAME, "tc");

                // Process TM Key
                this.tmKeyId = tmKeyId
                        .map(String::valueOf)
                        .orElseGet(() -> fallback.getString("tm"));
                setTmKey(this.tmKeyId);

                // Process TC Key
                this.tcKeyId = tcKeyId
                        .map(String::valueOf)
                        .orElseGet(() -> fallback.getString("tc"));
                setTcKey(this.tcKeyId);
            
            } catch (StreamSqlException | ParseException e) {
                throw new RuntimeException(e);
            }
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

    // Key ID GET
    public String getTcKeyId() {
        return this.tcKeyId;
    }

    public String getTmKeyId() {
        return this.tmKeyId;
    }

    public Stream getStream(){
        return stream;
    }

    private void setTcKey(String keyId) {
        if (client != null) {
            this.tcKey = this.parser.getKeySections()
                    .get("TC Keys")
                    .get(spacecraftId + "_TC_" + keyId)
                    .getKey();
            this.tcKeyId = keyId;
        }
    }

    private void setTmKey(String keyId) {
        if (client != null) {
            this.tmKey = this.parser.getKeySections()
                    .get("TM Keys")
                    .get(spacecraftId + "_TM_" + keyId)
                    .getKey();
            this.tmKeyId = keyId;
        }
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

            ydb.execute("insert into " + TABLE_NAME + "(inserttime, keyid, family) values(" + inserttime + ",'" + keyId + "','" + family + "')");
            switch (family) {
                case "tc" -> this.setTcKey(keyId);
                case "tm" -> this.setTmKey(keyId);
                default -> throw new ParseException("Key Family not found");
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
