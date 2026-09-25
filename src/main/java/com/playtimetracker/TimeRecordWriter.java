package com.playtimetracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.http.api.RuneLiteAPI;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@Singleton
public class TimeRecordWriter {
    private static final String FILE_EXTENSION = ".log";
    private static final File PLAY_TIME_DIR = new File(RUNELITE_DIR, "playTime");
    private String playerName;
    /**
     * Set when a load of an existing file did not complete cleanly. While set, writes are refused:
     * the in-memory records are known to be incomplete, and this file is a full truncate-and-rewrite,
     * so saving would make a transient read failure a permanent loss of history.
     */
    private boolean lastLoadIncomplete;

    @Inject
    public TimeRecordWriter() {
        PLAY_TIME_DIR.mkdir();
    }

    public void setPlayerUsername(final String username)
    {
        playerName = sanitize(username);
    }

    /** Keeps the name filesystem-safe and never blank (a blank name would create a stray ".log"). */
    private static String sanitize(final String username)
    {
        if (username == null) {
            return "";
        }
        return username.trim().replaceAll("[^a-zA-Z0-9 _-]", "_");
    }

    public synchronized ArrayList<PlayTimeRecord> loadPlayTimeRecords()
    {
        if (playerName == null || playerName.isEmpty()) {
            return new ArrayList();
        }
        final String fileName = playerName.trim() + FILE_EXTENSION;
        final File file = new File(PLAY_TIME_DIR, fileName);
        final ArrayList<PlayTimeRecord> data = new ArrayList<>();
        lastLoadIncomplete = false;
        if (!file.exists()) {
            log.debug("Play Time Tracker: no existing file {}, starting fresh", fileName);
            return new ArrayList();
        }
        int lineNo = 0;
        int badLines = 0;
        boolean readFailed = false;
        try (final BufferedReader br = new BufferedReader(new FileReader(file)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                lineNo++;
                // Skips the empty line at end of file
                if (line.length() > 0)
                {
                    try
                    {
                        final PlayTimeRecord r = RuneLiteAPI.GSON.fromJson(line, PlayTimeRecord.class);
                        if (r != null)
                        {
                            data.add(r);
                        }
                    }
                    catch (RuntimeException parseError)
                    {
                        // A line the client never finished writing must not kill the read, and must
                        // never propagate: this runs under onGameTick, where a throw repeats forever.
                        badLines++;
                        log.warn("Play Time Tracker: unparseable line {} in {} ({}): {}",
                                lineNo, fileName,
                                isAllNul(line) ? "all-NUL, crashed mid-write" : "bad JSON",
                                isAllNul(line) ? "<" + line.length() + " NUL bytes>" : line);
                    }
                }
            }
        }
        catch (FileNotFoundException e)
        {
            log.debug("File not found: {}", fileName);
        }
        catch (IOException e)
        {
            // Partial read of a file we know exists: the records we have are incomplete.
            readFailed = true;
            log.warn("IOException for file {} after {} lines: {}", fileName, lineNo, e.getMessage());
        }

        if (badLines > 0 && data.isEmpty())
        {
            // Nothing at all survived, so there is no history left to protect. Refusing to write
            // here would brick the character forever; move the wreckage aside and start clean.
            lastLoadIncomplete = !quarantine(file, fileName);
            return new ArrayList<>();
        }

        // Some records did survive alongside a failure. Overwriting now would drop the rest of the
        // file permanently, since a save is a full rewrite — so hold off until a clean read.
        lastLoadIncomplete = badLines > 0 || readFailed;
        log.debug("Play Time Tracker: loaded {} records from {} ({} lines, {} bad, incomplete={})",
                data.size(), fileName, lineNo, badLines, lastLoadIncomplete);
        return data;
    }

    /** A line of NUL bytes is what NTFS leaves behind when the client dies mid-rewrite. */
    private static boolean isAllNul(final String line)
    {
        for (int i = 0; i < line.length(); i++)
        {
            if (line.charAt(i) != '\0')
            {
                return false;
            }
        }
        return true;
    }

    /** Moves an unreadable file aside so it is never silently destroyed. True if it is now safe to write. */
    private static boolean quarantine(final File file, final String fileName)
    {
        final File dest = new File(PLAY_TIME_DIR, fileName + ".corrupt-" + System.currentTimeMillis());
        try
        {
            Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.warn("Play Time Tracker: {} held no readable records; moved to {} and starting fresh",
                    fileName, dest.getName());
            return true;
        }
        catch (IOException e)
        {
            log.warn("Play Time Tracker: could not quarantine {}: {}", fileName, e.getMessage());
            return false;
        }
    }

    public synchronized boolean writePlayTimeFile(final ArrayList<PlayTimeRecord> times)
    {
        if (playerName == null || playerName.isEmpty()) {
            return false;
        }
        final String fileName = playerName.trim() + FILE_EXTENSION;
        if (lastLoadIncomplete)
        {
            // Refuse to overwrite a file we failed to read in full — see lastLoadIncomplete.
            log.warn("Play Time Tracker: refusing to write {}, last load was incomplete", fileName);
            return false;
        }
        final File timeFile = new File(PLAY_TIME_DIR, fileName);
        final File tempFile = new File(PLAY_TIME_DIR, fileName + ".tmp");

        try
        {
            // Write to a temp file and swap it in, so a crash mid-save cannot leave the real file
            // truncated. This runs every 10 ticks, so the truncate window would otherwise be constant.
            final FileOutputStream fos = new FileOutputStream(tempFile, false);
            try (final BufferedWriter file = new BufferedWriter(new OutputStreamWriter(fos)))
            {
                for (final PlayTimeRecord rec : times)
                {
                    // Convert entry to JSON
                    final String dataAsString = RuneLiteAPI.GSON.toJson(rec);
                    file.append(dataAsString);
                    file.newLine();
                }
                // Force the bytes to the platter before the swap. Without this the rename can be
                // journalled ahead of the data, and a power cut leaves the real file the right
                // length but full of NULs — which is exactly the crash this method exists to stop.
                file.flush();
                fos.getFD().sync();
            }

            try
            {
                Files.move(tempFile.toPath(), timeFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException notAtomic)
            {
                Files.move(tempFile.toPath(), timeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            return true;
        }
        catch (IOException ioe)
        {
            log.warn("Error rewriting play time data to file {}: {}", fileName, ioe.getMessage());
            return false;
        }
    }
}
