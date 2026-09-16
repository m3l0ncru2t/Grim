package ac.grim.grimac.platform.fabric.mixins;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// TEMP FIX (2026-08-02, mintutils investigation, part 9) - vanilla's own "moved too quickly!"
// check in ServerGamePacketListenerImpl compares reported movement against the player's own
// tracked velocity (Entity.getDeltaMovement()) plus a fixed allowance - it has no concept of "the
// server just gave this player a legitimate one-off velocity spike", so a strong TNT/piston cannon
// can genuinely exceed it on the very first tick after the explosion (confirmed live: a single
// "moved too quickly!" warning right when a cannon fired, not a sustained stream during the whole
// flight - so this is a one-tick startup transient, not an ongoing problem). Vanilla already has
// the exact right mechanism for this: LivingEntity.applyPostImpulseGraceTime(ticks), currently only
// used by the mace's knockback smash attack to grant a brief "you were just legitimately flung,
// don't second-guess what happens next" window. Reuse it here for explosion knockback instead of
// inventing a parallel Grim-side tracking system - see FabricOfficialServerGamePacketListenerMixin
// for where the grace window actually gets consulted. Safe to apply unconditionally (no magnitude
// threshold): this only fires from a real server-authoritative explosion that already pushed a real
// player, so there's no way for a client to trigger it without an actual explosion happening.
@Mixin(ServerExplosion.class)
public abstract class FabricOfficialServerExplosionMixin {

    private static final int ELYTRA_LAUNCHER_GRACE_TICKS = 40;

    @Redirect(method = "hurtEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/phys/Vec3;)V"))
    private void grimac$onExplosionPush(Entity entity, Vec3 knockback) {
        entity.push(knockback);
        if (entity instanceof Player player) {
            player.applyPostImpulseGraceTime(ELYTRA_LAUNCHER_GRACE_TICKS);
        }
    }
}
