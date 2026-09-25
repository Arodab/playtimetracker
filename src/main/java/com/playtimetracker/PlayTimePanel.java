package com.playtimetracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

@Slf4j
public class PlayTimePanel extends PluginPanel {
    private final static Color BACKGROUND_COLOR = ColorScheme.DARK_GRAY_COLOR;

    private final PlayTimePlugin plugin;
    private final JLabel sessionTime = new JLabel();
    private final JLabel dayTime = new JLabel();
    private final JLabel weekTime = new JLabel();
    private final JLabel weekAverage = new JLabel();
    private final JLabel monthTime = new JLabel();
    private final JLabel monthAverage = new JLabel();
    private final JLabel yearTime = new JLabel();
    private final JLabel yearAverage = new JLabel();
    private final JLabel totalTrackedTime = new JLabel();
    private final JLabel totalTrackedAverage = new JLabel();
    private final JLabel externalTime = new JLabel();
    private final JLabel totalTime = new JLabel();

    private final JLabel mobileTotal = new JLabel();
    private final JLabel mobileAverage = new JLabel();
    private final JLabel mobileShare = new JLabel();
    private final JLabel mobileTracked = new JLabel();
    private final JLabel mobilePreInstall = new JLabel();
    private final JLabel mobileInGame = new JLabel();

    private boolean shown = false;
    private final PlayTimeChart chart;
    private final JPanel chartHolder = new JPanel(new BorderLayout());

    public PlayTimePanel(final PlayTimePlugin plugin)
    {
        super(false);
        this.plugin = plugin;
        this.chart = new PlayTimeChart(plugin);
        setBackground(BACKGROUND_COLOR);
        setLayout(new BorderLayout());
    }

    public void showView()
    {
        updateTimes();
        if (shown) {
            return;
        }
        shown = true;

        final PluginErrorPanel errorPanel = new PluginErrorPanel();
        errorPanel.setBorder(new EmptyBorder(10, 25, 10, 25));
        errorPanel.setContent("Play Time Tracker", "Time played, per character");

        final JPanel stats = new JPanel();
        stats.setLayout(new BoxLayout(stats, BoxLayout.Y_AXIS));
        stats.setBackground(BACKGROUND_COLOR);
        stats.setBorder(new EmptyBorder(10, 12, 10, 12));

        addPrimary(stats, sessionTime);
        addPrimary(stats, dayTime);
        stats.add(Box.createVerticalStrut(10));
        addPrimary(stats, weekTime);
        addSub(stats, weekAverage);
        stats.add(Box.createVerticalStrut(10));
        addPrimary(stats, monthTime);
        addSub(stats, monthAverage);
        stats.add(Box.createVerticalStrut(10));
        addPrimary(stats, yearTime);
        addSub(stats, yearAverage);
        stats.add(Box.createVerticalStrut(10));
        addPrimary(stats, totalTrackedTime);
        addSub(stats, totalTrackedAverage);
        stats.add(Box.createVerticalStrut(10));
        addPrimary(stats, externalTime);
        addPrimary(stats, totalTime);

        chartHolder.setBackground(BACKGROUND_COLOR);
        chartHolder.setBorder(new EmptyBorder(12, 0, 0, 0));
        chartHolder.add(chart, BorderLayout.CENTER);
        chartHolder.setAlignmentX(Component.LEFT_ALIGNMENT);
        stats.add(chartHolder);

        // Mobile tab: everything the plugin knows that did NOT happen in this client.
        final JPanel mobile = new JPanel();
        mobile.setLayout(new BoxLayout(mobile, BoxLayout.Y_AXIS));
        mobile.setBackground(BACKGROUND_COLOR);
        mobile.setBorder(new EmptyBorder(10, 12, 10, 12));

        addPrimary(mobile, mobileTotal);
        addSub(mobile, mobileAverage);
        mobile.add(Box.createVerticalStrut(10));
        addPrimary(mobile, mobileShare);
        addSub(mobile, mobileTracked);
        mobile.add(Box.createVerticalStrut(10));
        addPrimary(mobile, mobilePreInstall);
        addSub(mobile, mobileInGame);
        mobile.add(Box.createVerticalStrut(12));

        final JLabel mobileNote = new JLabel("<html><body style='width:158px'>"
                + "Off-client time is the gap between the game's own Time Played and what this "
                + "plugin tracked - mobile, other clients, or the plugin switched off. It also "
                + "picks up logging in and loading, which the game counts and this plugin does "
                + "not, so expect a little even if you never leave the client. Reported to the "
                + "minute, because the game's total is. Needs 'Count time outside RuneLite' on."
                + "</body></html>");
        mobileNote.setForeground(Color.GRAY);
        mobileNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        mobile.add(mobileNote);

        final JScrollPane timeTab = wrapContainer(stats);
        final JScrollPane mobileTab = wrapContainer(mobile);
        // Do NOT hide the unselected tab: MaterialTabGroup.select swaps the display's children
        // with removeAll/add and never restores visibility, so a hidden tab stays blank forever.

        final JPanel tabContent = new JPanel(new BorderLayout());
        tabContent.setBackground(BACKGROUND_COLOR);
        tabContent.add(timeTab, BorderLayout.CENTER);

        final MaterialTabGroup tabGroup = new MaterialTabGroup(tabContent);
        tabGroup.setBorder(new EmptyBorder(4, 12, 0, 12));
        final MaterialTab timeTabButton = new MaterialTab("Play time", tabGroup, timeTab);
        final MaterialTab mobileTabButton = new MaterialTab("Mobile", tabGroup, mobileTab);
        tabGroup.addTab(timeTabButton);
        tabGroup.addTab(mobileTabButton);
        tabGroup.select(timeTabButton);

        final JButton exportButton = new JButton("Export daily CSV");
        exportButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        exportButton.addActionListener(e -> exportCsv());

        final JButton resetButton = new JButton("Reset session counter");
        resetButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        resetButton.addActionListener(e -> plugin.resetCounter());

        final JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.setBackground(BACKGROUND_COLOR);
        buttons.setBorder(new EmptyBorder(6, 12, 10, 12));
        buttons.add(exportButton);
        buttons.add(Box.createVerticalStrut(6));
        buttons.add(resetButton);

        final JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BACKGROUND_COLOR);
        header.add(errorPanel, BorderLayout.NORTH);
        header.add(tabGroup, BorderLayout.SOUTH);

