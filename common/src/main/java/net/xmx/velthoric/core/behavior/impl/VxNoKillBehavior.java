/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.core.behavior.impl;

import net.xmx.velthoric.core.behavior.VxBehavior;
import net.xmx.velthoric.core.behavior.VxBehaviorId;
import net.xmx.velthoric.init.VxMainClass;

/**
 * A behavior that prevents a body from being removed by standard administrative commands like /vxkill.
 * <p>
 * This is intended for bodies that are part of the world infrastructure, such as moving bridges,
 * elevators, or decorative physics objects that should not be accidentally deleted by players.
 *
 * @author xI-Mx-Ix
 */
public class VxNoKillBehavior implements VxBehavior {

    /**
     * The unique identifier for this behavior.
     */
    public static final VxBehaviorId ID = new VxBehaviorId(VxMainClass.MODID, "NoKill");

    /**
     * Default constructor for no-kill behavior.
     */
    public VxNoKillBehavior() {
    }

    /**
     * @return The unique identifier for this behavior.
     */
    @Override
    public VxBehaviorId getId() {
        return ID;
    }
}
