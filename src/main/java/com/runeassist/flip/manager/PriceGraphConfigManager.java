package com.runeassist.flip.manager;

import com.runeassist.flip.controller.Persistance;
import com.runeassist.flip.ui.graph.model.Config;
import com.google.gson.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import net.runelite.client.util.Filepath;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PriceGraphConfigManager {

    public static final String JSON_FILE = "price_graph_config.json";

    private final Object fileLock = new Object();

    private final Gson gson;
    private final ScheduledExecutorService executorService;

    private Config cachedConfig;

    public synchronized Config getConfig() {
        if(cachedConfig != null) {
            return cachedConfig;
        }
        cachedConfig = load();
        return cachedConfig;
    }

    public synchronized void setConfig(Config config) {
        if (config == null) {
            return;
        }
        cachedConfig = config;
        saveAsync();
    }

    public void saveAsync() {
        executorService.submit(() -> {
            synchronized (fileLock) {
                Config config = getConfig();
                try {
                    Persistance.file(JSON_FILE).write(gson.toJson(config));
                } catch (IOException e) {
                    log.warn("error saving graph config {}", e.getMessage(), e);
                }
            }
        });
    }

    public Config load() {
        Filepath file = Persistance.file(JSON_FILE);
        try (BufferedReader reader = file.openBufferedReader()) {
            return gson.fromJson(reader, Config.class);
        } catch (NoSuchFileException ignored) {
            return new Config();
        } catch (JsonSyntaxException | JsonIOException | IOException e) {
            log.warn("error loading saved graph config json file {}", file, e);
            return new Config();
        }
    }
}
