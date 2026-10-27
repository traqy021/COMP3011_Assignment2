package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    private Path movie() throws IOException {
        return Files.createFile(tempDir.resolve("movie.mp4"));
    }

    @Test
    void acceptsVideoFileWithoutEffects() throws IOException {
        Path file = movie();
        CommandLineController cli = new CommandLineController(new String[] {file.toString()});

        assertTrue(cli.shouldLaunchApplication());
        assertEquals(0, cli.getExitCode());
        assertEquals(file.toFile(), cli.getVideoFile());
        assertEquals(List.of(), cli.getEffects());
    }

    @Test
    void acceptsMixedShortLongAndStackedOptionsInOrder() throws IOException {
        String file = movie().toString();
        CommandLineController cli = new CommandLineController(new String[] {
                "--jitter-frames", "-fd", "--dust-frames", "--number-frames", file
        });

        assertEquals(List.of(FrameEffect.JITTER, FrameEffect.FLICKER,
                FrameEffect.DUST, FrameEffect.DUST, FrameEffect.NUMBER),
                cli.getEffects());
        assertTrue(cli.shouldLaunchApplication());
    }

    @Test
    void acceptsEffectsAfterVideoFile() throws IOException {
        String file = movie().toString();
        CommandLineController cli = new CommandLineController(new String[] {
                file, "-sfbn", "--mottle-frames"
        });

        assertEquals(List.of(FrameEffect.SCRATCH, FrameEffect.FLICKER,
                FrameEffect.BLEED, FrameEffect.NUMBER, FrameEffect.MOTTLE),
                cli.getEffects());
        assertTrue(cli.shouldLaunchApplication());
    }

    @Test
    void retainsEveryRepeatedEffect() throws IOException {
        CommandLineController cli = new CommandLineController(new String[] {
                "-ssssn", movie().toString()
        });

        assertEquals(List.of(FrameEffect.SCRATCH, FrameEffect.SCRATCH,
                FrameEffect.SCRATCH, FrameEffect.SCRATCH, FrameEffect.NUMBER),
                cli.getEffects());
    }

    @Test
    void supportsOriginalPlaybackFlags() throws IOException {
        CommandLineController cli = new CommandLineController(new String[] {
                "-ax1", movie().toString()
        });

        assertTrue(cli.isAudioRequested());
        assertTrue(cli.isMaximiseRequested());
        assertEquals(1, cli.getDisplayId());
        assertTrue(cli.shouldLaunchApplication());
    }

    @Test
    void helpTakesPriorityOverAnEarlierInvalidOption() {
        CommandLineController cli = new CommandLineController(new String[] {
                "--illegal-argument", "movie.mp4", "--help"
        });

        assertEquals(0, cli.getExitCode());
        assertNull(cli.getErrorMessage());
        assertFalse(cli.shouldLaunchApplication());
    }

    @Test
    void helpTakesPriorityOverDuplicateFilenamesAndIsRecognisedInAStack() {
        CommandLineController cli = new CommandLineController(new String[] {
                "movie.mp4", "movie.mp4", "-snh"
        });

        assertEquals(0, cli.getExitCode());
        assertNull(cli.getErrorMessage());
        assertFalse(cli.shouldLaunchApplication());
    }

    @Test
    void helpAfterEndOfOptionsMarkerIsNotTreatedAsFlag() {
        CommandLineController cli = new CommandLineController(new String[] {
                "--", "--help"
        });

        assertNotEquals(0, cli.getExitCode());
        assertNotNull(cli.getErrorMessage());
    }

    @Test
    void missingOrUnknownInputsFail() {
        assertNotEquals(0, new CommandLineController(new String[] {"-n"}).getExitCode());
        assertNotEquals(0, new CommandLineController(new String[] {
                "--illegal-argument", "movie.mp4"}).getExitCode());
        assertNotEquals(0, new CommandLineController(new String[] {
                "-sfbnz", "movie.mp4"}).getExitCode());
        assertNotEquals(0, new CommandLineController(new String[] {
                "movie.mp4", "movie.mp4"}).getExitCode());
    }

    @Test
    void rejectsConflictingMonitorChoices() throws IOException {
        CommandLineController cli = new CommandLineController(new String[] {
                "-1", "--monitor-2", movie().toString()
        });

        assertNotEquals(0, cli.getExitCode());
        assertFalse(cli.shouldLaunchApplication());
    }

    @Test
    void effectListReturnedByParserCannotBeModified() throws IOException {
        CommandLineController cli = new CommandLineController(new String[] {
                "-s", movie().toString()
        });

        assertThrows(UnsupportedOperationException.class,
                () -> cli.getEffects().add(FrameEffect.NUMBER));
        assertEquals(List.of(FrameEffect.SCRATCH), cli.getEffects());
    }
}
