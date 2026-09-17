package ac.grim.grimac.utils.data;

import ac.grim.grimac.utils.math.Vector3dm;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class SetBackData {
    private final TeleportData teleportData;
    private final float xRot, yRot;
    private final Vector3dm velocity;
    private final boolean vehicle;
    private boolean isComplete = false;
    // TODO: Rethink when we block movements for teleports, perhaps after 10 ticks or 5 blocks?
    // MINTMC FIX (2026-09-17): implemented the timeout this TODO describes. The confirmation
    // handshake this setback waits on has no other fallback - if the handshake packet is ever
    // lost/reordered (real network jitter, not just extreme lag), shouldBlockMovement() stayed
    // true forever, since it only checks isComplete(). That blocked movement AND, via the same
    // gate, the Reach combat check and entity-action packets - so a stuck player couldn't move,
    // fight, or use items until a full reconnect reset their session. 100 ticks (5s) is generous
    // enough that a real confirmation under normal latency/lag still lands well before the
    // timeout fires, while capping the worst case at 5s instead of indefinite.
    private static final int STUCK_TIMEOUT_TICKS = 100;
    private boolean isPlugin;
    private int ticksComplete = 0;
    private int ticksIncomplete = 0;

    public SetBackData(TeleportData teleportData, float xRot, float yRot, Vector3dm velocity, boolean vehicle, boolean isPlugin) {
        this.teleportData = teleportData;
        this.xRot = xRot;
        this.yRot = yRot;
        this.velocity = velocity;
        this.vehicle = vehicle;
        this.isPlugin = isPlugin;
    }

    public void tick() {
        if (isComplete) {
            ticksComplete++;
        } else {
            ticksIncomplete++;
        }
    }

    public boolean isStuckTooLong() {
        return !isComplete && ticksIncomplete >= STUCK_TIMEOUT_TICKS;
    }
}
