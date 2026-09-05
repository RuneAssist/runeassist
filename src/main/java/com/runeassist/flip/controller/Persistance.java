package com.runeassist.flip.controller;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
public class Persistance {
    public static Gson gson;
    public static final File PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "runeassist-flip");
    public static final String LOGIN_RESPONSE_JSON_FILE = "login-response.json";
    public static File directory;

    public static void setUp(String directoryPath) throws IOException {
        directory = new File(directoryPath);
        createDirectory(directory);
        createRequiredFiles();
    }

    public static void setUp(Gson gson) throws IOException {
        Persistance.gson = gson;
        directory = PLUGIN_DIR;
        createDirectory(PLUGIN_DIR);
        createRequiredFiles();
    }

    public static boolean hasExistingInstallation() {
        if (!PLUGIN_DIR.exists() || !PLUGIN_DIR.isDirectory()) {
            return false;
        }

        String[] files = PLUGIN_DIR.list();
        return files != null && files.length > 0;
    }

    private static void createRequiredFiles() throws IOException {
        generateFileIfDoesNotExist(LOGIN_RESPONSE_JSON_FILE);
    }

    private static void generateFileIfDoesNotExist(String filename) throws IOException {
        File file = new File(directory, filename);
        if (!file.exists()) {
            if (!file.createNewFile()) {
                log.info("Failed to generate file {}", file.getPath());
            }
        }
    }

    private static void createDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            if (!directory.mkdir()) {
                throw new IOException("unable to create parent directory!");
            }
        }
    }

    public static String hashDisplayName(String displayName) {
        if(displayName == null) {
            return "null";
        }
        // we hash the display name just to ensure that it's a valid file name
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
