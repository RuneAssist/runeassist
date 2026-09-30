package com.runeassist.flip.controller;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class Persistance {
    public static Gson gson;
    public static final String LOGIN_RESPONSE_JSON_FILE = "login-response.json";
    public static final String UNACKED_TRANSACTIONS_FILE_TEMPLATE = "%s_unacked.jsonl";
    private static volatile Filepath dataDir;

    public static void setDataDir(Filepath dir) {
        dataDir = dir;
    }

    public static Filepath dataDir() {
        Filepath dir = dataDir;
        if (dir == null) {
            throw new IllegalStateException("RuneAssist data directory is not initialised yet");
        }
        return dir;
    }

    public static Filepath file(String name) {
        return dataDir().joinSegment(name);
    }

    public static void setUp(Gson gson) throws IOException {
        Persistance.gson = gson;
        Filepath dir = dataDir();
        if (!dir.exists()) {
            dir.createDirectories();
        }
        createRequiredFiles();
    }

    public static boolean hasExistingInstallation() {
        Filepath dir = dataDir;
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try (Stream<Filepath> entries = dir.walk(1)) {
            return entries.anyMatch(p -> !p.equals(dir));
        } catch (IOException e) {
            return false;
        }
    }

    private static void createRequiredFiles() throws IOException {
        generateFileIfDoesNotExist(LOGIN_RESPONSE_JSON_FILE);
    }

    private static void generateFileIfDoesNotExist(String filename) throws IOException {
        Filepath file = file(filename);
        if (!file.exists()) {
            file.write(new byte[0]);
        }
    }

    public static String readString(Filepath file) throws IOException {
        try (InputStream in = file.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    public static List<com.runeassist.flip.model.Transaction> loadUnackedTransactions(String displayName) {
        java.util.List<com.runeassist.flip.model.Transaction> transactions = new java.util.ArrayList<>();
        Filepath file = file(String.format(UNACKED_TRANSACTIONS_FILE_TEMPLATE, hashDisplayName(displayName)));
        if (!file.exists()) {
            return transactions;
        }
        java.util.Set<java.util.UUID> added = new java.util.HashSet<>();
        try (BufferedReader reader = file.openBufferedReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || gson == null) {
                    continue;
                }
                try {
                    com.runeassist.flip.model.Transaction transaction = gson.fromJson(line, com.runeassist.flip.model.Transaction.class);
                    if (transaction != null && transaction.getId() != null && added.add(transaction.getId())) {
                        transactions.add(transaction);
                    }
                } catch (com.google.gson.JsonSyntaxException e) {
                    log.warn("error deserializing unacked transaction line in {}", file, e);
                }
            }
        } catch (java.io.IOException e) {
            log.warn("error loading unacked transactions {}", file, e);
        }
        log.debug("loaded {} unacked transactions for {}", transactions.size(), displayName);
        return transactions;
    }

    public static void storeUnackedTransactions(java.util.List<com.runeassist.flip.model.Transaction> transactions, String displayName) {
        Filepath file = file(String.format(UNACKED_TRANSACTIONS_FILE_TEMPLATE, hashDisplayName(displayName)));
        try (BufferedWriter w = file.openBufferedWriter()) {
            if (transactions != null && gson != null) {
                for (com.runeassist.flip.model.Transaction transaction : transactions) {
                    w.write(gson.toJson(transaction));
                    w.newLine();
                }
            }
        } catch (java.io.IOException e) {
            log.warn("error storing unacked transactions to {}", file, e);
        }
    }

    public static String hashDisplayName(String displayName) {
        if(displayName == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashBytes = digest.digest(displayName.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
