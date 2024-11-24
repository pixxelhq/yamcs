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
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;


public class KeyManagementApi extends AbstractKeyManagmentApi<Context>{
    private static final Log log = new Log(KeyManagementApi.class);

    public static final Pattern ALLOWED_INSTANCE_NAMES = Pattern.compile("\\w[\\w\\.-]*");

    // @Override
    // public void updateKey(Context ctx, UpdateKeyRequest request, Observer<UpdateKeyResponse> observer) {
    //     ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);

    //     KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");
    //     TimeService timeService = YamcsServer.getTimeService(request.getInstance());

    //     Stream responseStream = StreamFactory.asyncResponse(request.getInstance(), KeyManagementService.KEY_INSERTION_RESPONSE_TUPLE_DEFINITION.copy());
    //     responseStream.addSubscriber(new StreamSubscriber() {
    //         @Override
    //         public void onTuple(Stream stream, Tuple t) {
    //             boolean status = (Boolean) t.getBooleanColumn("status");

    //             UpdateKeyResponse.Builder updateKeyResponse = UpdateKeyResponse.newBuilder();

    //             if (status) {
    //                 String keyId = (String) t.getColumn("keyid");
    //                 String family = (String) t.getColumn("family");

    //                 updateKeyResponse.setKeyFamily(family).setKeyId(keyId);

    //             } else {
    //                 String error = (String) t.getColumn("error");
    //                 updateKeyResponse.setError(error);
    //             }

    //             // Send response
    //             observer.complete(updateKeyResponse.build());

    //             // Close temporary HTML stream
    //             stream.close();
    //         }
    //     });


    //     // Add temporary column
    //     TupleDefinition modTd = KeyManagementService.ACTIVE_KEY_TUPLE_DEFINITION.copy();
    //     modTd.addColumn("responsestream", DataType.STRING);

    //     Stream publishStream = keyMgmService.getStream();
    //     publishStream.emitTuple(new Tuple(KeyManagementService.ACTIVE_KEY_TUPLE_DEFINITION, new Object[]{
    //             timeService.getMissionTime(),
    //             request.getKeyId(),
    //             request.getKeyFamily(),
    //             responseStream.getName()
    //         })
    //     );
    // }


    @Override
    public void updateKey(Context ctx, UpdateKeyRequest request, Observer<UpdateKeyResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);

        KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");
        TimeService timeService = YamcsServer.getTimeService(request.getInstance());

        Stream stream = keyMgmService.getStream();

        try {
            Tuple t = new Tuple(KeyManagementService.ACTIVE_KEY_TUPLE_DEFINITION, new Object[]{
                    timeService.getMissionTime(),
                    request.getKeyId(),
                    request.getKeyFamily(),
            });
            stream.emitTuple(t);
            UpdateKeyResponse.Builder updateKeyResponse = UpdateKeyResponse.newBuilder();
            updateKeyResponse.setKeyId(request.getKeyId());
            observer.complete(updateKeyResponse.build());
        } catch (Exception e){
            log.warn("Error while updating key: {}", e);
            observer.completeExceptionally(e);
        }
    }

    @Override
    public void getActiveKey(Context ctx, ActiveKeyRequest request, Observer<ActiveKeyResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlServices);

        KeyManagementService keyMgmService = YamcsServer.getServer().getInstance(request.getInstance()).getService(KeyManagementService.class, "keyManagementService");

        try {
            ActiveKeyResponse.Builder activeKeyResponse = ActiveKeyResponse.newBuilder();
            if (request.getFamily().equals("tm")) {
                activeKeyResponse.setKeyId(keyMgmService.getTmKeyId());
            } else if(request.getFamily().equals("tc")) {
                activeKeyResponse.setKeyId(keyMgmService.getTcKeyId());
            } else if((request.getFamily().equals("pay"))){
                activeKeyResponse.setKeyId(keyMgmService.getPayloadKeyId());
            } else{
                activeKeyResponse.setKeyId("family not found");
            }
            activeKeyResponse.setFamily(request.getFamily());
            activeKeyResponse.setInstance(request.getInstance());
            observer.complete(activeKeyResponse.build());
        } catch (Exception e){
            log.warn("Error while updating key: {}", e);
            observer.completeExceptionally(e);
        }
    }

}
