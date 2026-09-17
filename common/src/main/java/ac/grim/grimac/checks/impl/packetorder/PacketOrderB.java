package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

@CheckData(name = "PacketOrderB", stableKey = "grim.packetorder.noswing", description = "Did not swing for attack")
public class PacketOrderB extends Check implements PreViaPacketReceiveListener {
    private static final Verbose V = Verbose.of("[pre-attack|post-attack]");

    // 1.9 packet order: INTERACT -> ANIMATION
    // 1.8 packet order: ANIMATION -> INTERACT
    // I personally think 1.8 made much more sense. You swing and THEN you hit!
    private final boolean is1_9 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);

    private boolean sentAnimationSinceLastAttack = player.getClientVersion().isNewerThan(ClientVersion.V_1_8);
    private boolean sentAttack, sentAnimation, sentSlotSwitch;

    public PacketOrderB(final GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION
            && new WrapperPlayClientAnimation(event).getHand() == InteractionHand.MAIN_HAND) {
            sentAnimationSinceLastAttack = sentAnimation = true;
            sentAttack = sentSlotSwitch = false;
            return;
        }

        // MINTMC FIX (2026-09-17): the REAL cause of "can attack once after joining, then never
        // again" (not the ENTITY_ACTION/sprint theory below, which was a real but much smaller
        // contributor). WrapperPlayClientAnimation/PacketType.ANIMATION is @ApiStatus.Obsolete as
        // of 26.3 - Mojang replaced ServerboundSwingPacket with ServerboundPunchPacket
        // (WrapperPlayClientPunch / PacketType.PUNCH), which carries no hand field at all. A 26.3+
        // client never sends ANIMATION for a swing anymore, so sentAnimationSinceLastAttack was
        // never being set true again after its initial (join-time) default - every attack after
        // the very first one on a fresh connection got flagged and cancelled. PUNCH has no hand
        // data to check, so any PUNCH packet is treated as a main-hand swing.
        if (event.getPacketType() == PacketType.Play.Client.PUNCH) {
            sentAnimationSinceLastAttack = sentAnimation = true;
            sentAttack = sentSlotSwitch = false;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack(event);
                return;
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            onAttack(event);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            if (packet.getAction() == DiggingAction.STAB) {
                onAttack(event);
                return;
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE && !is1_9 && !sentSlotSwitch) {
            sentSlotSwitch = true;
            return; // do not set sentAnimation to false
        }

        // MINTMC FIX (2026-09-17): vanilla auto-cancels sprint the instant you land a hit, which
        // sends an ENTITY_ACTION (stop-sprinting) packet as a normal side effect of attacking -
        // it has nothing to do with the attack/animation ordering this check enforces. Without this
        // exemption it fell into the generic reset below, and since it lands between the (legit)
        // attack packet and its paired animation packet specifically when the attacker is sprinting,
        // every sprint-attack got falsely flagged and cancelled - explains "can hit a squid (not
        // sprinting) but not a pig/sheep/hostile (chasing = sprinting)".
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            return; // do not disturb sentAttack/sentAnimation state
        }

        // MINTMC FIX (2026-09-17): the actual dominant false-positive source, bigger than the
        // ENTITY_ACTION/sprint case above. isUpdate() covers PLAYER_FLYING/PLAYER_POSITION/
        // PLAYER_ROTATION/PLAYER_POSITION_AND_ROTATION (movement, sent every tick regardless of
        // combat) plus CLIENT_TICK_END and transaction acks (PONG/WINDOW_CONFIRMATION) - none of
        // these were exempted, only isAsync()'s much narrower set (KEEP_ALIVE/CHUNK_BATCH_ACK/
        // RESOURCE_PACK_STATUS) was. Any player moving or looking around while attacking (i.e.
        // normal combat) could easily have a routine position/rotation packet land between the
        // attack and its paired swing packet, falsely flagging and cancelling a perfectly legit
        // attack. Reported live: still-cancelled attacks even after the PUNCH-recognition and
        // ENTITY_ACTION fixes.
        if (isUpdate(event.getPacketType())) {
            return; // do not disturb sentAttack/sentAnimation state
        }

        if (!isAsync(event.getPacketType())) {
            if (sentAttack && is1_9) {
                flag(V.write(verbose()).bool(false));
            }

            sentAttack = sentAnimation = sentSlotSwitch = false;
        }
    }

    private void onAttack(PacketReceiveEvent event) {
        if (player.gamemode == GameMode.SPECTATOR && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)) return;

        sentAttack = true;

        if (is1_9 ? !sentAnimationSinceLastAttack : !sentAnimation) {
            sentAttack = false; // don't flag twice
            if (flag(V.write(verbose()).bool(true)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }

        sentAnimationSinceLastAttack = sentAnimation = sentSlotSwitch = false;
    }
}
