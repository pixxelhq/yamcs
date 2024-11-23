package org.yamcs.security.encryption.aes;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

import org.yamcs.YConfiguration;
import org.yamcs.security.encryption.SymmetricEncryption;
import org.yamcs.utils.StringConverter;
import org.yamcs.YamcsServer;
import org.yamcs.logging.Log;

public class GCM implements SymmetricEncryption {
    private static final Log log = new Log(SymmetricEncryption.class);

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    private static final int TAG_LENGTH = 16;
    private static final int IV_LENGTH = 12;

    protected KeyManagementService keyMgmService;

    byte[] aad;

    @Override
    public void init(String yamcsInstance, YConfiguration config) {
        String keyMgmServiceName = config.getString("keyManagementService");
        keyMgmService = YamcsServer.getServer().getInstance(yamcsInstance).getService(KeyManagementService.class, keyMgmServiceName);
    }

    public byte[] encrypt(byte[] plainMessage)
            throws NoSuchAlgorithmException,InvalidKeySpecException, InvalidKeyException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = getRandomNonce(IV_LENGTH);
        SecretKey secretKey = getSecretKey(keyMgmService.getTcKey());
        log.warn("KeyBeing Used: {}", secretKey.toString());

        Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, secretKey, iv);
        if (aad != null)
            cipher.updateAAD(aad);

        byte[] encryptedMessage = cipher.doFinal(plainMessage);

        // Add IV to the beginning
        return ByteBuffer.allocate(iv.length + encryptedMessage.length)
                .put(iv)
                .put(encryptedMessage)
                .array();
    }

    public byte[] getRandomNonce(int length) {
        byte[] nonce = new byte[length];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    private Cipher initCipher(int mode, SecretKey secretKey, byte[] iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException, NoSuchPaddingException, NoSuchAlgorithmException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(mode, secretKey, new GCMParameterSpec(TAG_LENGTH * 8, iv));
        return cipher;
    }

    public SecretKey getSecretKey(String key)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        return new SecretKeySpec(StringConverter.hexStringToArray(key), "AES");
    }

    @Override
    public byte[] decrypt(byte[] cipherContent) throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException,
            InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException  {
        SecretKey secretKey = getSecretKey(keyMgmService.getTmKey());
        log.warn("KeyBeing Used: {}", secretKey.toString());
        ByteBuffer bb = ByteBuffer.wrap(cipherContent);

        byte[] iv = new byte[IV_LENGTH];
        bb.get(iv);

        byte[] content = new byte[bb.remaining()];
        bb.get(content);

        Cipher cipher = initCipher(Cipher.DECRYPT_MODE, secretKey, iv);
        if (aad != null)
            cipher.updateAAD(aad);

        return cipher.doFinal(content);
    }

    @Override
    public int getTagLength() {
        return TAG_LENGTH;
    }

    @Override
    public int getIVLength() {
        return IV_LENGTH;
    }
}
