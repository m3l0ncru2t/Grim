package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

@CheckData(name = "Simulation", stableKey = "grim.prediction.simulation", description = "Moved differently than predicted movement simulation", decay = 0.02)
public class OffsetHandler extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("{offset}");

    private static final AtomicInteger flags = new AtomicInteger(0);
    // Config
    private double setbackDecayMultiplier;
    private double threshold;
    private double immediateSetbackThreshold;
    private double maxAdvantage;
    private double maxCeiling;
    private double setbackViolationThreshold;
    // Current advantage gained
    private double advantageGained = 0;
    private static final CompletePredictionEvent.Channel COMPLETE_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class);

    // TEMP FIX (2026-08-02, mintutils investigation) - isGliding is normally only ever cleared by
    // PacketSelfMetadataListener, gated behind a transaction round-trip that can apparently get stuck
    // indefinitely with no fallback (LatencyUtils queues tasks per-transaction and never expires
    // them if the matching ack never arrives). When that happens isGliding stays true forever even
    // though the player is genuinely standing on the ground, and every tick's elytra-physics
    // prediction mismatches their real ground movement by a small, constant offset - confirmed live
    // via this exact check repeatedly flagging an identical offset for minutes straight. A real
    // elytra landing clears isGliding within a tick or two in vanilla, so a long-running glide is
    // proof the flag is stuck - self-heal it instead of leaving the player stuck until a full
    // reconnect.
    //
    // First attempt tracked isGliding && onGround together and reset the counter the instant either
    // went false for even one tick - which normal walking/jumping does constantly (onGround flickers
    // false every footstep/hop), so the counter almost never reached threshold in practice. Track
    // gliding duration on its own instead (only resets when isGliding itself goes false) and only
    // act at a moment onGround also happens to be true - which real ground movement provides
    // plentifully, and which for a genuine long flight only lines up right at the actual landing
    // tick anyway (harmless - that's exactly when vanilla would clear it normally).
    private static final int STUCK_GLIDING_TICKS_THRESHOLD = 60; // 3 seconds
    private int glidingDurationTicks = 0;

    // TEMP FIX (2026-08-02, mintutils investigation, part 2) - same root mechanism, different flag.
    // player.isFlying/canFly are updated by PacketPlayerAbilities the same way isGliding is updated
    // by PacketSelfMetadataListener - queued via the same LatencyUtils transaction round-trip with no
    // timeout. Confirmed live: a player leaving a flight-capable mode (e.g. spectator, after being
    // eliminated from a minigame) back into survival kept flagging Simulation with a constant offset
    // starting the moment they returned - isFlying never got cleared. Same self-heal shape as
    // isGliding above: track how long isFlying has been continuously true, and only act at a moment
    // onGround also happens to be true.
    private static final int STUCK_FLYING_TICKS_THRESHOLD = 60; // 3 seconds
    private int flyingDurationTicks = 0;

    public OffsetHandler(GrimPlayer player) {
        super(player);
    }

    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) return;

        // TEMP FIX (2026-08-02, mintutils investigation, part 3) - confirmed live: a spectator (e.g.
        // eliminated from a minigame, correctly floating with no gravity/collision) was still getting
        // hit by this check's executeViolationSetback(), which - unlike every other setback method in
        // SetbackTeleportUtil - has no spectator exemption (deliberately, per its own comment, because
        // Timer-style checks still need to catch spectators). But *this* check compares real movement
        // against normal-physics prediction, which fundamentally doesn't model free-fly/no-clip
        // spectator movement in the first place, so a mismatch here is meaningless for a spectator and
        // the resulting setback just forcibly snaps them toward normal-physics ground position - felt
        // exactly like "being pulled to the ground." Skip this specific check for spectators instead
        // of touching the shared setback-exemption logic other checks still legitimately rely on.
        if (player.gamemode == GameMode.SPECTATOR) return;

        if (player.isGliding) {
            glidingDurationTicks++;
            if (player.onGround && glidingDurationTicks > STUCK_GLIDING_TICKS_THRESHOLD) {
                player.isGliding = false;
                player.pointThreeEstimator.updatePlayerGliding();
                glidingDurationTicks = 0;
            }
        } else {
            glidingDurationTicks = 0;
        }

        if (player.isFlying) {
            flyingDurationTicks++;
            if (player.onGround && flyingDurationTicks > STUCK_FLYING_TICKS_THRESHOLD) {
                player.isFlying = false;
                flyingDurationTicks = 0;
            }
        } else {
            flyingDurationTicks = 0;
        }

        double offset = predictionComplete.getOffset();

        if (COMPLETE_CHANNEL.fire(player, this, offset)) return;

        if ((offset >= threshold || offset >= immediateSetbackThreshold)) {
            advantageGained += offset;
            giveOffsetLenienceNextTick(offset);

            synchronized (flags) {
                int flagId = (flags.get() & 255) + 1; // 1-256 as possible values

                if (flag(V.write(verbose()).f64(offset), () -> humanFormattedOffset(offset) + " /gl " + flagId)) {
                    flags.incrementAndGet();
                    predictionComplete.setIdentifier(flagId);

                    if ((advantageGained >= maxAdvantage || offset >= immediateSetbackThreshold)
                            && !isNoSetbackPermission()
                            && violations >= setbackViolationThreshold) {
                        player.getSetbackTeleportUtil().executeViolationSetback();
                    }
                }
            }

            advantageGained = Math.min(advantageGained, maxCeiling);
        } else {
            advantageGained *= setbackDecayMultiplier;
        }

        removeOffsetLenience();
    }

    public static String humanFormattedOffset(double offset) {
        String humanFormattedOffset;
        if (offset < 0.001) { // 1.129E-3
            humanFormattedOffset = String.format("%.4E", offset);
            // Squeeze out an extra digit here by E-03 to E-3
            humanFormattedOffset = humanFormattedOffset.replace("E-0", "E-");
        } else {
            // 0.00112945678 -> .001129
            humanFormattedOffset = String.format("%6f", offset);
            // I like the leading zero, but removing it lets us add another digit to the end
            humanFormattedOffset = humanFormattedOffset.replace("0.", ".");
        }
        return humanFormattedOffset;
    }

    private void giveOffsetLenienceNextTick(double offset) {
        // Don't let players carry more than 1 offset into the next tick
        // (I was seeing cheats try to carry 1,000,000,000 offset into the next tick!)
        //
        // This value so that setting back with high ping doesn't allow players to gather high client velocity
        double minimizedOffset = Math.min(offset, 1);

        // Normalize offsets
        player.uncertaintyHandler.lastHorizontalOffset = minimizedOffset;
        player.uncertaintyHandler.lastVerticalOffset = minimizedOffset;
    }

    private void removeOffsetLenience() {
        player.uncertaintyHandler.lastHorizontalOffset = 0;
        player.uncertaintyHandler.lastVerticalOffset = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        setbackDecayMultiplier = config.getDoubleElse("Simulation.setback-decay-multiplier", 0.999);
        threshold = config.getDoubleElse("Simulation.threshold", 0.001);
        immediateSetbackThreshold = config.getDoubleElse("Simulation.immediate-setback-threshold", 0.1);
        maxAdvantage = config.getDoubleElse("Simulation.max-advantage", 1);
        maxCeiling = config.getDoubleElse("Simulation.max-ceiling", 4);
        setbackViolationThreshold = config.getDoubleElse("Simulation.setback-violation-threshold", 1);
        if (maxAdvantage == -1) maxAdvantage = Double.MAX_VALUE;
        if (immediateSetbackThreshold == -1) immediateSetbackThreshold = Double.MAX_VALUE;
    }

    public boolean doesOffsetFlag(double offset) {
        return offset >= threshold;
    }
}
