package com.runeassist.flip.model;

import com.runeassist.flip.controller.Persistance;
import com.runeassist.flip.rs.AccountSuggestionPreferencesRS;
import com.google.gson.Gson;
import com.google.inject.name.Named;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.runelite.client.util.Filepath;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
@Slf4j
public class SuggestionPreferencesManager {

    private static final int DEFAULT_TIMEFRAME = 5;
    public static final long DEFAULT_DUMP_MIN_PROFIT = 100_000;
    public static final long DEFAULT_MIN_PREDICTED_PROFIT = 20_000L;
    public static final int DEFAULT_TIME_BASED_ABORT_MINUTES = 15;

    public static final String DEFAULT_PROFILE_NAME = "Default profile";
    public static final String PROFILE_SUFFIX = ".profile.json";

    private final Gson gson;
    private final ScheduledExecutorService executorService;
    private final AccountSuggestionPreferencesRS osrsAccountPreferences;
    private final Filepath dataDir;
    private final Filepath defaultProfile;

    private ProfileSuggestionPreferences cachedPreferences;
    private Filepath selectedProfile;
    private List<Filepath> availableProfiles;

    @Getter
    @Setter
    private volatile boolean sellOnlyMode = false;

    @Inject
    public SuggestionPreferencesManager(Gson gson,
                                        @Named("runeAssistExecutor") ScheduledExecutorService executorService,
                                        AccountSuggestionPreferencesRS osrsAccountPreferences,
                                        @Named("runeAssistDataDir") Filepath dataDir) {
        this.gson = gson;
        this.executorService = executorService;
        this.osrsAccountPreferences = osrsAccountPreferences;
        this.dataDir = dataDir;
        this.defaultProfile = profilePath(DEFAULT_PROFILE_NAME);
        loadAvailableProfiles();
        selectedProfile = defaultProfile;
        loadCurrentProfile();
        executorService.scheduleAtFixedRate(() -> {
            this.loadAvailableProfiles();
            this.loadCurrentProfile();
        }, 5L, 5L, TimeUnit.SECONDS);
    }

    public synchronized List<String> getAvailableProfiles() {
        return availableProfiles.stream().map(this::toDisplayName).collect(Collectors.toList());
    }

    public synchronized boolean isF2pOnlyMode() {
        return osrsAccountPreferences.get().isF2pOnlyMode();
    }

    public synchronized boolean isBuyAndHold() {
        return osrsAccountPreferences.get().isBuyAndHold();
    }

    public synchronized void setBuyAndHold(boolean buyAndHold) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setBuyAndHold(buyAndHold);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized void setF2pOnlyMode(boolean f2pOnlyMode) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setF2pOnlyMode(f2pOnlyMode);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized void setTimeframe(int minutes) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setTimeframe(minutes > 0 ? minutes : DEFAULT_TIMEFRAME);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized int getTimeframe() {
        return osrsAccountPreferences.get().getTimeframe();
    }

    public synchronized void setRiskLevel(RiskLevel riskLevel) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setRiskLevel(riskLevel == null ? RiskLevel.MEDIUM : riskLevel);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized RiskLevel getRiskLevel() {
        return osrsAccountPreferences.get().getRiskLevel();
    }

