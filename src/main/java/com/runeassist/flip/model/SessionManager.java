package com.runeassist.flip.model;

import com.runeassist.flip.controller.Persistance;
import com.google.gson.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SessionManager {

    public static final String SESSION_DATA_FILE_TEMPLATE = "%s_session_data.jsonl";

    private final OsrsLoginManager osrsLoginManager;
    private final ScheduledExecutorService executorService;
    private final Gson gson;

    private final Map<String, SessionData> cachedSessionData =  new HashMap<>();
    private final Map<String, File> displayNameToFile = new HashMap<>();

    private Instant lastSessionUpdateTime;

    public synchronized SessionData getCachedSessionData() {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return new SessionData((int) Instant.now().getEpochSecond(), 0, 0);
        }
        SessionData sd = getSessionData(displayName);
        return new SessionData(sd.startTime,  sd.durationMillis, sd.averageCash);
    }

    /**
     * Begin or resume the flipping session for the logged-in OSRS account.
     * Start the session clock from the OSRS display name (RSN).
     */
    public synchronized void startOrResume() {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return;
        }
        getSessionData(displayName);
        if (lastSessionUpdateTime == null) {
            lastSessionUpdateTime = Instant.now();
        }
    }

    public synchronized void resetSession() {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            lastSessionUpdateTime = Instant.now();
            return;
        }
        SessionData sd = getSessionData(displayName);
        sd.startTime = (int) Instant.now().getEpochSecond();
        sd.averageCash = 0;
        sd.durationMillis = 0;
        lastSessionUpdateTime = Instant.now();
        saveAsync(displayName);
    }

    public synchronized boolean updateSessionStats(boolean currentlyFlipping, long cashStack) {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            lastSessionUpdateTime = null;
            return false;
        } else if (lastSessionUpdateTime == null) {
            lastSessionUpdateTime = Instant.now();
            return true;
        } else {
            SessionData sd = getSessionData(displayName);
            Instant now = Instant.now();
            long duration = Duration.between(lastSessionUpdateTime, now).toMillis();
            if (duration < 0) {
                duration = 0;
            }
            long total = sd.durationMillis + duration;
            if (currentlyFlipping && total > 0) {
                sd.averageCash = (cashStack * duration + sd.durationMillis * sd.averageCash) / total;
            }
            sd.durationMillis = total;
            lastSessionUpdateTime = now;
            saveAsync(displayName);
            return true;
        }
    }

    private void saveAsync(String displayName) {
        executorService.submit(() -> {
            File file = getFile(displayName);
            synchronized (file) {
                SessionData data = cachedSessionData.computeIfAbsent(displayName, this::load);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                    String json = gson.toJson(data);
                    writer.write(json);
                    writer.newLine();
                } catch (IOException e) {
                    log.warn("error storing session data to file {}", file, e);
                }
            }
        });
    }

     private SessionData load(String displayName) {
        File file = getFile(displayName);
        if (!file.exists()) {
            return new SessionData((int) Instant.now().getEpochSecond(), 0 ,0);
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            SessionData sd =  gson.fromJson(reader, SessionData.class);
            if (sd != null) {
                return sd;
            }
        } catch (JsonSyntaxException | JsonIOException | IOException e) {
            log.warn("error loading session data json file {}", file, e);
        }
        return new SessionData((int) Instant.now().getEpochSecond(), 0 ,0);
    }

    private File getFile(String displayName) {
        return displayNameToFile.computeIfAbsent(displayName,
                (k) -> new File(Persistance.PLUGIN_DIR, String.format(SESSION_DATA_FILE_TEMPLATE, Persistance.hashDisplayName(displayName))));
    }

    private SessionData getSessionData(String displayName) {
         return cachedSessionData.computeIfAbsent(displayName, this::load);
    }

    public synchronized void reset() {
        lastSessionUpdateTime = null;
    }
}
