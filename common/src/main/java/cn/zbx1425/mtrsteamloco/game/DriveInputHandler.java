package cn.zbx1425.mtrsteamloco.game;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Keyboard input for virtual driving. The original NTE HMI polled the MTR key bindings from its
 * render overlay; hANTE renders the enhanced console instead, so the same repeat logic lives here
 * and runs once per client tick.
 */
public final class DriveInputHandler {

    private static final KeyMapping ACCELERATE = mtr.KeyMappings.TRAIN_ACCELERATE;
    private static final KeyMapping BRAKE = mtr.KeyMappings.TRAIN_BRAKE;
    private static final KeyMapping DOORS = mtr.KeyMappings.TRAIN_TOGGLE_DOORS;
    private static final KeyMapping NEUTRAL = cn.zbx1425.mtrsteamloco.KeyMappings.TRAIN_NEUTRAL;

    private static int lastKey;
    private static int lastTick;
    private static int cooldown;

    private DriveInputHandler() {
    }

    public static void tick() {
        TrainVirtualDrive train = TrainVirtualDrive.activeTrain;
        if (train == null) {
            lastKey = 0;
            cooldown = 0;
            return;
        }

        Minecraft client = Minecraft.getInstance();
        boolean accelerateDown = ACCELERATE.isDown() || (client.options != null && client.options.keyUp.isDown());
        boolean brakeDown = BRAKE.isDown() || (client.options != null && client.options.keyDown.isDown());

        boolean any = false;
        if (accelerateDown) {
            if (trigger(1)) {
                train.vdNotch = Math.min(train.vdNotch + 1, train.powerNotches);
            }
            any = true;
        } else if (brakeDown) {
            if (trigger(2)) {
                train.vdNotch = Math.max(train.vdNotch - 1, -train.brakeNotches);
            }
            any = true;
        } else if (NEUTRAL.isDown()) {
            if (trigger(3)) {
                train.vdNotch -= Integer.signum(train.vdNotch);
            }
            any = true;
        }

        if (!any) {
            lastKey = 0;
            cooldown = 0;
        }

        while (DOORS.consumeClick()) {
            train.toggleDoors();
        }
    }

    private static boolean trigger(int key) {
        int tick = (int) mtr.MTRClient.getGameTick();
        if (lastKey != key) {
            lastKey = key;
            lastTick = tick;
            cooldown = 6;
            return true;
        }
        if (tick != lastTick) {
            lastTick = tick;
            if (cooldown > 0) {
                cooldown--;
            }
        }
        if (cooldown <= 0) {
            cooldown = 3;
            return true;
        }
        return false;
    }
}
