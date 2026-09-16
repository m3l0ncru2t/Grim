package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.elytra.ElytraA;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.IntToObjectPair;
import ac.grim.grimac.utils.data.SprintingState;
import ac.grim.grimac.utils.data.packetentity.JumpableEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

public class PacketEntityAction extends PacketListenerAbstract {

    public PacketEntityAction() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());

            if (player == null) return;

            switch (action.getAction()) {
                case START_SPRINTING -> {
                    player.isSprinting = true;
                    player.vehicleData.camelSprintingState = SprintingState.STARTED;
                }
                case STOP_SPRINTING -> {
                    player.isSprinting = false;
                    player.vehicleData.camelSprintingState = SprintingState.STOPPED;
                }
                case START_SNEAKING -> player.isSneaking = true;
                case STOP_SNEAKING -> player.isSneaking = false;
                case START_FLYING_WITH_ELYTRA -> {
                    if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9))
                        return;

                    // TEMP FIX (2026-08-02, mintutils investigation, part 4) - onGround/lastOnGround
                    // only become trustworthy once any teleport the player is mid-confirming has
                    // actually settled - see SetbackTeleportUtil.shouldBlockMovement(), Grim's own
                    // general-purpose "are we in a desync/unconfirmed-teleport state" signal (covers
                    // both the post-respawn hasAcceptedSpawnTeleport case AND an ordinary teleport
                    // still awaiting its position/transaction match via requiredSetBack). A minigame's
                    // own restore-to-original-position teleport - fired for an eliminated player after
                    // their own respawn, OR for the match winner who never respawned at all but still
                    // gets teleported back once the match ends - can leave onGround stuck stale from a
                    // completely different, earlier position while that confirmation is still pending.
                    // Rejecting a genuine elytra deploy on stale ground state here was confirmed live
                    // for both cases: the player is stuck unable to glide, repeatedly forced back down
                    // by the resync below, exactly matching "stuck in elytra on the ground" / "pulled
                    // to the ground like I'm fly hacking." Don't trust onGround for this specific
                    // rejection until any pending teleport has actually settled - this doesn't weaken
                    // the check for any already-settled player, ghost-elytra fly-hacking is still
                    // caught exactly as before.
                    boolean settled = !player.getSetbackTeleportUtil().shouldBlockMovement();

                    if (settled && (player.onGround || player.lastOnGround)) {
                        player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
                        player.resyncGlidingState();
                        event.setCancelled(true);
                        player.onPacketCancel();
                        return;
                    }

                    player.checkManager.get(ElytraA.class).onStartGliding(event);

                    // Starting fall flying is server sided on 1.14 and below
                    if (player.getClientVersion().isOlderThan(ClientVersion.V_1_15)) return;

                    // This shouldn't be needed with latency compensated inventories
                    // TODO: Remove this?
                    if (player.canGlide()) {
                        player.isGliding = true;
                        player.pointThreeEstimator.updatePlayerGliding();
                    } else {
                        // A client is flying with a ghost elytra, resync
                        player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
                        player.resyncGlidingState();
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }
                case START_JUMPING_WITH_HORSE -> {
                    PacketEntity riding = player.compensatedEntities.self.getRiding();
                    if (riding instanceof JumpableEntity jumpable) {
                        if (player.vehicleData.pendingJumps.size() >= 20) return; // discard
                        player.vehicleData.pendingJumps.add(new IntToObjectPair<>(action.getJumpBoost(), jumpable));
                    }
                }
            }
        }
    }
}
