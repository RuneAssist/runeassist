package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.AccountLoginRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import com.runeassist.flip.ui.*;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import com.runeassist.flip.ui.graph.model.Data;
import com.runeassist.flip.ui.graph.model.PriceLine;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.Notifier;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.util.Objects;
import java.time.Instant;
import java.util.List;
import okhttp3.Call;
import java.util.function.Consumer;


@Slf4j
@Getter
@Setter
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SuggestionController {

    private static final String DUMP_ALERT_SOUND = "/alert-sound.wav";

    private final PausedManager pausedManager;
    private final Client client;
    private final AudioPlayer audioPlayer;
    private final OsrsLoginManager osrsLoginManager;
    private final HighlightController highlightController;
    private final GrandExchange grandExchange;
    private final Notifier notifier;
    private final OfferManager offerManager;
    private final AccountLoginRS accountLoginRS;
    private final ClientThread clientThread;
    private final RuneAssistConfig config;
    private final SuggestionManager suggestionManager;
    private final AccountStatusManager accountStatusManager;
    private final GrandExchangeUncollectedManager uncollectedManager;
    private final PortfolioStateRS portfolioStateRS;
    private final FlipsDialogController flipDialogController;
    private final GePreviousSearch gePreviousSearch;
    private final com.runeassist.flip.RuneAssistSuggestionSource runeAssistSource;
    private final ApiRequestHandler apiRequestHandler;
    private final FlipHistorySyncService flipHistorySyncService;


    private MainPanel mainPanel;
    private RuneAssistPanel runeAssistPanel;
    private SuggestionPanel suggestionPanel;
    private final TradingContext tradingContext = new TradingContext();
    private Call suggestionGraphCall;
    private int suggestionGraphItemId;
    private long graphRequestSequence;
    private Data lastSuggestionGraph;
    private long lastSuggestionGraphAt;
    private long portfolioUpdateSequence;

    public void skipSuggestion() {
        clientThread.invokeLater(this::skipSuggestionOnClientThread);
    }

    private void skipSuggestionOnClientThread() {
        if (!syncTradingContext()) return;
        Suggestion current = suggestionManager.getSuggestion();
        if (accountStatusManager.skipCurrentSuggestion()) {
            flipHistorySyncService.reportSuggestionOutcome(current, "skip");
            clientThread.invokeLater(() -> {
                if (!suggestionManager.isSuggestionRequestInProgress()) {
                    getSuggestionAsync();
                }
            });
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
        }
    }

    public void togglePause() {
        // Account identity, GE widgets and suggestion mutations belong to the client thread.
        clientThread.invokeLater(() -> {
            pausedManager.setPaused(!pausedManager.isPaused());
            syncTradingContext();
            if (suggestionPanel != null) suggestionPanel.refresh();
        });
    }

    void onGameTick() {
        if (!syncTradingContext()) return;
        if (accountStatusManager.releaseStaleOwnedModify(
                client.getGrandExchangeOffers(), grandExchange.isSlotOpen())) {
            markGhostModifyActioned();
            suggestionManager.setSuggestionNeeded(true);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
        }
        if(suggestionManager.isSuggestionRequestInProgress()) {
            return;
        }
        reconcileUncollected();
        if (osrsLoginManager.hasJustLoggedIn()) {
            return;
        }
        if (shouldFetchNewSuggestion()) {
            getSuggestionAsync();
        }
    }

    private boolean shouldFetchNewSuggestion() {
        if (client.getTickCount() < suggestionManager.suggestionsDelayedUntil) {
            return false;
        }
        Suggestion p = suggestionManager.getSuggestion();
        // Hold dump alerts until Confirm/Skip so backing out of sell setup can't overwrite them.
        if (p != null && p.isRecentUnActionedDumpAlert()) {
            return false;
        }
        if (isModifyInProgress(p)) {
            return false;
        }
        if (grandExchange.isSlotOpen()) {
            if (!suggestionActionedOrVeryOutOfDate(p)) {
                return false;
            }
            if (liveOfferItemId(grandExchange.getOpenSlot()) != -1) {
                // Live offer behind open slot: don't yank the card while the player is using it.
                return false;
            }
        }

        return suggestionManager.isSuggestionNeeded() || suggestionManager.suggestionOutOfDate();
    }

    /** Offer editor open for the current MODIFY card — do not fetch a replacement. */
    private boolean isModifyInProgress(Suggestion p) {
        if (p != null && p.actionedTick != -1 && p.actionedTick <= client.getTickCount()) {
            return false;
        }
        return isEditorOnModify(p);
    }

    private boolean isEditorOnModify(Suggestion p) {
        if (!grandExchange.isSlotOpen()) {
            return false;
        }
        int open = grandExchange.getOpenSlot();
        int currentItem = grandExchange.getCurrentItemId();
        AccountStatusManager.OwnedModify owned = accountStatusManager.getOwnedModify();
        if (owned != null && owned.itemId > 0 && slotMatchesModify(open, currentItem, owned.itemId, owned.slot)) {
            return true;
        }
        return p != null && p.isModifySuggestion()
                && slotMatchesModify(open, currentItem, p.getItemId(), p.getBoxId());
    }

    private boolean slotMatchesModify(int open, int currentItem, int itemId, int boxId) {
        if (open < 0 || itemId <= 0) {
            return false;
        }
        if (ModifyStep.editorMatches(open, currentItem, itemId, boxId)) {
            return true;
        }
        return liveOfferItemId(open) == itemId;
    }

    /** Filling BUYING/SELLING only — CANCELLED_* still reports itemId after modify cancel. */
    private int liveOfferItemId(int slot) {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || slot < 0 || slot >= offers.length || offers[slot] == null) {
            return -1;
        }
        GrandExchangeOfferState st = offers[slot].getState();
        if (st != GrandExchangeOfferState.BUYING && st != GrandExchangeOfferState.SELLING) {
            return -1;
        }
        int itemId = offers[slot].getItemId();
        return itemId > 0 ? itemId : -1;
    }

    private void markGhostModifyActioned() {
        Suggestion p = suggestionManager.getSuggestion();
        if (p != null && p.isModifySuggestion() && p.actionedTick == -1) {
            p.actionedTick = 0;
        }
    }

    private boolean suggestionActionedOrVeryOutOfDate(Suggestion p) {
        if (p == null || p.isWaitSuggestion()) {
            return true;
        }
        if (p.actionedTick != -1 && p.actionedTick < client.getTickCount()) {
            return true;
        }
        return suggestionManager.suggestionVeryOutOfDate();
    }

    void reconcileUncollected() {
        if (client.getTickCount() <= uncollectedManager.getLastUncollectedAddedTick() + 2) {
            return;
        }
        if(!grandExchange.isHomeScreenOpen() || grandExchange.isCollectButtonVisible()) {
            return;
        }
        Long accountHash = osrsLoginManager.getAccountHash();
        boolean staleItems = uncollectedManager.HasUncollected(accountHash);
        boolean staleCard = suggestionPanel != null && suggestionPanel.isCollectItemsSuggested();
        if (!staleItems && !staleCard) {
            return;
        }
        if (staleItems) {
            log.warn("Clearing stale uncollected state: GE home is open without a Collect button");
            uncollectedManager.clearAllUncollected(accountHash);
        }
        if (staleCard) {
            suggestionPanel.clearCollectSuggestion();
        }
        suggestionManager.setSuggestionNeeded(true);
    }

    public void getSuggestionAsync() {
        if (!syncTradingContext()) return;
        if (suggestionManager.isSuggestionRequestInProgress()) {
            suggestionManager.setSuggestionNeeded(true);
            return;
        }
        suggestionManager.setSuggestionNeeded(false);
        if (!osrsLoginManager.isValidLoginState()) {
            suggestionManager.setSuggestionRefreshPending(false);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
            return;
        }
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (accountStatus == null) {
            suggestionManager.setSuggestionRefreshPending(false);
            suggestionManager.setSuggestionNeeded(true);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
            return;
        }
        Suggestion oldSuggestion = suggestionManager.getSuggestion();
        if (oldSuggestion != null && oldSuggestion.isRecentUnActionedDumpAlert()) {
            suggestionManager.setSuggestionRefreshPending(false);
            return;
        }
        suggestionManager.setSuggestionRequestInProgress(true);
        suggestionManager.setSuggestionRefreshPending(false);
        boolean skipGraphData = config.lowDataMode();
        final long requestGeneration = tradingContext.generation();
        Consumer<Suggestion> suggestionConsumer = (newSuggestion) ->
                receiveForContext(requestGeneration, oldSuggestion, newSuggestion, accountStatus, !skipGraphData);
        suggestionPanel.refresh();
        log.debug("tick {} getting suggestion", client.getTickCount());
        // Graphs are optional UI enrichment, never part of the compose critical path.
        runeAssistSource.getSuggestionAsync(suggestionConsumer, false);
    }

    void handleDumpSuggestion(Suggestion suggestion) {
        if (!syncTradingContext()) return;
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (accountStatus == null) {
            log.info("discarding dump suggestion as account status null");
            return;
        }
        Suggestion s = suggestionManager.getSuggestion();
        if(s != null && s.isDumpAlert && s.actionedTick == -1) {
            log.info("discarding dump suggestion as already processing dump suggestion");
            return;
        }
        if (accountStatus.emptySlotExists()) {
            handleSuggestionReceived(suggestionManager.getSuggestion(), suggestion, accountStatus,
                    !config.lowDataMode());
        } else {
            log.info("discarding dump suggestion as no free slot");
        }
    }

    void handleDumpSuggestion(Suggestion suggestion, long streamGeneration) {
        syncTradingContext();
        if (tradingContext.accepts(streamGeneration)) handleDumpSuggestion(suggestion);
    }

    /** Invalidate only suggestion/UI work; offer observation and history sync keep running. */
    boolean syncTradingContext() {
        boolean valid = osrsLoginManager.isValidLoginState();
        Suggestion current = suggestionManager.getSuggestion();
        boolean preserveDecantGuidance = valid && !pausedManager.isPaused()
                && tradingContext.sameAccount(osrsLoginManager.getAccountHash())
                && !grandExchange.isOpen() && current != null && current.isDecantSuggestion();
        boolean changed = tradingContext.update(osrsLoginManager.getAccountHash(), valid,
                valid && grandExchange.isOpen(), valid && pausedManager.isPaused());
        if (changed) {
            clearContextSuggestion();
            // Decanting itself requires leaving the GE. Keep only the already
            // issued local instruction; do not poll or announce new trades.
            if (preserveDecantGuidance) suggestionManager.setSuggestion(current);
            suggestionManager.setSuggestionNeeded(tradingContext.canRequest());
            if (suggestionPanel != null) suggestionPanel.refresh();
        }
        return tradingContext.canRequest();
    }

    void onSessionEnded() {
        tradingContext.invalidate();
        clearContextSuggestion();
    }

    private void clearContextSuggestion() {
        cancelSuggestionGraph();
        lastSuggestionGraph = null;
        suggestionManager.setSuggestion(null);
        suggestionManager.setSuggestionRequestInProgress(false);
        suggestionManager.setGraphDataReadingInProgress(false);
        suggestionManager.setSuggestionRefreshPending(false);
        suggestionManager.setSuggestionNeeded(false);
        suggestionManager.suggestionsDelayedUntil = 0;
        suggestionManager.setLastFailureAt(null);
        highlightController.removeAll();
    }

    void receiveForContext(long generation, Suggestion oldSuggestion, Suggestion newSuggestion,
                           AccountStatus accountStatus, boolean loadGraph) {
        syncTradingContext();
        // A stale reply must not release a newer request, change another account's
        // skip/protection state, display a card or emit a notification.
        if (!tradingContext.accepts(generation)) return;
        handleSuggestionReceived(oldSuggestion, newSuggestion, accountStatus, loadGraph);
    }

    private synchronized void handleSuggestionReceived(Suggestion oldSuggestion, Suggestion newSuggestion,
                                                       AccountStatus accountStatus, boolean loadGraph) {
        if (!newSuggestion.isDumpAlert && !suggestionManager.isSuggestionRequestInProgress()) {
            log.info("discarding suggestion as not dump alert and no request in progress {}", newSuggestion);
            return;
        }
        if (isGhostModify(newSuggestion)) {
            log.info("discarding ghost MODIFY item {} — no live offer, editor closed",
                    newSuggestion.getItemId());
            accountStatusManager.clearOwnedModify();
            markGhostModifyActioned();
            Suggestion current = suggestionManager.getSuggestion();
            if (current != null && current.isModifySuggestion()) {
                suggestionManager.setSuggestion(null);
            }
            suggestionManager.setSuggestionRequestInProgress(false);
            suggestionManager.setGraphDataReadingInProgress(false);
            suggestionManager.setSuggestionNeeded(true);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
            getSuggestionAsync();
            return;
        }
        if (shouldKeepOwnedModify(oldSuggestion, newSuggestion)) {
            log.info("keeping in-progress MODIFY item {} slot {}; discarding {}",
                    oldSuggestion.getItemId(), oldSuggestion.getBoxId(), newSuggestion);
            suggestionManager.setSuggestionRequestInProgress(false);
            suggestionManager.setGraphDataReadingInProgress(false);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
            return;
        }
        if (!isSellAvailableNow(newSuggestion)) {
            Suggestion wait = new Suggestion();
            wait.setType(SuggestionType.WAIT);
            wait.setMessage("Waiting for inventory update");
            suggestionManager.setSuggestion(wait);
            suggestionManager.setSuggestionRequestInProgress(false);
            suggestionManager.setGraphDataReadingInProgress(false);
            suggestionManager.setSuggestionNeeded(false);
            if (suggestionPanel != null) suggestionPanel.refresh();
            return;
        }
        if (newSuggestion.isBuySuggestion() || newSuggestion.isSellSuggestion()) {
            accountStatusManager.protectListing(newSuggestion.getItemId());
        }
        if (newSuggestion.isAbortSuggestion() && newSuggestion.getItemId() > 0) {
            accountStatusManager.skipItem(newSuggestion.getItemId());
        }
        if (newSuggestion.isBuyDumpSuggestion() && config.dumpAlertSound()) {
            playDumpAlertSound();
        }
        if (oldSuggestion != null && oldSuggestion.actionedTick == -1
                && oldSuggestion.getServerSuggestionId() != null
                && !oldSuggestion.getServerSuggestionId().isEmpty()
                && !Objects.equals(oldSuggestion.getServerSuggestionId(), newSuggestion.getServerSuggestionId()))
        {
            flipHistorySyncService.reportSuggestionOutcome(oldSuggestion, "superseded");
        }
        suggestionManager.setSuggestion(newSuggestion);
        suggestionManager.setLastFailureAt(com.runeassist.flip.RuneAssistSuggestionSource.isUnavailableWait(newSuggestion)
                ? Instant.now() : null);
        final long acceptedGeneration = tradingContext.generation();
        if (newSuggestion.getPortfolioItems() != null) portfolioStateRS.updatePortfolioState(
                newSuggestion.getBankItems(),
                newSuggestion.getPortfolioItems(),
                accountStatus.getOffers(),
                accountStatus.getUncollected(),
                newSuggestion.getTimeIssued(),
                () -> tradingContext.accepts(acceptedGeneration) && suggestionManager.getSuggestion() == newSuggestion
        );
        suggestionManager.setSuggestionRequestInProgress(false);
        log.debug("Received suggestion: {}", newSuggestion.toString());
        accountStatusManager.resetSkipSuggestion();
        offerManager.setOfferJustPlaced(false);
        suggestionPanel.refresh();
        showNotifications(oldSuggestion, newSuggestion, accountStatus);
        SwingUtilities.invokeLater(() -> {
            if (!tradingContext.accepts(acceptedGeneration) || suggestionManager.getSuggestion() != newSuggestion) return;
            if (flipDialogController.priceGraphPanel == null) {
                return;
            }
            if (!newSuggestion.isWaitSuggestion()) {
                flipDialogController.priceGraphPanel.newSuggestedItemId(
                        newSuggestion.getItemId(),
                        buildPriceLine(newSuggestion)
                );
            } else {
                flipDialogController.priceGraphPanel.suggestedPriceLine = null;
            }
        });
        if (client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 14) {
            clientThread.invokeLater(() -> {
                if (syncTradingContext() && tradingContext.accepts(acceptedGeneration)
                        && suggestionManager.getSuggestion() == newSuggestion) gePreviousSearch.showSuggestedItemInSearch();
            });
        }
        feedSuggestionGraph(newSuggestion, loadGraph);
        runeAssistSource.getPortfolioItemsAsync((items, snapshotAt) -> receivePortfolioForContext(
                acceptedGeneration, newSuggestion, items, snapshotAt));
    }

    void receivePortfolioForContext(long generation, Suggestion suggestion, List<Suggestion.PortfolioItem> items, Instant snapshotAt) {
        syncTradingContext();
        // The source separately verifies unchanged held/offer snapshots. A newer
        // routine card alone must not starve a slow portfolio quote forever.
        if (!tradingContext.accepts(generation)) return;
        AccountStatus status = accountStatusManager.getAccountStatus();
        if (status == null) return;
        final long sequence = ++portfolioUpdateSequence;
        portfolioStateRS.updatePortfolioState(suggestion.getBankItems(), items,
                status.getOffers(), status.getUncollected(), snapshotAt,
                () -> tradingContext.accepts(generation) && sequence == portfolioUpdateSequence);
    }

    /** Bundled compose graph and/or GET /v1/graph. */
    void feedSuggestionGraph(Suggestion suggestion, boolean loadGraph) {
        if (!loadGraph) {
            cancelSuggestionGraph();
            return;
        }
        if (suggestion == null || suggestion.isWaitSuggestion() || suggestion.getItemId() <= 0) {
            cancelSuggestionGraph();
            Data d = new Data();
            if (suggestion != null && suggestion.isWaitSuggestion()) {
                d.fromWaitSuggestion = true;
            } else {
                d.loadingErrorMessage = "No graph data loaded for this item.";
            }
            displaySuggestionGraph(d, tradingContext.generation(), suggestion == null ? 0 : suggestion.getItemId());
            return;
        }
        if (suggestion.getGraphData() != null) {
            cancelSuggestionGraph();
            displaySuggestionGraph(suggestion.getGraphData(), tradingContext.generation(), suggestion.getItemId());
            return;
        }
        final int itemId = suggestion.getItemId();
        // A routine refresh of the same item must not cancel/restart a slow graph.
        if (suggestionGraphCall != null && suggestionGraphItemId == itemId) return;
        cancelSuggestionGraph();
        if (lastSuggestionGraph != null && lastSuggestionGraph.itemId == itemId
                && System.currentTimeMillis() - lastSuggestionGraphAt < 60_000L) {
            displaySuggestionGraph(lastSuggestionGraph, tradingContext.generation(), itemId);
            return;
        }
        suggestionGraphItemId = itemId;
        suggestionManager.setGraphDataReadingInProgress(true);
        final long graphGeneration = tradingContext.generation();
        final long sequence = graphRequestSequence;
        final long startedAt = System.nanoTime();
        Consumer<Data> graphDataConsumer = d -> clientThread.invokeLater(() -> {
            if (sequence != graphRequestSequence || !tradingContext.accepts(graphGeneration)) return;
            suggestionGraphCall = null;
            suggestionManager.setGraphDataReadingInProgress(false);
            d.itemId = itemId;
            lastSuggestionGraph = d;
            lastSuggestionGraphAt = System.currentTimeMillis();
            log.debug("suggestion graph enrichment elapsedMs={}", (System.nanoTime() - startedAt) / 1_000_000L);
            displaySuggestionGraph(d, graphGeneration, itemId);
        });
        suggestionGraphCall = apiRequestHandler.asyncGetRuneAssistGraph(itemId,
                graphDataConsumer,
                (Throwable err) -> {
                    log.debug("suggestion graph fetch failed for item {}: {}", itemId, err.toString());
                    Data d = new Data();
                    d.itemId = itemId;
                    d.loadingErrorMessage = "No graph data loaded for this item.";
                    graphDataConsumer.accept(d);
                });
    }

    private void displaySuggestionGraph(Data data, long generation, int itemId) {
        SwingUtilities.invokeLater(() -> {
            Suggestion current = suggestionManager.getSuggestion();
            if (!tradingContext.accepts(generation) || current == null || current.getItemId() != itemId) return;
            if (flipDialogController.priceGraphPanel != null) flipDialogController.priceGraphPanel.setSuggestionPriceData(data);
        });
    }

    private void cancelSuggestionGraph() {
        graphRequestSequence++;
        if (suggestionGraphCall != null) suggestionGraphCall.cancel();
        suggestionGraphCall = null;
        suggestionManager.setGraphDataReadingInProgress(false);
    }

    /** Drop refreshes that would switch away from an in-progress MODIFY of a different item. */
    private boolean shouldKeepOwnedModify(Suggestion oldSuggestion, Suggestion newSuggestion) {
        if (oldSuggestion == null || !oldSuggestion.isModifySuggestion()) {
            return false;
        }
        if (oldSuggestion.actionedTick != -1 || !isModifyInProgress(oldSuggestion)) {
            return false;
        }
        if (newSuggestion == null) {
            return true;
        }
        return !(newSuggestion.isModifySuggestion()
                && newSuggestion.getItemId() == oldSuggestion.getItemId());
    }

    /** MODIFY with no filling offer and editor closed — leftover lock after logout/collect. */
    public boolean isGhostModify(Suggestion s) {
        if (s == null || !s.isModifySuggestion() || s.getItemId() <= 0) {
            return false;
        }
        if (isEditorOnModify(s)) {
            return false;
        }
        return ModifyStep.isGhost(true, s.getItemId(), false,
                grandExchange.hasFillingOffer(s.getItemId()));
    }

    /** Direct sales need current physical stock, not only historical purchases. */
    public boolean isSellAvailableNow(Suggestion suggestion) {
        if (suggestion == null || !suggestion.isSellSuggestion()) return true;
        if (suggestion.getType() == SuggestionType.MODIFY_SELL) {
            if (grandExchange.hasFillingSellOffer(suggestion.getItemId())) return true;
            // Cancel/collect guidance is allowed before the relist editor. Once
            // preparing a new offer, only physical inventory can back its quantity.
            if (!grandExchange.isSetupOfferOpen() && uncollectedManager.loadAllUncollected(
                    osrsLoginManager.getAccountHash()).getOrDefault(suggestion.getItemId(), 0L) > 0) return true;
        }
        return runeAssistSource.hasInventoryForSale(suggestion);
    }

    private void playDumpAlertSound() {
        try {
            audioPlayer.play(SuggestionController.class, DUMP_ALERT_SOUND, 0);
        } catch (Exception e) {
            log.warn("failed to play dump alert sound", e);
        }
    }

    private PriceLine buildPriceLine(Suggestion suggestion) {
        if (suggestion.isBuySuggestion()) {
            return new PriceLine(suggestion.getPrice(), "Suggested buy price", false);
        }
        if (suggestion.isSellSuggestion()) {
            return new PriceLine(suggestion.getPrice(), "Suggested sell price", true);
        }
        return null;
    }

    void showNotifications(Suggestion oldSuggestion, Suggestion newSuggestion, AccountStatus accountStatus) {
        if (!tradingContext.canRequest()) return;
        if (shouldNotify(newSuggestion, oldSuggestion)) {
            String msg = newSuggestion.toMessage();
            if (config.enableTrayNotifications()) {
                notifier.notify(msg);
            }
            if (!runeAssistPanel.isShowing() && config.enableChatNotifications()) {
                showChatNotifications(newSuggestion, accountStatus);
            }
        }
    }

    static boolean shouldNotify(Suggestion newSuggestion, Suggestion oldSuggestion) {
        if (newSuggestion.isWaitSuggestion()) {
            return false;
        }
        return oldSuggestion == null || !newSuggestion.equals(oldSuggestion);
    }

    private void showChatNotifications(Suggestion newSuggestion, AccountStatus accountStatus) {
        final long notificationGeneration = tradingContext.generation();
        final boolean collectNeeded = accountStatus.isCollectNeeded(newSuggestion, grandExchange.isSetupOfferOpen());
        clientThread.invokeLater(() -> {
            if (!syncTradingContext() || !tradingContext.accepts(notificationGeneration)
                    || suggestionManager.getSuggestion() != newSuggestion) return;
            if (collectNeeded) showChatNotification("RuneAssist: Collect items");
            showChatNotification(newSuggestion.toMessage());
        });
    }

    private void showChatNotification(String message) {
        String chatMessage = new ChatMessageBuilder()
                .append(config.chatTextColor(), message)
                .build();
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage, "");
    }

}
