package ac.grim.grimac.platform.fabric;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.player.PlatformPlayerFactory;
import ac.grim.grimac.platform.fabric.inject.FabricMinecraftServerHandle;
import ac.grim.grimac.platform.fabric.inject.FabricServerPlayerHandle;
import ac.grim.grimac.platform.fabric.player.FabricPlatformPlayerFactory;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

public class GrimACFabricOfficialEntryPoint extends AbstractGrimACFabricEntryPoint<GrimACFabricOfficialLoaderPlugin> {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(FabricServerEvents::fireServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(FabricServerEvents::fireServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(FabricServerEvents::fireEndTick);

        // TEMP FIX (2026-08-02, mintutils investigation, part 8) - vanilla creates a brand new
        // ServerPlayer Java object on every respawn (confirmed independently via mintutils'
        // RespawnDiagnostics: sameJavaObject=false on every respawn). GrimPlayer.platformPlayer is
        // captured once on first use and cached for the life of the login session (see its
        // assignment in GrimPlayer.java, guarded by "if platformPlayer == null") - nothing ever
        // refreshes it afterward. AbstractPlatformPlayerFactory.replaceNativePlayer(uuid, T) exists
        // specifically to fix this (it swaps the volatile native-entity reference inside the SAME
        // cached PlatformPlayer object GrimPlayer already holds), but had zero callers anywhere in
        // the codebase. Confirmed live: after any respawn, every read routed through platformPlayer
        // (e.g. canGlide()'s real-inventory fallback) kept seeing the pre-respawn player's state
        // forever - a plugin/mod correctly restoring a player's real inventory (elytra included)
        // via the current canonical ServerPlayer object was invisible to Grim, which kept reading
        // the stale, disconnected pre-respawn object instead. Only a full reconnect ever fixed it,
        // since that creates a fresh GrimPlayer (and platformPlayer) from scratch. Wiring this up
        // here - the same event mintutils itself already uses for its own respawn handling - closes
        // the gap at its source instead of patching each individual symptom.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            PlatformPlayerFactory factory = GrimAPI.INSTANCE.getPlatformPlayerFactory();
            if (factory instanceof FabricPlatformPlayerFactory fabricFactory) {
                fabricFactory.replaceNativePlayer(newPlayer.getUUID(), (FabricServerPlayerHandle) (Object) newPlayer);
            }
        });

        initialize(
                "grim26MainLoad",
                GrimACFabricOfficialLoaderPlugin.class,
                true
        );
    }

    @Override
    protected void setPlatformLoader(GrimACFabricOfficialLoaderPlugin platformLoader) {
        GrimACFabricOfficialLoaderPlugin.LOADER = platformLoader;
    }

    @Override
    protected void setNativeServer(FabricMinecraftServerHandle server) {
        GrimACFabricOfficialLoaderPlugin.FABRIC_SERVER = (MinecraftServer) (Object) server;
    }
}
