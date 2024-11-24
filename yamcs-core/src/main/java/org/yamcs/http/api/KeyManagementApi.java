package org.yamcs.http.api;

import java.util.regex.Pattern;

import org.yamcs.YamcsServer;
import org.yamcs.api.Observer;
import org.yamcs.http.Context;
import org.yamcs.logging.Log;
import org.yamcs.protobuf.*;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.security.encryption.aes.KeyManagementService;
import org.yamcs.time.TimeService;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;


public class KeyManagementApi extends AbstractKeyManagmentApi<Context>{
    private static final Log log = new Log(KeyManagementApi.class);

    public static final Pattern ALLOWED_INSTANCE_NAMES = Pattern.compile("\\w[\\w\\.-]*");

    @Override
    public void updateKey(Context ctx, UpdateKeyRequest request, Observer<UpdateKeyResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);

        KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");
        TimeService timeService = YamcsServer.getTimeService(request.getInstance());

        try {
            Stream publishStream = keyMgmService.getStream();
            publishStream.emitTuple(new Tuple(KeyManagementService.ACTIVE_KEY_TUPLE_DEFINITION, new Object[]{
                    timeService.getMissionTime(),
                    request.getKeyId(),
                    request.getKeyFamily(),
                })
            );

            UpdateKeyResponse.Builder updateKeyResponse = UpdateKeyResponse.newBuilder();
            updateKeyResponse
                .setKeyFamily(request.getKeyFamily())
                .setKeyId(request.getKeyId());

            observer.complete(updateKeyResponse.build());
        } catch (RuntimeException e) {
            log.warn("Error while updating key: {}", e);
            observer.completeExceptionally(e);
        }
    }

    @Override
    public void getActiveKey(Context ctx, ActiveKeyRequest request, Observer<ActiveKeyResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);
        KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");

        String keyId;
        try {
            switch (request.getFamily()) {
                case "tm" -> keyId = keyMgmService.getTmKeyId();
                case "tc" -> keyId = keyMgmService.getTcKeyId();
                case "pay" -> keyId = keyMgmService.getPayloadKeyId();
                default -> keyId = "family not found";
            }

            ActiveKeyResponse.Builder activeKeyResponse = ActiveKeyResponse.newBuilder();
            activeKeyResponse
                .setFamily(request.getFamily())
                .setInstance(request.getInstance())
                .setKeyId(keyId);

            observer.complete(activeKeyResponse.build());

        } catch (Exception e){
            log.warn("Error while updating key: {}", e);
            observer.completeExceptionally(e);
        }
    }

}
