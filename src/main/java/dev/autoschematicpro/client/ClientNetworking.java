package dev.autoschematicpro.client;

import dev.autoschematicpro.build.PlanCodec;
import dev.autoschematicpro.build.SchematicPlan;
import dev.autoschematicpro.net.ModPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class ClientNetworking {
    private static int nextUploadId = (int) System.nanoTime();

    private ClientNetworking() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.Status.ID,
                (payload, context) -> ClientBuildState.update(payload.text(), payload.completed(), payload.total()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientBuildState.reset());
    }

    /** True when the server we're connected to (including our own integrated server) has this mod installed. */
    public static boolean serverSupportsMod() {
        return ClientPlayNetworking.canSend(ModPackets.StartBuild.ID);
    }

    /**
     * Uploads the schematic in chunks (vanilla caps client-to-server payloads at 32 KiB), then asks the server to start.
     * @return null on success, otherwise a user-facing error message.
     */
    public static String startBuild(SchematicPlan plan, BlockPos origin, List<BlockPos> chests) {
        byte[] data;
        try {
            data = PlanCodec.encode(plan);
        } catch (IOException exception) {
            return "Could not prepare the schematic for upload.";
        }
        int total = (data.length + ModPackets.MAX_CHUNK_BYTES - 1) / ModPackets.MAX_CHUNK_BYTES;
        if (total > ModPackets.MAX_CHUNKS) {
            return "This schematic is too large to upload.";
        }
        int uploadId = nextUploadId++;
        for (int index = 0; index < total; index++) {
            int from = index * ModPackets.MAX_CHUNK_BYTES;
            int to = Math.min(data.length, from + ModPackets.MAX_CHUNK_BYTES);
            ClientPlayNetworking.send(new ModPackets.UploadChunk(uploadId, index, total, Arrays.copyOfRange(data, from, to)));
        }
        ClientPlayNetworking.send(new ModPackets.StartBuild(uploadId, origin, chests.get(0), chests.get(1)));
        return null;
    }

    public static void stopBuild() {
        ClientPlayNetworking.send(new ModPackets.StopBuild());
    }
}
