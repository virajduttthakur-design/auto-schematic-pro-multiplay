package dev.autoschematicpro;

import dev.autoschematicpro.build.BuildEngine;
import dev.autoschematicpro.net.ModPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class AutoSchematicProMod implements ModInitializer {
    public static final String MOD_ID = "auto_schematic_pro";

    @Override
    public void onInitialize() {
        ModPackets.register();

        ServerPlayNetworking.registerGlobalReceiver(ModPackets.UploadChunk.ID,
                (payload, context) -> BuildEngine.handleChunk(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ModPackets.StartBuild.ID,
                (payload, context) -> BuildEngine.handleStart(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ModPackets.StopBuild.ID,
                (payload, context) -> BuildEngine.handleStop(context.player()));

        ServerLifecycleEvents.SERVER_STARTING.register(BuildEngine::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BuildEngine.onServerStopped());
        ServerTickEvents.END_SERVER_TICK.register(BuildEngine::tick);
    }
}
