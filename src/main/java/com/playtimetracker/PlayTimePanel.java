package com.playtimetracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;

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

    private boolean shown = false;

    public PlayTimePanel(final PlayTimePlugin plugin)
    {
        super(false);
        this.plugin = plugin;
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

        add(errorPanel, BorderLayout.NORTH);
        add(wrapContainer(stats), BorderLayout.CENTER);
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
