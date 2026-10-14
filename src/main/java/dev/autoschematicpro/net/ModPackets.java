package dev.autoschematicpro.net;

import dev.autoschematicpro.AutoSchematicProMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Network protocol. Vanilla limits client-to-server custom payloads to 32 KiB, so schematics are uploaded as a
 * sequence of {@link UploadChunk}s followed by a {@link StartBuild}.
 */
public final class ModPackets {
    public static final int MAX_CHUNK_BYTES = 28_000;
    public static final int MAX_CHUNKS = 600; // ~16 MB compressed upper bound

    private ModPackets() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(UploadChunk.ID, UploadChunk.CODEC);
        PayloadTypeRegistry.playC2S().register(StartBuild.ID, StartBuild.CODEC);
        PayloadTypeRegistry.playC2S().register(StopBuild.ID, StopBuild.CODEC);
        PayloadTypeRegistry.playS2C().register(Status.ID, Status.CODEC);
    }

    public record UploadChunk(int uploadId, int index, int total, byte[] data) implements CustomPayload {
        public static final Id<UploadChunk> ID = new Id<>(Identifier.of(AutoSchematicProMod.MOD_ID, "upload_chunk"));
        public static final PacketCodec<RegistryByteBuf, UploadChunk> CODEC = PacketCodec.of(UploadChunk::write, UploadChunk::read);

        private void write(RegistryByteBuf buf) {
            buf.writeInt(uploadId);
            buf.writeVarInt(index);
            buf.writeVarInt(total);
            buf.writeByteArray(data);
        }

        private static UploadChunk read(RegistryByteBuf buf) {
            return new UploadChunk(buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(MAX_CHUNK_BYTES));
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record StartBuild(int uploadId, BlockPos origin, BlockPos chestA, BlockPos chestB) implements CustomPayload {
        public static final Id<StartBuild> ID = new Id<>(Identifier.of(AutoSchematicProMod.MOD_ID, "start_build"));
        public static final PacketCodec<RegistryByteBuf, StartBuild> CODEC = PacketCodec.of(StartBuild::write, StartBuild::read);

        private void write(RegistryByteBuf buf) {
            buf.writeInt(uploadId);
            buf.writeBlockPos(origin);
            buf.writeBlockPos(chestA);
            buf.writeBlockPos(chestB);
        }

        private static StartBuild read(RegistryByteBuf buf) {
            return new StartBuild(buf.readInt(), buf.readBlockPos(), buf.readBlockPos(), buf.readBlockPos());
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record StopBuild() implements CustomPayload {
        public static final Id<StopBuild> ID = new Id<>(Identifier.of(AutoSchematicProMod.MOD_ID, "stop_build"));
        public static final PacketCodec<RegistryByteBuf, StopBuild> CODEC = PacketCodec.of((value, buf) -> {}, buf -> new StopBuild());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server to client progress/status for the player's own build. */
    public record Status(String text, int completed, int total) implements CustomPayload {
        public static final Id<Status> ID = new Id<>(Identifier.of(AutoSchematicProMod.MOD_ID, "status"));
        public static final PacketCodec<RegistryByteBuf, Status> CODEC = PacketCodec.of(Status::write, Status::read);

        private void write(RegistryByteBuf buf) {
            buf.writeString(text, 256);
            buf.writeVarInt(completed);
            buf.writeVarInt(total);
        }

        private static Status read(RegistryByteBuf buf) {
            return new Status(buf.readString(256), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
