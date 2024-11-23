package org.yamcs.security.encryption.aes;

import org.yamcs.AbstractYamcsService;
import org.yamcs.YamcsService;
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

import java.util.Objects;

public class KeyManagementService extends AbstractYamcsService implements YamcsService, StreamSubscriber {
    private static final Log log = new Log(SymmetricEncryption.class);

    public static final String TABLE_NAME = "activekeys";
    public static final String STREAM_NAME = "active_keys";

    public static final TupleDefinition ACTIVE_KEY_TUPLE_DEFINITION = new TupleDefinition();
    static {
        ACTIVE_KEY_TUPLE_DEFINITION.addColumn("inserttime", DataType.TIMESTAMP);
        ACTIVE_KEY_TUPLE_DEFINITION.addColumn("keyid", DataType.INT);
        ACTIVE_KEY_TUPLE_DEFINITION.addColumn("family", DataType.ENUM);
    }

    protected String yamcsInstance;

    String tcKey;
    String tmKey;
    Stream stream;
    String payloadKey;
    String spacecraftId;
    String tmKeyId;
    String tcKeyId;
    String payloadKeyId;

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

    @Override
    protected void doStart() {
        stream.addSubscriber(this);
        try {
            Integer tmKeyId = null;
            Integer tcKeyId = null;
            Integer payKeyId = null;

            StreamSqlResult result = ydb.execute("select * from " + TABLE_NAME + " where family='tm' order desc limit 1");

            log.warn("Results: {}", result);

            while (result.hasNext()) {
                tmKeyId = result.next().getColumn("keyid");
            }

            result = ydb.execute("select * from " +  TABLE_NAME + " where family='tc' order desc limit 1");
            log.warn("Results: {}", result);

            while (result.hasNext()) {
                tcKeyId = result.next().getColumn("keyid");
            }

            result = ydb.execute("select * from " +  TABLE_NAME + " where family='pay' order desc limit 1");
            log.warn("Results: {}", result);

            while (result.hasNext()) {
                payKeyId = result.next().getColumn("keyid");
            }

            if (tmKeyId!=null) {
                this.tmKeyId = tmKeyId.toString();
                this.tmKey = parser.getKeySections().get("TM Keys").get(this.spacecraftId + "_TM_" + tmKeyId).getKey();
            } else {
                this.tmKeyId = config.getString("defaultTmKeyId");
                this.tmKey = parser.getKeySections().get("TM Keys").get(this.spacecraftId + "_TM_" + config.getString("defaultTmKeyId")).getKey();
            }

            if (tcKeyId!=null) {
                this.tcKeyId = tcKeyId.toString();
                this.tcKey = parser.getKeySections().get("TC Keys").get(this.spacecraftId + "_TC_" + tcKeyId).getKey();
            } else {
                this.tcKeyId = config.getString("defaultTcKeyId");
                this.tcKey = parser.getKeySections().get("TC Keys").get(this.spacecraftId + "_TC_" + config.getString("defaultTcKeyId")).getKey();
            }

//            if (payKeyId!=null) {
//                this.payKeyId = payKeyId.toString();
//                this.tcKey = parser.getKeySections().get("PAY Keys").get(this.spacecraftId + "_PAY_" + payKeyId).getKey();
//            } else {
//                this.payloadKeyId = config.getString("defaultPayKeyId");
//                this.tcKey = parser.getKeySections().get("PAY Keys").get(this.spacecraftId + "_PAY_" + config.getString("defaultPayKeyId")).getKey();
//            }
        } catch (StreamSqlException | ParseException e) {
            throw new RuntimeException(e);
        }

        notifyStarted();
    }

    public String getTcKey() {
        return this.tcKey;
    }

    public String getTmKey() {
        return this.tmKey;
    }

    public String getPayloadKey(){
        return this.payloadKey;
    }

    public String getTcKeyId() {
        return this.tcKeyId;
    }

    public String getTmKeyId() {
        return this.tmKeyId;
    }

    public String getPayloadKeyId(){
        return this.payloadKeyId;
    }

    public void setTcKeyId(String tcKeyId) {
        this.tcKeyId = tcKeyId;
    }

    public void setTmKeyId(String tmKeyId) {
        this.tmKeyId = tmKeyId;
    }

    public void setPayloadKeyId(String payloadKeyId){
        this.payloadKeyId = payloadKeyId;
    }

    public Stream getStream(){
        return stream;
    }

    public void setTcKey(String tcKeyId) {
        log.debug("New TC KEY ID: {}", tcKeyId);
        this.tcKey = this.parser.getKeySections().get("TC Keys").get(config.getString("spacecraftIdSrs")+"_TC_"+tcKeyId).getKey();
    }

    public void setTmKey(String tmKeyId) {
        log.debug("New TM KEY ID: {}", tmKeyId);
        this.tmKey = this.parser.getKeySections().get("TM Keys").get(config.getString("spacecraftIdSrs")+"_TM_"+tmKeyId).getKey();
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
            long inserttime = (Long)tuple.getColumn("inserttime");
            String keyId = (String)tuple.getColumn("keyid");
            String family = (String)tuple.getColumn("family");
            ydb.execute("insert into " + TABLE_NAME + "(inserttime, keyid, family) values("+inserttime + ",'" + keyId +"','"+ family + "')");
            if (Objects.equals(family, "tc")) {
                this.setTcKeyId(keyId);
            }else if (Objects.equals(family, "tm")) {
                this.setTmKeyId(keyId);
            }else if (Objects.equals(family, "pay")) {
                this.setPayloadKeyId(keyId);
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
