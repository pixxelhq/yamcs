package org.yamcs.tctm.pus.services.tc.six;

import java.util.*;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.tctm.ccsds.error.CrcCciitCalculator;
import org.yamcs.tctm.pus.services.PusService;
import org.yamcs.tctm.pus.services.PusSubService;
import org.yamcs.tctm.pus.services.tc.PusTcCcsdsPacket;
import org.yamcs.tctm.pus.tuples.Pair;

public class ServiceSix implements PusService {
    Log log;
    private final String yamcsInstance;
    private final Map<Integer, PusSubService> pusSubServices = new HashMap<>();
    private final YConfiguration config;
    
    protected static Map<Pair<Integer, Integer>, Map<Integer, List<Pair<Integer, Integer>>>> memoryIds = new HashMap<>();

    protected static int DEFAULT_MEMORY_ID_SIZE = 1;
    protected static int DEFAULT_BASE_ID_SIZE = 1;
    protected static int DEFAULT_NFIELDS_SIZE = 1;
    protected static int DEFAULT_OFFSET_SIZE = 2;
    protected static int DEFAULT_LENGTH_SIZE = 1;

    protected static int memoryIdSize;
    protected static int baseIdSize;
    protected static int nfieldsSize;
    protected static int offsetSize;
    protected static int lengthSize;
    protected static int offsetArgumentSize = 2;
    protected static int checksumSize = 2;

    protected static CrcCciitCalculator crc;

    public ServiceSix(String yamcsInstance, YConfiguration config) {
        this.yamcsInstance = yamcsInstance;
        this.config = config;

        memoryIdSize = config.getInt("memoryIdSize", DEFAULT_MEMORY_ID_SIZE);
        baseIdSize = config.getInt("baseIdSize", DEFAULT_BASE_ID_SIZE);
        nfieldsSize = config.getInt("nfieldsSize", DEFAULT_NFIELDS_SIZE);
        offsetSize = config.getInt("offsetSize", DEFAULT_OFFSET_SIZE);
        lengthSize = config.getInt("lengthSize", DEFAULT_LENGTH_SIZE);

        crc = new CrcCciitCalculator(config.getConfig("crc"));
        List<String> memoryIdStr = config.getList("memoryId");
        YConfiguration memoryIdConfig = YConfiguration.getConfiguration("six", "pus");
        
        for (String memoryId: memoryIdStr) {
            if (memoryIdConfig.containsKey(memoryId)) {
                YConfiguration memoryIdMap = memoryIdConfig.getConfig(memoryId);
                
                Map<Integer, List<Pair<Integer, Integer>>> baseIds = new HashMap<>();
                for (YConfiguration baseConfig: memoryIdMap.getConfigList("baseId")) {
                    int baseIdValue = baseConfig.getInt("value");

                    List<Pair<Integer, Integer>> offsets = new ArrayList<>();
                    for (YConfiguration offsetConfig: baseConfig.getConfigList("offsets")) {
                        if (offsetConfig.isList("value")) {
                            List<Integer> values = offsetConfig.getList("value");
                            for (int v: values)
                                offsets.add(new Pair<>(
                                    v, offsetConfig.getInt("length")
                                ));
                        } else
                            offsets.add(new Pair<>(
                                offsetConfig.getInt("value"), offsetConfig.getInt("length")
                            ));
                    }
                    baseIds.put(baseIdValue, offsets);
                }

                memoryIds.put(new Pair<>(
                    memoryIdMap.getInt("apid"), memoryIdMap.getInt("value")
                ), baseIds);

            } else {
                throw new ConfigurationException("Provided memoryId: " + memoryId + " does not exist in the config");
            }
        }

        initializeSubServices();
    }

    @Override
    public void initializeSubServices() {
        pusSubServices.put(1, new SubServiceOne(yamcsInstance, config.getConfigOrEmpty("one")));
        pusSubServices.put(3, new SubServiceThree(yamcsInstance, config.getConfigOrEmpty("three")));
        pusSubServices.put(5, new SubServiceFive(yamcsInstance, config.getConfigOrEmpty("five")));
        pusSubServices.put(7, new SubServiceSeven(yamcsInstance, config.getConfigOrEmpty("seven")));
        pusSubServices.put(12, new SubServiceTwelve(yamcsInstance, config.getConfigOrEmpty("twelve")));
        pusSubServices.put(15, new SubServiceFifteen(yamcsInstance, config.getConfigOrEmpty("fifteen")));
        pusSubServices.put(16, new SubServiceSixteen(yamcsInstance, config.getConfigOrEmpty("sixteen")));
        pusSubServices.put(17, new SubServiceSeventeen(yamcsInstance, config.getConfigOrEmpty("seventeen")));
    }

    @Override
    public PreparedCommand addPusModifiers(PreparedCommand telecommand) {
        return pusSubServices.get(PusTcCcsdsPacket.getMessageSubType(telecommand)).process(telecommand);
    }

    @Override
    public ArrayList<TmPacket> extractPusModifiers(TmPacket tmPacket) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'extractPusModifiers'");
    }
}

