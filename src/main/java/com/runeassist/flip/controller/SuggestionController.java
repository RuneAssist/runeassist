package com.runeassist.flip.controller;

import com.runeassist.flip.config.FlippingCopilotConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.CopilotLoginRS;
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

    // dependencies
    private final PausedManager pausedManager;
    private final Client client;
    private final AudioPlayer audioPlayer;
    private final OsrsLoginManager osrsLoginManager;
    private final HighlightController highlightController;
    private final GrandExchange grandExchange;
    private final ApiRequestHandler apiRequestHandler;
    private final Notifier notifier;
    private final OfferManager offerManager;
    private final CopilotLoginRS copilotLoginRS;
    private final ClientThread clientThread;
    private final FlippingCopilotConfig config;
    private final SuggestionManager suggestionManager;
    private final AccountStatusManager accountStatusManager;
    private final GrandExchangeUncollectedManager uncollectedManager;
    private final PortfolioStateRS portfolioStateRS;
    private final FlipsDialogController flipDialogController;
    private final GePreviousSearch gePreviousSearch;
    // RuneAssist fork: our local suggestion source replaces FC's backend.
    private final com.runeassist.flip.RuneAssistSuggestionSource runeAssistSource;
    private final com.osrsmcp.TelemetryService telemetry;


    private MainPanel mainPanel;
    private LoginPanel loginPanel;
    private CopilotPanel copilotPanel;
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
        // There is a race condition when the collect button is hit at the same time as offers fill.
        // In such a case we can end up with the uncollectedManager falsely thinking there is items to collect.
        // We identify if this has happened here by checking if the collect button is actually visible.
        if(isUncollectedOutOfSync()) {
            log.warn("uncollected is out of sync, it thinks there are items to collect but the GE is open and the Collect button not visible");
            uncollectedManager.clearAllUncollected(osrsLoginManager.getAccountHash());
            suggestionManager.setSuggestionNeeded(true);
        }
        // on initial login the state of the GE offers isn't correct we need to wait a couple ticks before requesting a suggestion
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
        // Dump alerts highlight Back while a sell setup is open. Leaving that
        // screen used to look like "account state changed" and fetch a SELL of
        // inventory, overwriting the dump. Hold until Confirm or Skip.
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
                // The "very out of date" path exists to un-freeze a ghost editor (left open
                // with nothing behind it) so the card doesn't lock up forever -- see
                // isModifyInProgress's comment. But a live offer behind the open slot (e.g.
                // the player manually re-listing leftover stock after a partial fill/cancel,
                // unrelated to any suggestion) means they're actively using this screen; do
                // not yank the card to an unrelated item's suggestion out from under them.
                return false;
            }
        }

        return suggestionManager.isSuggestionNeeded() || suggestionManager.suggestionOutOfDate();
    }

    /**
     * Click-to-modify / offer editor open for the current MODIFY card. Do not fetch
     * a replacement (the 60s "very out of date" path used to bypass isSlotOpen and
     * LocalSuggestionEngine then saw the cancelled slot as empty and emitted BUY).
     * A leftover lock with the editor closed (logout, hop, GE home) must not freeze
     * the card — empty slots should get BUY.
     */
    private boolean isModifyInProgress(Suggestion p) {
        if (p != null && p.actionedTick != -1 && p.actionedTick <= client.getTickCount()) {
            return false;
        }
        return isEditorOnModify(p);
    }

    /**
     * Set up offer is open for this modify — including after cancel-then-relist,
     * when the slot is EMPTY and {@code boxId} may be stale. Used so the panel
     * and price highlight are not dropped as a ghost.
     */
    private boolean isEditorOnModify(Suggestion p) {
        if (!grandExchange.isSlotOpen()) {
            return false;
        }
        int open = grandExchange.getOpenSlot();
        int currentItem = grandExchange.getCurrentItemId();
        AccountStatusManager.OwnedModify owned = accountStatusManager.getOwnedModify();
        if (owned != null && owned.itemId > 0) {
            if (slotIsForOwnedModify(open, currentItem, owned)) {
                return true;
            }
        }
        if (p != null && p.isModifySuggestion()) {
            return slotIsForSuggestionModify(open, currentItem, p);
        }
        return false;
    }

    private boolean slotIsForOwnedModify(int open, int currentItem, AccountStatusManager.OwnedModify owned) {
        if (open < 0 || owned == null) {
            return false;
        }
        if (ModifyStep.editorMatches(open, currentItem, owned.itemId, owned.slot)) {
            return true;
        }
        return liveOfferItemId(open) == owned.itemId;
    }

    private boolean slotIsForSuggestionModify(int open, int currentItem, Suggestion p) {
        if (open < 0 || p == null) {
            return false;
        }
        if (ModifyStep.editorMatches(open, currentItem, p.getItemId(), p.getBoxId())) {
            return true;
        }
        return liveOfferItemId(open) == p.getItemId();
    }

    /**
     * Filling BUYING/SELLING only. CANCELLED_* still reports an itemId after
     * modify cancels, which used to block a replacement fetch and leave the
     * panel stuck on "Getting the next flip…".
     */
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

    /** Stale MODIFY card: allow replacement and do not re-arm the lock on the next slot click. */
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
        if(uncollectedManager.HasUncollected(osrsLoginManager.getAccountHash())) {
            return true;
        }
        if(suggestionPanel.isCollectItemsSuggested()) {
            return true;
        }
        return false;
    }

    public void getSuggestionAsync() {
        if (suggestionManager.isSuggestionRequestInProgress()) {
            suggestionManager.setSuggestionNeeded(true);
            return;
        }
        suggestionManager.setSuggestionNeeded(false);
        // RuneAssist fork: no FC account required — only the OSRS login must be valid.
        if (!osrsLoginManager.isValidLoginState()) {
            suggestionManager.setSuggestionRefreshPending(false);
            if (suggestionPanel != null) {
                suggestionPanel.refresh();
            }
            return;
        }
        if (suggestionManager.isSuggestionRequestInProgress()) {
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
        Consumer<Suggestion> suggestionConsumer = (newSuggestion) -> handleSuggestionReceived(oldSuggestion, newSuggestion, accountStatus);
        Consumer<Data> graphDataConsumer = (d) -> {
            SwingUtilities.invokeLater(() -> {
                if (flipDialogController.priceGraphPanel != null) {
                    flipDialogController.priceGraphPanel.setSuggestionPriceData(d);
                }
            });
            suggestionManager.setGraphDataReadingInProgress(false);
        };
        Consumer<HttpResponseException> onFailure = (e) -> {
            suggestionManager.setSuggestion(null);
            suggestionManager.setSuggestionError(e);
            suggestionManager.setSuggestionRequestInProgress(false);
            suggestionManager.setGraphDataReadingInProgress(false);
            if (e.getResponseCode() == 401) {
                copilotLoginRS.clear();
                mainPanel.refresh();
                loginPanel.showLoginErrorMessage("Login timed out. Please log in again");
            } else {
                suggestionPanel.refresh();
            }
        };
        suggestionPanel.refresh();
        log.debug("tick {} getting suggestion", client.getTickCount());
        // RuneAssist fork: get the suggestion from our local scorer instead of FC's server.
        suggestionManager.setGraphDataReadingInProgress(false); // graph is served separately
        runeAssistSource.getSuggestionAsync(suggestionConsumer);
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
            handleSuggestionReceived(suggestionManager.getSuggestion(), suggestion, accountStatus);
        } else {
            log.info("discarding dump suggestion as no free slot");
        }
    }

    private synchronized void handleSuggestionReceived(Suggestion oldSuggestion, Suggestion newSuggestion, AccountStatus accountStatus) {
        if (!newSuggestion.isDumpAlert && !suggestionManager.isSuggestionRequestInProgress()) {
            // this is the edge case when a dump suggestion is received whilst a standard request is in progress
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
        suggestionManager.setSuggestionError(null);
        suggestionManager.setSuggestionRequestInProgress(false);
        log.debug("Received suggestion: {}", newSuggestion.toString());
        logSuggestionDecision(oldSuggestion, newSuggestion);
        accountStatusManager.resetSkipSuggestion();
        offerManager.setOfferJustPlaced(false);
        suggestionPanel.refresh();
        showNotifications(oldSuggestion, newSuggestion, accountStatus);
        if (!newSuggestion.isWaitSuggestion()) {
            SwingUtilities.invokeLater(() -> {
                if (flipDialogController.priceGraphPanel != null) {
                    flipDialogController.priceGraphPanel.newSuggestedItemId(
                            newSuggestion.getItemId(),
                            buildPriceLine(newSuggestion)
                    );
                }
            });
        } else {
            SwingUtilities.invokeLater(() -> {
                if (flipDialogController.priceGraphPanel != null) {
                    flipDialogController.priceGraphPanel.suggestedPriceLine = null;
                }
            });
        }
        if (client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 14) {
            clientThread.invokeLater(gePreviousSearch::showSuggestedItemInSearch);
        }
    }

    /**
     * While the user is acting on a MODIFY, a refresh that would switch the card to
     * BUY/SELL/MODIFY of a different item is dropped. Same-item MODIFY is allowed
     * (repriced quote). Editor closed / leftover lock must not keep a ghost card.
     */
    private boolean shouldKeepOwnedModify(Suggestion oldSuggestion, Suggestion newSuggestion) {
        if (oldSuggestion == null || !oldSuggestion.isModifySuggestion()) {
            return false;
        }
        if (oldSuggestion.actionedTick != -1) {
            return false;
        }
        if (!isModifyInProgress(oldSuggestion)) {
            return false;
        }
        if (newSuggestion == null) {
            return true;
        }
        if (newSuggestion.isModifySuggestion()
                && newSuggestion.getItemId() == oldSuggestion.getItemId()) {
            return false;
        }
        return true;
    }

    /**
     * MODIFY with no filling offer and the editor closed — a leftover lock after
     * logout/collect/empty slot. Do not show it; fetch BUY into empty slots.
     * Cancel-then-relist (Set up offer still showing this item) is not a ghost.
     */
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
            return new PriceLine(
                    suggestion.getPrice(),
                    "Suggested buy price",
                    false
            );
        }
        if (suggestion.isSellSuggestion()) {
            return new PriceLine(
                    suggestion.getPrice(),
                    "Suggested sell price",
                    true
            );
        }
        return null;
    }

    void showNotifications(Suggestion oldSuggestion, Suggestion newSuggestion, AccountStatus accountStatus) {
        if (shouldNotify(newSuggestion, oldSuggestion)) {
            String msg = newSuggestion.toMessage();
            if (config.enableTrayNotifications()) {
                notifier.notify(msg);
            }
            if (!copilotPanel.isShowing() && config.enableChatNotifications()) {
                showChatNotifications(newSuggestion, accountStatus);
            }
        }
    }

    static boolean shouldNotify(Suggestion newSuggestion, Suggestion oldSuggestion) {
        if (newSuggestion.isWaitSuggestion()) {
            return false;
        }
        if (oldSuggestion != null && newSuggestion.equals(oldSuggestion)) {
            return false;
        }
        return true;
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

    private void logSuggestionDecision(Suggestion oldSuggestion, Suggestion newSuggestion) {
        try {
            String rsn = osrsLoginManager.getLastDisplayName();
            if (oldSuggestion != null && newSuggestion != null
                    && !newSuggestion.equals(oldSuggestion)
                    && !oldSuggestion.isWaitSuggestion()
                    && oldSuggestion.actionedTick == -1) {
                telemetry.logSuggestionDecision(rsn, oldSuggestion, "ignored",
                    oldSuggestion.getPickSource());
            }
            if (newSuggestion != null
                    && (oldSuggestion == null || !newSuggestion.equals(oldSuggestion))) {
                String outcome = newSuggestion.isAbortSuggestion() ? "abort" : "shown";
                telemetry.logSuggestionDecision(rsn, newSuggestion, outcome,
                    newSuggestion.getPickSource());
            }
        } catch (RuntimeException e) {
            log.debug("suggestion_decision log failed", e);
        }
    }
}