        add(header, BorderLayout.NORTH);
        add(tabContent, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        revalidate();
        repaint();
    }

    private static void addPrimary(final JPanel panel, final JLabel label)
    {
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(3, 0, 3, 0));
        panel.add(label);
    }

    private static void addSub(final JPanel panel, final JLabel label)
    {
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 14, 3, 0));
        label.setForeground(Color.LIGHT_GRAY);
        panel.add(label);
    }

    public void updateTimes()
    {
        final boolean wantChart = plugin.getConfig().showChart();
        if (chartHolder.isVisible() != wantChart) {
            chartHolder.setVisible(wantChart);
        }
        if (wantChart) {
            chart.refresh();
        }

        if (plugin.getSessionTicks() == 0) {
            sessionTime.setText("Login for times to be displayed");
            dayTime.setText("");
            weekTime.setText("");
            weekAverage.setText("");
            monthTime.setText("");
            monthAverage.setText("");
            yearTime.setText("");
            yearAverage.setText("");
            totalTrackedTime.setText("");
            totalTrackedAverage.setText("");
            externalTime.setText("");
            totalTime.setText("");
            return;
        }

        updateMobile();

        final PlayTimeRecord rec = plugin.getCurrentRecord();
        sessionTime.setText("Session: " + (rec != null ? plugin.formatTicks(plugin.getSessionTicks()) : "?"));
        dayTime.setText("Today: " + (rec != null ? plugin.formatTicks(plugin.getTodayTicks()) : "?"));

        if (rec == null) {
            weekTime.setText("This week: ?");
            monthTime.setText("This month: ?");
            yearTime.setText("This year: ?");
            totalTrackedTime.setText("Total tracked: ?");
            externalTime.setText("External/mobile: ?");
            totalTime.setText("Total (in-game): ?");
            if (plugin.getConfig().showAverages()) {
                weekAverage.setText("avg/day this week: ?");
                monthAverage.setText("avg/day this month: ?");
                yearAverage.setText("avg/day this year: ?");
                totalTrackedAverage.setText("avg/day tracked: ?");
            } else {
                clearAverages();
            }
            return;
        }

        weekTime.setText("This week: " + plugin.formatTicks(plugin.getWeekTicks()));
        monthTime.setText("This month: " + plugin.formatTicks(plugin.getMonthTicks()));
        yearTime.setText("This year: " + plugin.formatTicks(plugin.getYearTicks()));
        totalTrackedTime.setText("Total tracked: " + plugin.formatTicks(plugin.getTrackedTicks()));
        externalTime.setText("External/mobile: " + plugin.formatTicks(plugin.getExternalSinceInstallTicks()));
        totalTime.setText("Total (in-game): " + plugin.formatTicks(plugin.getTotalTicks()));

        if (plugin.getConfig().showAverages()) {
            weekAverage.setText("avg/day this week: " + plugin.formatTicks(plugin.getWeekAvgTicks()));
            monthAverage.setText("avg/day this month: " + plugin.formatTicks(plugin.getMonthAvgTicks()));
            yearAverage.setText("avg/day this year: " + plugin.formatTicks(plugin.getYearAvgTicks()));
            totalTrackedAverage.setText("avg/day tracked: " + plugin.formatTicks(plugin.getTrackedAvgTicks()));
        } else {
            clearAverages();
        }
    }

    /** The Mobile tab. Driven off the same records, so it needs no state of its own. */
    private void updateMobile()
    {
        if (plugin.getCurrentPlayer() == null) {
            mobileTotal.setText("Off-client: log in to see");
            mobileAverage.setText("");
            mobileShare.setText("");
            mobileTracked.setText("");
            mobilePreInstall.setText("");
            mobileInGame.setText("");
            return;
        }

        if (!plugin.getConfig().countExternalTime()) {
            mobileTotal.setText("Off-client tracking is off");
            mobileAverage.setText("Enable 'Count time outside RuneLite'");
            mobileShare.setText("");
            mobileTracked.setText("");
            mobilePreInstall.setText("");
            mobileInGame.setText("");
            return;
        }

        final long external = plugin.getExternalSinceInstallTicks();
        if (external == 0) {
            mobileTotal.setText("Off-client: none detected");
            mobileAverage.setText("under a minute is not counted");
        } else {
            mobileTotal.setText("Off-client: " + plugin.formatTicks(external));
            mobileAverage.setText("avg/day: " + plugin.formatTicks(plugin.getExternalAvgTicks())
                    + "  (over " + plugin.getDaysSinceTrackingStarted() + "d)");
        }
        mobileShare.setText("Share of play: " + plugin.getExternalSharePercent() + "% off-client");
        mobileTracked.setText("in RuneLite: " + plugin.formatTicks(plugin.getTrackedTicks()));
        mobilePreInstall.setText("Before this plugin: " + plugin.formatTicks(plugin.getPreInstallTicks()));
        mobileInGame.setText("Game total: " + plugin.formatTicks(plugin.getTotalTicks()));
    }

    private void clearAverages()
    {
        weekAverage.setText("");
        monthAverage.setText("");
        yearAverage.setText("");
        totalTrackedAverage.setText("");
    }

    private void exportCsv()
    {
        final String csv = plugin.toDailyCsv();
        final String who = plugin.getCurrentPlayer() == null ? "playtime" : plugin.getCurrentPlayer();

        final JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export daily play time");
        chooser.setSelectedFile(new File("playtime-" + who.replaceAll("[^a-zA-Z0-9_-]", "_") + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getParentFile(), file.getName() + ".csv");
        }
        try (PrintWriter pw = new PrintWriter(file, "UTF-8")) {
            pw.print(csv);
            JOptionPane.showMessageDialog(this, "Exported daily play time to:\n" + file.getAbsolutePath());
        }
        catch (IOException ex) {
            log.warn("Play Time Tracker CSV export failed", ex);
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JScrollPane wrapContainer(final JPanel container)
    {
        final JPanel wrapped = new JPanel(new BorderLayout());
        wrapped.add(container, BorderLayout.NORTH);
        wrapped.setBackground(BACKGROUND_COLOR);

        final JScrollPane scroller = new JScrollPane(wrapped);
        scroller.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroller.setBackground(BACKGROUND_COLOR);

        return scroller;
    }
}
