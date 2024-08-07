package org.yamcs.tctm.ccsds;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;

/**
 * Stores configuration related to Virtual Channels
 * @author nm
 *
 */
public class VcDownlinkManagedParameters {
    protected enum TMDecoder {
        CCSDS,
        SINGLE,
        MULTIPLE;
    }

    protected int vcId;
    //if set to true, the encapsulation packets sent to the preprocessor will be without the encapsulation header(CCSDS 133.1-B-2)
    boolean stripEncapsulationHeader;
    TMDecoder tmDecoder;
    boolean isIdleVcid;

    // if service = M_PDU
    int maxPacketLength;
    String packetPreprocessorClassName;
    YConfiguration packetPreprocessorArgs;
    final YConfiguration config;
    protected String vcaHandlerClassName;
    
    public VcDownlinkManagedParameters(int vcId) {
        this.vcId = vcId;
        this.config = null;
        tmDecoder = TMDecoder.CCSDS;
    }
    
    public VcDownlinkManagedParameters(YConfiguration config) {
        this.config = config;
        this.vcId = config.getInt("vcId");
        tmDecoder = config.getEnum("tmDecoder", TMDecoder.class, TMDecoder.MULTIPLE);
    }
    
    
    protected void parsePacketConfig() {
        maxPacketLength = config.getInt("maxPacketLength", 65536);
        if (maxPacketLength < 7) {
            throw new ConfigurationException("invalid maxPacketLength: " + maxPacketLength);
        }

        packetPreprocessorClassName = config.getString("packetPreprocessorClassName");
        if (config.containsKey("packetPreprocessorArgs")) {
            packetPreprocessorArgs = config.getConfig("packetPreprocessorArgs");
        }
        stripEncapsulationHeader = config.getBoolean("stripEncapsulationHeader", false);
        isIdleVcid = config.getBoolean("isIdle", false);
    }

    protected void parseVcaConfig() {
        this.vcaHandlerClassName = config.getString("vcaHandlerClassName");
    }
}
