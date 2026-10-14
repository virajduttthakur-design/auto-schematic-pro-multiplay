package dev.autoschematicpro.build;

import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compact wire format for a {@link SchematicPlan}: gzip( name, size, palette strings, delta-coded placements ).
 * {@link #decode} treats its input as untrusted (it comes from a client) and enforces strict limits.
 */
public final class PlanCodec {
    private static final long MAX_DECODED_BYTES = 48L * 1024 * 1024;

    private PlanCodec() {}

    public static byte[] encode(SchematicPlan plan) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeUTF(plan.name());
            out.writeInt(plan.width());
            out.writeInt(plan.height());
            out.writeInt(plan.length());
            out.writeInt(plan.palette().size());
            for (String entry : plan.palette()) {
                out.writeUTF(entry);
            }
            out.writeInt(plan.placements().size());
            long previous = -1;
            for (SchematicPlan.Placement placement : plan.placements()) {
                BlockPos pos = placement.relativePos();
                long linear = pos.getX() + (long) pos.getZ() * plan.width() + (long) pos.getY() * plan.width() * plan.length();
                writeVarInt(out, (int) (linear - previous - 1));
                writeVarInt(out, placement.paletteIndex());
                previous = linear;
            }
        }
        return bytes.toByteArray();
    }

    public static SchematicPlan decode(byte[] data, int maxBlocks) throws IOException {
        try (DataInputStream in = new DataInputStream(new LimitedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)), MAX_DECODED_BYTES))) {
            String name = in.readUTF();
            if (name.length() > 128) {
                name = name.substring(0, 128);
            }
            int width = in.readInt();
            int height = in.readInt();
            int length = in.readInt();
            long volume = (long) width * height * length;
            if (width < 1 || height < 1 || length < 1 || volume > SchematicLoader.MAX_BLOCKS) {
                throw new IOException("Invalid schematic dimensions");
            }
            int paletteSize = in.readInt();
            if (paletteSize < 1 || paletteSize > SchematicLoader.MAX_PALETTE) {
                throw new IOException("Invalid palette size");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            BlockState[] states = new BlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                String entry = in.readUTF();
                if (entry.length() > 512) {
                    throw new IOException("Palette entry too long");
                }
                palette.add(entry);
                states[i] = SchematicLoader.parseBlockState(entry);
            }
            int count = in.readInt();
            if (count < 1 || count > volume || count > maxBlocks) {
                throw new IOException("Schematic exceeds this server's block limit (" + maxBlocks + ")");
            }
            List<SchematicPlan.Placement> placements = new ArrayList<>(count);
            long previous = -1;
            for (int i = 0; i < count; i++) {
                long linear = previous + 1 + readVarInt(in);
                int paletteIndex = readVarInt(in);
                if (linear >= volume || paletteIndex < 0 || paletteIndex >= paletteSize) {
                    throw new IOException("Corrupt placement data");
                }
                BlockState state = states[paletteIndex];
                if (state.isAir() || state.getBlock().asItem() == Items.AIR) {
                    throw new IOException("Schematic contains a non-placeable block");
                }
                int x = (int) (linear % width);
                int z = (int) ((linear / width) % length);
                int y = (int) (linear / ((long) width * length));
                placements.add(new SchematicPlan.Placement(new BlockPos(x, y, z), state, paletteIndex));
                previous = linear;
            }
            return new SchematicPlan(name, width, height, length, palette, placements);
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int current = in.readUnsignedByte();
            result |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("Invalid varint");
    }

    /** Guards against gzip bombs. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("Decoded schematic too large");
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new IOException("Decoded schematic too large");
            }
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
