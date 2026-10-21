/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    // Declaring the effects list
    private final List<FrameEffect> effects = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse(this.args);
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }
    
    public List<FrameEffect> getEffects() {
        return List.copyOf(effects);
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse(String[] args) {
    	
    	// Checking whether help was requested
    	if (containsHelpOption(args)) {
            helpRequested = true;
            return;
        }
    	
    	// Parse all other arguments
        List<String> files = new ArrayList<>();
        boolean optionsEnded = false;
        
        for (String arg : args) {
        	
            if (optionsEnded) {
                files.add(arg);
                
            } else if (arg.equals("--")) {
                optionsEnded = true;
                
            } else if (arg.startsWith("--")) {
                parseLongOption(arg.substring(2));
                
            } else if (arg.startsWith("-") && arg.length() > 1) {
            	
                // '-jfddn' means five individual options, in precisely this order.
                for (int i = 1; i < arg.length(); i++) {
                    parseShortOption(arg.charAt(i));
                }
            } else {
                files.add(arg);
            }
            if (errorMessage != null) {
                return;
            }
        }
        if (files.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (files.size() == 1) {
            videoFile = new File(files.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else if (!helpRequested) {
            errorMessage = "No video file specified.";
        }
    }
    
    // Searches for help before normal argument parsing
    private static boolean containsHelpOption(String[] args) {

        for (String arg : args) {

            if (arg.equals("--")) {
                break;
            }

            if (arg.equals("--help")) {
                return true;
            }

            if (arg.startsWith("-")
                    && !arg.startsWith("--")
                    && arg.length() > 1
                    && arg.indexOf('h', 1) >= 1) {

                return true;
            }
        }

        return false;
    }
    
    private void parseLongOption(String option) {
        FrameEffect effect = FrameEffect.fromLongName(option);
        if (effect != null) {
            effects.add(effect);
            return;
        }
        switch (option) {
            case "help" -> helpRequested = true;
            case "audio" -> audioRequested = true;
            case "maximise" -> maximiseRequested = true;
            case "monitor-1" -> setDisplayId(1);
            case "monitor-2" -> setDisplayId(2);
            default -> errorMessage = "Unknown option: --" + option;
        }
    }

    private void parseShortOption(char option) {
        FrameEffect effect = FrameEffect.fromShortName(option);
        if (effect != null) {
            effects.add(effect);
            return;
        }
        switch (option) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> errorMessage = "Unknown option: -" + option;
        }
    }


    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
