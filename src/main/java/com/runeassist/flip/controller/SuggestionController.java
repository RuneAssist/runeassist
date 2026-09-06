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


    private MainPanel mainPanel;
    private RuneAssistPanel runeAssistPanel;
    private SuggestionPanel suggestionPanel;

    public void skipSuggestion() {
        if (accountStatusManager.skipCurrentSuggestion()) {
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
        if (pausedManager.isPaused()) {
            pausedManager.setPaused(false);
            suggestionManager.setSuggestionNeeded(true);
            suggestionPanel.refresh();
        } else {
            pausedManager.setPaused(true);
            highlightController.removeAll();
            suggestionPanel.refresh();
        }
    }

    void onGameTick() {
        if (accountStatusManager.releaseStaleOwnedModify(
                client.getGrandExchangeOffers(), grandExchange.isSlotOpen())) {
            markGhostModifyActioned();
            suggestionManager.setSuggestionNeeded(true);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
        }
        if(suggestionManager.isSuggestionRequestInProgress() || suggestionManager.isGraphDataReadingInProgress()) {
            return;
        }
        // Collect-button race can leave uncollectedManager falsely non-empty.
        if(isUncollectedOutOfSync()) {
            log.warn("uncollected is out of sync, it thinks there are items to collect but the GE is open and the Collect button not visible");
            uncollectedManager.clearAllUncollected(osrsLoginManager.getAccountHash());
            suggestionManager.setSuggestionNeeded(true);
        }
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

    private boolean isUncollectedOutOfSync() {
        if (client.getTickCount() <= uncollectedManager.getLastUncollectedAddedTick() + 2) {
            return false;
        }
        if(!grandExchange.isHomeScreenOpen() || grandExchange.isCollectButtonVisible()) {
            return false;
        }
        return uncollectedManager.HasUncollected(osrsLoginManager.getAccountHash())
                || suggestionPanel.isCollectItemsSuggested();
    }

    public void getSuggestionAsync() {
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
        suggestionManager.setGraphDataReadingInProgress(!skipGraphData);
        Consumer<Suggestion> suggestionConsumer = (newSuggestion) ->
                handleSuggestionReceived(oldSuggestion, newSuggestion, accountStatus, !skipGraphData);
        suggestionPanel.refresh();
        log.debug("tick {} getting suggestion", client.getTickCount());
        runeAssistSource.getSuggestionAsync(suggestionConsumer, !skipGraphData);
    }

    void handleDumpSuggestion(Suggestion suggestion) {
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
        if (newSuggestion.isBuyDumpSuggestion() && config.dumpAlertSound()) {
            playDumpAlertSound();
        }
        suggestionManager.setSuggestion(newSuggestion);
        portfolioStateRS.updatePortfolioState(
                newSuggestion.getBankItems(),
                newSuggestion.getPortfolioItems(),
                accountStatus.getOffers(),
                accountStatus.getUncollected(),
                newSuggestion.getTimeIssued()
        );
        suggestionManager.setSuggestionRequestInProgress(false);
        log.debug("Received suggestion: {}", newSuggestion.toString());
        accountStatusManager.resetSkipSuggestion();
        offerManager.setOfferJustPlaced(false);
        suggestionPanel.refresh();
        showNotifications(oldSuggestion, newSuggestion, accountStatus);
        SwingUtilities.invokeLater(() -> {
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
            clientThread.invokeLater(gePreviousSearch::showSuggestedItemInSearch);
        }
        feedSuggestionGraph(newSuggestion, loadGraph);
    }

    /** Bundled compose graph and/or GET /v1/graph. */
    private void feedSuggestionGraph(Suggestion suggestion, boolean loadGraph) {
        if (!loadGraph) {
            suggestionManager.setGraphDataReadingInProgress(false);
            return;
        }
        suggestionManager.setGraphDataReadingInProgress(true);
        Consumer<Data> graphDataConsumer = (d) -> {
            SwingUtilities.invokeLater(() -> {
                if (flipDialogController.priceGraphPanel != null) {
                    flipDialogController.priceGraphPanel.setSuggestionPriceData(d);
                }
            });
            suggestionManager.setGraphDataReadingInProgress(false);
        };
        if (suggestion == null || suggestion.isWaitSuggestion() || suggestion.getItemId() <= 0) {
            Data d = new Data();
            if (suggestion != null && suggestion.isWaitSuggestion()) {
                d.fromWaitSuggestion = true;
            } else {
                d.loadingErrorMessage = "No graph data loaded for this item.";
            }
            graphDataConsumer.accept(d);
            return;
        }
        if (suggestion.getGraphData() != null) {
            graphDataConsumer.accept(suggestion.getGraphData());
            return;
        }
        final int itemId = suggestion.getItemId();
        apiRequestHandler.asyncGetRuneAssistGraph(itemId,
                graphDataConsumer,
                (Throwable err) -> {
                    log.debug("suggestion graph fetch failed for item {}: {}", itemId, err.toString());
                    Data d = new Data();
                    d.itemId = itemId;
                    d.loadingErrorMessage = "No graph data loaded for this item.";
                    graphDataConsumer.accept(d);
                });
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
        if (accountStatus.isCollectNeeded(newSuggestion, grandExchange.isSetupOfferOpen())) {
            clientThread.invokeLater(() -> showChatNotification("RuneAssist: Collect items"));
        }
        clientThread.invokeLater(() -> showChatNotification(newSuggestion.toMessage()));
    }

    private void showChatNotification(String message) {
        String chatMessage = new ChatMessageBuilder()
                .append(config.chatTextColor(), message)
                .build();
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage, "");
    }

}
