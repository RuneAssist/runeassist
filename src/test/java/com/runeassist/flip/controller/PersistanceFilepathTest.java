package com.runeassist.flip.controller;

import com.google.gson.Gson;
import net.runelite.client.util.Filepath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PersistanceFilepathTest {

    @Test
    void dataDirectoryIsSandboxedAndFilesRoundTrip(@TempDir Path tmp) throws Exception {
        Filepath dir = Filepath.Unchecked.getRooted(tmp).joinSegment("runeassist-flipping").rooted();
        Persistance.setDataDir(dir);

        assertFalse(Persistance.hasExistingInstallation(), "nothing on disk yet");
        Persistance.setUp(new Gson());
        assertTrue(Persistance.file(Persistance.LOGIN_RESPONSE_JSON_FILE).exists());
        assertTrue(Persistance.hasExistingInstallation());

        Filepath note = Persistance.file("note.txt");
        note.write("hello");
        assertEquals("hello", Persistance.readString(note));

        // Names cannot leave the plugin directory: the Hub's reason for Filepath.
        assertThrows(IllegalArgumentException.class, () -> Persistance.file("../escape.txt"));
        assertThrows(IllegalArgumentException.class, () -> Persistance.file("a/b.txt"));
    }
}
