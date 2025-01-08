package org.yamcs.tctm.pus.services.tm.one;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.tctm.pus.services.PusService;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tm.PusTmCcsdsPacket;

public class ServiceOne implements PusService {
    public static Map<Integer, String> ccsdsApids = new HashMap<>();
    public static Map<Integer, String> failureCodes = new HashMap<>();

    Log log;
    Map<Integer, PusSubService> pusSubServices = new HashMap<>();
    String yamcsInstance;
    YConfiguration serviceOneConfig;

    public static final int DEFAULT_FAILURE_CODE_SIZE = 1;
    public static final int DEFAULT_FAILURE_DATA_SIZE = 4;
    public static final int REQUEST_ID_LENGTH = 4;

    public static int failureCodeSize;
    public static int failureDataSize;

    public ServiceOne(String yamcsInstance, YConfiguration config) {
        this.yamcsInstance = yamcsInstance;
        serviceOneConfig = config;

        failureCodeSize = config.getInt("failureCodeSize", DEFAULT_FAILURE_CODE_SIZE);
        failureDataSize = config.getInt("failureDataSize", DEFAULT_FAILURE_DATA_SIZE);

        YConfiguration apidConfig = YConfiguration.getConfiguration("apids", "pus");
        YConfiguration errorCodeConfig = YConfiguration.getConfiguration("errorCodes", "pus");

        if (apidConfig.containsKey("apids")) {
            List<YConfiguration> apids = apidConfig.getConfigList("apids");
            for (YConfiguration apid : apids) {
                ccsdsApids.put(apid.getInt("value"), apid.getString("name"));
            }
        }

        if (errorCodeConfig.containsKey("apids")) {
            List<YConfiguration> errorCodes = errorCodeConfig.getConfigList("errorCodes");
            for (YConfiguration errorCode : errorCodes) {
                failureCodes.put(errorCode.getInt("value"), errorCode.getString("name"));
            }
        }

        initializeSubServices();
    }

    public void initializeSubServices() {
        pusSubServices.put(1, new SubServiceOne(yamcsInstance));
        pusSubServices.put(2, new SubServiceTwo(yamcsInstance));
        pusSubServices.put(7, new SubServiceSeven(yamcsInstance));
        pusSubServices.put(8, new SubServiceEight(yamcsInstance));
    }

    @Override
    public ArrayList<TmPacket> extractPusModifiers(TmPacket tmPacket) {
        return pusSubServices.get(PusTmCcsdsPacket.getMessageSubType(tmPacket.getPacket())).process(tmPacket);
    }

    @Override
    public PreparedCommand addPusModifiers(PreparedCommand telecommand) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addPusModifiers'");
    }
}
