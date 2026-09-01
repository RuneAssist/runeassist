package com.osrsmcp;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * On-screen (in-game) helper for RuneAssist. Renders a small panel showing the latest
 * tip / nudge on the game canvas, so the player sees it without opening the side panel.
 * Display-only — it never automates any game input. Toggled by {@code screenOverlay}
 * (default ON); a tip auto-expires after {@link #TTL_MS}.
 */
@Singleton
public class RuneAssistOverlay extends Overlay
{
    private static final long  TTL_MS = 25_000L;
    private static final Color ACCENT = new Color(124, 138, 255); // RuneAssist indigo
    private static final int   WRAP   = 34; // chars per line

    private final PanelComponent panel = new PanelComponent();
    private final OsrsMcpConfig config;

    @Inject private TaskService taskService;

    private volatile String tip;
    private volatile long   tipAt;

    @Inject
    RuneAssistOverlay(OsrsMcpConfig config)
    {
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    /** Show a tip on screen (also called by NudgeService so nudges surface in-game). */
    public void setTip(String t)
    {
        tip = t;
        tipAt = System.currentTimeMillis();
    }

    public void clear() { tip = null; }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.screenOverlay()) return null;
        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(190, 0));
        boolean any = false;

        // Transient tip / nudge.
        String t = tip;
        if (t != null && System.currentTimeMillis() - tipAt <= TTL_MS)
        {
            panel.getChildren().add(TitleComponent.builder().text("RuneAssist").color(ACCENT).build());
            for (String line : wrap(t, WRAP))
                panel.getChildren().add(LineComponent.builder().left(line).build());
            any = true;
        }

        // Persistent active goals.
        List<String> goals = taskService != null ? taskService.activeTexts(3) : java.util.Collections.emptyList();
        if (!goals.isEmpty())
        {
            panel.getChildren().add(TitleComponent.builder().text("Goals").color(ACCENT).build());
            for (String goal : goals)
                for (String line : wrap("- " + goal, WRAP))
                    panel.getChildren().add(LineComponent.builder().left(line).build());
            any = true;
        }

        return any ? panel.render(g) : null;
    }

    private static List<String> wrap(String s, int width)
    {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split("\\s+"))
        {
            if (cur.length() + word.length() + 1 > width && cur.length() > 0)
            {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