    public synchronized void setReservedSlots(Integer reservedSlots) {
        Integer clamped = reservedSlots == null ? null : Math.max(0, Math.min(8, reservedSlots));
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setReservedSlots(clamped);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized Integer getReservedSlots() {
        return osrsAccountPreferences.get().getReservedSlots();
    }

    public synchronized int getEffectiveReservedSlots() {
        Integer reservedSlots = getReservedSlots();
        if (reservedSlots != null) {
            return reservedSlots;
        }
        boolean dumpAlertsEnabled = isReceiveDumpSuggestions();
        int timeframeMinutes = getTimeframe();
        return (dumpAlertsEnabled && timeframeMinutes <= 30) ? 1 : 0;
    }

    public synchronized void setMinPredictedProfit(Long minPredictedProfit) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setMinPredictedProfit(minPredictedProfit);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized Long getMinPredictedProfit() {
        Long v = osrsAccountPreferences.get().getMinPredictedProfit();
        return v == null ? DEFAULT_MIN_PREDICTED_PROFIT : v;
    }

    public synchronized void setDumpMinPredictedProfit(Long dumpMinPredictedProfit) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setDumpMinPredictedProfit(dumpMinPredictedProfit);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized Long getDumpMinPredictedProfit() {
        return osrsAccountPreferences.get().getDumpMinPredictedProfit();
    }

    public synchronized Long getEffectiveDumpMinPredictedProfit() {
        if (!isReceiveDumpSuggestions()) {
            return null;
        }
        Long dumpMinPredictedProfit = getDumpMinPredictedProfit();
        return dumpMinPredictedProfit != null ? dumpMinPredictedProfit : DEFAULT_DUMP_MIN_PROFIT;
    }

    public synchronized void setReceiveDumpSuggestions(boolean receiveDumpSuggestions) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setReceiveDumpSuggestions(receiveDumpSuggestions);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized boolean isReceiveDumpSuggestions() {
        return osrsAccountPreferences.get().isReceiveDumpSuggestions();
    }

    public synchronized void setTimeBasedAbortEnabled(boolean enabled) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setTimeBasedAbortEnabled(enabled);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized boolean isTimeBasedAbortEnabled() {
        return osrsAccountPreferences.get().isTimeBasedAbortEnabled();
    }

    public synchronized void setTimeBasedAbortMinutes(int minutes) {
        AccountSuggestionPreferences preferences = osrsAccountPreferences.get();
        preferences.setTimeBasedAbortMinutes(minutes > 0 ? minutes : DEFAULT_TIME_BASED_ABORT_MINUTES);
        osrsAccountPreferences.updateAndPersist(preferences);
    }

    public synchronized int getTimeBasedAbortMinutes() {
        int m = osrsAccountPreferences.get().getTimeBasedAbortMinutes();
        return m > 0 ? m : DEFAULT_TIME_BASED_ABORT_MINUTES;
    }

    public synchronized void setBlockedItems(Set<Integer> blockedItems) {
        List<Integer> toUnblock = cachedPreferences.blockedItemIds.stream().filter(i -> !blockedItems.contains(i)).collect(Collectors.toList());
        List<Integer> toBlock = blockedItems.stream().filter(i -> !cachedPreferences.blockedItemIds.contains(i)).collect(Collectors.toList());
        Consumer<ProfileSuggestionPreferences> update = (s) -> {
            s.blockedItemIds.removeIf(toUnblock::contains);
            toBlock.forEach(i -> {
                if(!s.blockedItemIds.contains(i)) {
                    s.blockedItemIds.add(i);
                }
            });
        };
        log.debug("blocking {}, unblocking {}", toBlock, toUnblock);
        update.accept(cachedPreferences);
        executorService.submit(() -> updateProfile(selectedProfile, update));
    }

    public synchronized void blockItem(int itemId) {
        Consumer<ProfileSuggestionPreferences> update = (s) -> {
            if(!s.blockedItemIds.contains(itemId)) {
                s.blockedItemIds.add(itemId);
            }
        };
        update.accept(cachedPreferences);
        executorService.submit(() -> updateProfile(selectedProfile, update));
        log.debug("blocked item {}", itemId);
    }

    public synchronized List<Integer> blockedItems() {
        return cachedPreferences.getBlockedItemIds();
    }

    public synchronized boolean isDefaultProfileSelected() {
        return defaultProfile.equals(selectedProfile);
    }

    public synchronized String getCurrentProfile() {
        return toDisplayName(selectedProfile);
    }

    public synchronized void setCurrentProfile(String name) {
        selectedProfile = fromDisplayName(name);
        loadCurrentProfile();
    }

    public synchronized void addProfile(String name) throws IOException {
        Filepath p;
        try {
            p = profilePath(name);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid profile name: " + name, e);
        }
        createProfileFile(p);
        availableProfiles.add(p);
        selectedProfile = p;
        loadCurrentProfile();
    }

    private synchronized void updateProfile(Filepath profile, Consumer<ProfileSuggestionPreferences> changes) {
        Filepath lockFile = sibling(profile, ".lock");
        Filepath tmpFile = sibling(profile, ".tmp");
        try {
            ProfileSuggestionPreferences preferences;
            if (profile.exists()) {
                preferences = gson.fromJson(Persistance.readString(profile), ProfileSuggestionPreferences.class);
            } else {
                preferences = new ProfileSuggestionPreferences();
            }
            changes.accept(preferences);
            String toWrite = gson.toJson(preferences);
            try (FileChannel lockChannel = lockFile.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock l = lockChannel.lock()) {
                tmpFile.write(toWrite);
                tmpFile.moveTo(profile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                lockFile.deleteIfExists();
                tmpFile.deleteIfExists();
            }
            if (profile.equals(selectedProfile)) {
                cachedPreferences = preferences;
            }
        } catch (IOException e) {
            log.warn("error saving preferences json file {}", profile, e);
        }
    }

    private synchronized void loadCurrentProfile() {
        try {
            if (selectedProfile.exists()) {
                cachedPreferences = gson.fromJson(Persistance.readString(selectedProfile), ProfileSuggestionPreferences.class);
            } else {
                cachedPreferences = new ProfileSuggestionPreferences();
            }
        } catch (IOException e) {
            log.error("reading profile {}", selectedProfile, e);
        }
    }

    private synchronized void loadAvailableProfiles() {
        if (!dataDir.exists()) {
            availableProfiles = new ArrayList<>();
            availableProfiles.add(defaultProfile);
            return;
        }
        try (Stream<Filepath> paths = dataDir.walk(1)) {
            availableProfiles = paths
                    .filter(p -> p.getFileName().endsWith(PROFILE_SUFFIX))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            availableProfiles = new ArrayList<>();
            availableProfiles.add(defaultProfile);
            log.error("loading available profiles", e);
        }
    }

    private String toDisplayName(Filepath p ) {
        return p == null ? null : p.getFileName().replaceAll("\\.profile\\.json$", "");
    }

    private Filepath fromDisplayName(String name) {
        return profilePath(name);
    }

    private Filepath profilePath(String name) {
        return dataDir.joinSegment(name + PROFILE_SUFFIX);
    }

    private Filepath sibling(Filepath profile, String suffix) {
        return dataDir.joinSegment(profile.getFileName() + suffix);
    }

    public synchronized void deleteSelectedProfile() throws IOException {
        Filepath lockFile = sibling(selectedProfile, ".lock");
        try (FileChannel lockChannel = lockFile.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock l = lockChannel.lock()) {
            selectedProfile.delete();
        } finally {
            lockFile.deleteIfExists();
        }
        selectedProfile = defaultProfile;
        loadCurrentProfile();
        loadAvailableProfiles();
    }

    private void createProfileFile(Filepath profile) throws IOException {
        Filepath lockFile = sibling(profile, ".lock");
        Filepath tmpFile = sibling(profile, ".tmp");
        String toWrite = "{}";
        try (FileChannel lockChannel = lockFile.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock l = lockChannel.lock()) {
            tmpFile.write(toWrite);
            tmpFile.moveTo(profile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            lockFile.deleteIfExists();
        }
    }

}
