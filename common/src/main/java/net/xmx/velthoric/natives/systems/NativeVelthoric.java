/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.natives.systems;

import net.xmx.velthoric.natives.os.UnsupportedOperatingSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Manages the lifecycle of the custom vxnative library.
 *
 * @author xI-Mx-Ix
 */
public class NativeVelthoric {

    private static final Logger LOGGER = LoggerFactory.getLogger("Velthoric NativeVelthoric");
    private static volatile boolean isInitialized = false;

    /**
     * Initializes the vxnative library.
     *
     * @param extractionPath The root directory where native libraries should be extracted.
     * @throws UnsupportedOperatingSystemException if the current platform is not supported.
     */
    public static void initialize(Path extractionPath) {
        if (isInitialized) {
            return;
        }

        LOGGER.debug("Performing vxnative loading...");

        // Delegate the platform detection and loading to the central manager.
        NativeManager.loadLibrary(extractionPath, "vxnative");

        isInitialized = true;
        LOGGER.debug("vxnative library loaded successfully.");
    }

    public static boolean isInitialized() {
        return isInitialized;
    }
}