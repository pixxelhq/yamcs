package org.yamcs.http.api;

import java.util.regex.Pattern;

import org.yamcs.YamcsServer;
import org.yamcs.api.Observer;
import org.yamcs.http.Context;
import org.yamcs.http.ForbiddenException;
import org.yamcs.protobuf.*;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.security.encryption.aes.KeyManagementService;
import org.yamcs.time.TimeService;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;


public class KeyManagementApi extends AbstractKeyManagmentApi<Context>{
    public static final Pattern ALLOWED_INSTANCE_NAMES = Pattern.compile("\\w[\\w\\.-]*");

    @Override
    public void updateKey(Context ctx, UpdateKeyRequest request, Observer<KeyResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);

        KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");
        TimeService timeService = YamcsServer.getTimeService(request.getInstance());

        if (keyMgmService == null) {
            throw new ForbiddenException("KeyManagementService is not configured");
        }

        if (keyMgmService.getClient() == null) {
            throw new ForbiddenException("KeyManagementService is configured, but vault access not provided");
        }

        Stream publishStream = keyMgmService.getStream();
        publishStream.emitTuple(new Tuple(KeyManagementService.ACTIVE_KEY_TUPLE_DEFINITION, new Object[]{
                timeService.getMissionTime(),
                request.getKeyId(),
                request.getFamily(),
            })
        );

        KeyResponse.Builder response = KeyResponse.newBuilder();
        response
            .setFamily(request.getFamily())
            .setKeyId(request.getKeyId());

        observer.complete(response.build());
    }

    @Override
    public void getActiveKey(Context ctx, ActiveKeyRequest request, Observer<KeyResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);
        KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");

        if (keyMgmService == null) {
            throw new ForbiddenException("KeyManagementService is not configured");
        }

        if (keyMgmService.getClient() == null) {
            throw new ForbiddenException("KeyManagementService is configured, but vault access not provided");
        }

        String keyId;
        switch (request.getFamily()) {
            case "tm" -> keyId = keyMgmService.getTmKeyId();
            case "tc" -> keyId = keyMgmService.getTcKeyId();
            default -> throw new RuntimeException("Key Family not found");
        }

        KeyResponse.Builder activeKeyResponse = KeyResponse.newBuilder();
        activeKeyResponse
            .setFamily(request.getFamily())
            .setInstance(request.getInstance())
            .setKeyId(keyId);

        observer.complete(activeKeyResponse.build());
    }

}
