package org.yamcs.security.encryption.aes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyParser {
    private Map<String, Map<String, KeyEntry>> keySections;

    public KeyParser() {
        keySections = new HashMap<>();
    }

    public void parse(String decryptedData) {
        try (BufferedReader reader = new BufferedReader(new StringReader(decryptedData))) {
            String line;
            String currentSection = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Detect section headers (e.g., "TM Keys", "TC Keys")
                if (line.endsWith("Keys")) {
                    currentSection = line;
                    keySections.putIfAbsent(currentSection, new HashMap<>());
                }
                // Detect regular key entries
                else if (line.startsWith("FF")) {
                    String[] parts = line.split(",");
                    if (parts.length == 3 && currentSection != null) {
                        KeyEntry entry = new KeyEntry(parts[1], parts[2]);
                        keySections.get(currentSection).put(parts[0], entry);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Map<String, KeyEntry>> getKeySections() {
        return keySections;
    }

    public static class KeyEntry {
        private String key;
        private String checksum;

        public KeyEntry(String key, String checksum) {
            this.key = key;
            this.checksum = checksum;
        }

        @Override
        public String toString() {
            return "KeyEntry{" + " key='" + key + '\'' + ", checksum='" + checksum + '\'' + '}';
        }

        public String getKey() {
            return key;
        }

        public String getChecksum() {
            return checksum;
        }
    }
}

