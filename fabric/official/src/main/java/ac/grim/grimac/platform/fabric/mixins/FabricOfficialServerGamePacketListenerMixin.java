package ac.grim.grimac.platform.fabric.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// TEMP FIX (2026-08-02, mintutils investigation, part 9) - see FabricOfficialServerExplosionMixin
// for the other half of this fix. shouldCheckPlayerMovement() is vanilla's own single gate for the
// "moved too quickly!" check (mirrors the exact pattern vanilla already uses for the separate
// "moved wrongly!" check a little further down in the same class, which already exempts
// isInPostImpulseGraceTime()) - skip it entirely while the player is still within the grace window
// from a recent explosion push. Doesn't touch the check for anyone not in that window, so ordinary
// speedhack detection is untouched.
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FabricOfficialServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
    private void grimac$skipMovementCheckDuringImpulseGrace(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
        if (this.player.isInPostImpulseGraceTime()) {
            cir.setReturnValue(false);
        }
    }
}
