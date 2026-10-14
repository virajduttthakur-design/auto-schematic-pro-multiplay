package dev.autoschematicpro.build;

import dev.autoschematicpro.net.ModPackets;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative schematic builder. Everything here runs on the server thread. Each player may run at most
 * one build; jobs are independent. The server never trusts the client: the plan is re-parsed from the uploaded bytes
 * and every position is re-validated against permissions, distance, world border, spawn protection and claim mods
 * (via Fabric's UseBlockCallback).
 */
public final class BuildEngine {
    /** Only the 36 main inventory slots are used; armor and off-hand are never touched. */
    private static final int MAIN_SLOTS = 36;
    private static final int STATUS_INTERVAL_TICKS = 10;

    private static BuildConfig config = BuildConfig.load(true);
    private static final Map<UUID, Job> jobs = new HashMap<>();
    private static final Map<UUID, Upload> uploads = new HashMap<>();
    private static int tickCounter;

    private BuildEngine() {}

    // ---------------------------------------------------------------- lifecycle

    public static void onServerStarting(MinecraftServer server) {
        config = BuildConfig.load(server.isDedicated());
        jobs.clear();
        uploads.clear();
    }

    public static void onServerStopped() {
        jobs.clear();
        uploads.clear();
    }

    // ---------------------------------------------------------------- packet handlers

    public static void handleChunk(ServerPlayerEntity player, ModPackets.UploadChunk chunk) {
        if (!permitted(player)) {
            return;
        }
        if (chunk.total() < 1 || chunk.total() > ModPackets.MAX_CHUNKS || chunk.index() < 0 || chunk.index() >= chunk.total()) {
            uploads.remove(player.getUuid());
            return;
        }
        Upload upload = uploads.get(player.getUuid());
        if (upload == null || upload.id != chunk.uploadId() || upload.chunks.length != chunk.total()) {
            upload = new Upload(chunk.uploadId(), chunk.total());
            uploads.put(player.getUuid(), upload);
        }
        if (upload.chunks[chunk.index()] == null) {
            upload.chunks[chunk.index()] = chunk.data();
            upload.received++;
        }
    }

    public static void handleStart(MinecraftServer server, ServerPlayerEntity player, ModPackets.StartBuild request) {
        if (!permitted(player)) {
            return;
        }
        UUID id = player.getUuid();
        Upload upload = uploads.remove(id);
        if (upload == null || upload.id != request.uploadId() || upload.received != upload.chunks.length) {
            reject(player, "Schematic upload was incomplete. Try again.");
            return;
        }
        if (jobs.containsKey(id)) {
            reject(player, "A build is already active. Stop it before starting another.");
            return;
        }
        if (jobs.size() >= config.maxConcurrentBuilds) {
            reject(player, "The server is running the maximum number of builds. Try again later.");
            return;
        }
        if (!player.isAlive() || player.isSpectator() || player.interactionManager.getGameMode() == GameMode.ADVENTURE) {
            reject(player, "You can't build in your current game mode.");
            return;
        }

        SchematicPlan plan;
        try {
            int total = 0;
            for (byte[] part : upload.chunks) {
                total += part.length;
            }
            byte[] all = new byte[total];
            int offset = 0;
            for (byte[] part : upload.chunks) {
                System.arraycopy(part, 0, all, offset, part.length);
                offset += part.length;
            }
            plan = PlanCodec.decode(all, config.maxBlocks);
        } catch (IOException | RuntimeException exception) {
            reject(player, "Rejected schematic: " + exception.getMessage());
            return;
        }

        ServerWorld world = player.getEntityWorld();
        RegistryKey<World> dimension = world.getRegistryKey();
        BlockPos origin = request.origin().toImmutable();
        BlockPos chestA = request.chestA().toImmutable();
        BlockPos chestB = request.chestB().toImmutable();

        if (horizontalDistanceSq(player.getBlockPos(), origin) > (long) config.maxOriginDistance * config.maxOriginDistance) {
            reject(player, "The build origin is more than " + config.maxOriginDistance + " blocks from you.");
            return;
        }
        for (BlockPos chest : List.of(chestA, chestB)) {
            if (player.getBlockPos().getSquaredDistance(chest) > (double) config.maxChestDistance * config.maxChestDistance) {
                reject(player, "The supply chest must be within " + config.maxChestDistance + " blocks of you.");
                return;
            }
            if (!world.isChunkLoaded(new ChunkPos(chest).toLong())) {
                reject(player, "The supply chest is not loaded.");
                return;
            }
        }
        if (!isDoubleChest(world, chestA, chestB)) {
            reject(player, "The selected chest blocks are no longer a connected double chest.");
            return;
        }
        for (BlockPos chestPos : List.of(chestA, chestB)) {
            if (!(world.getBlockEntity(chestPos) instanceof Inventory)) {
                reject(player, "The selected double chest is not available in this dimension.");
                return;
            }
        }
        for (SchematicPlan.Placement placement : plan.placements()) {
            if (!world.isInBuildLimit(origin.add(placement.relativePos()))) {
                reject(player, "The schematic extends outside this world's build height.");
                return;
            }
        }

        Job job = new Job(id, dimension, origin, List.of(chestA, chestB), plan);
        jobs.put(id, job);
        job.status = "Building " + plan.name();

        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Item, Integer> material : plan.materialCounts().entrySet()) {
            int missing = material.getValue() - countMain(inventory, material.getKey());
            if (missing > 0) {
                transferFromChest(world, job.chestPositions, inventory, material.getKey(), missing);
            }
        }
        player.sendMessage(Text.literal("Auto Schematic Pro: starting " + plan.name() + " (" + plan.placements().size() + " blocks)."), false);
        sendStatus(player, job);
    }

    public static void handleStop(ServerPlayerEntity player) {
        uploads.remove(player.getUuid());
        Job job = jobs.remove(player.getUuid());
        if (job != null) {
            job.status = "Stopped";
            sendStatus(player, job);
            player.sendMessage(Text.literal("Auto Schematic Pro: build stopped."), false);
        }
    }

    // ---------------------------------------------------------------- ticking

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (jobs.isEmpty() && uploads.isEmpty()) {
            return;
        }
        if (tickCounter % 200 == 0) {
            uploads.keySet().removeIf(uuid -> server.getPlayerManager().getPlayer(uuid) == null);
        }
        Iterator<Job> iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId);
            if (player == null) {
                iterator.remove(); // player left; nobody to report to
                continue;
            }
            boolean finished;
            try {
                finished = tickJob(server, player, job);
            } catch (RuntimeException exception) {
                job.status = "Stopped: internal error";
                player.sendMessage(Text.literal("Auto Schematic Pro: build stopped because of an internal error."), false);
                finished = true;
            }
            if (finished) {
                iterator.remove();
                sendStatus(player, job);
            } else if (!job.status.equals(job.lastSentStatus) || tickCounter % STATUS_INTERVAL_TICKS == 0) {
                sendStatus(player, job);
            }
        }
    }

    /** @return true when the job is over (complete or aborted) and should be removed. */
    private static boolean tickJob(MinecraftServer server, ServerPlayerEntity player, Job job) {
        ServerWorld world = server.getWorld(job.dimension);
        if (world == null) {
            job.status = "Stopped: world is unavailable.";
            return true;
        }
        if (!player.isAlive() || player.isSpectator()) {
            job.status = "Paused: you can't build right now.";
            return false;
        }
        if (job.delayTicks > 0) {
            job.delayTicks--;
            return false;
        }

        List<SchematicPlan.Placement> placements = job.plan.placements();
        while (job.nextPlacement < placements.size()) {
            SchematicPlan.Placement placement = placements.get(job.nextPlacement);
            BlockPos target = job.origin.add(placement.relativePos());
            if (!world.isInBuildLimit(target)) {
                job.status = "Stopped: the schematic extends outside the world build height.";
                player.sendMessage(Text.literal("Auto Schematic Pro: stopped because the schematic extends outside the world build height."), false);
                return true;
            }
            if (!world.isChunkLoaded(new ChunkPos(target).toLong())) {
                job.delayTicks = 20;
                job.status = "Waiting for the build area chunk to load.";
                return false;
            }
            BlockState requestedState = placement.state();
            BlockState existing = world.getBlockState(target);
            if (existing.equals(requestedState)) {
                advance(job, false);
                continue;
            }
            Item material = requestedState.getBlock().asItem();
            if (material == Items.AIR) {
                advance(job, false);
                continue;
            }
            if (!canModify(server, world, player, target, existing)) {
                advance(job, true);
                continue;
            }

            PlayerInventory inventory = player.getInventory();
            if (countMain(inventory, material) == 0) {
                transferFromChest(world, job.chestPositions, inventory, material, Math.max(1, material.getMaxCount()));
            }
            if (countMain(inventory, material) == 0) {
                if (!job.waitingForMaterial) {
                    player.sendMessage(Text.literal("Auto Schematic Pro: waiting for " + material.getName().getString() + " in the double chest."), false);
                    job.waitingForMaterial = true;
                }
                job.delayTicks = 20;
                job.status = "Waiting for " + material.getName().getString();
                return false;
            }

            int slot = findStackSlot(inventory, material);
            if (slot < 0) {
                job.delayTicks = 20;
                return false;
            }
            inventory.removeStack(slot, 1);
            inventory.markDirty();
            world.setBlockState(target, requestedState, 3);
            advance(job, false);
            job.waitingForMaterial = false;
            job.delayTicks = Math.max(0, config.placeIntervalTicks - 1);
            job.status = "Building " + job.plan.name();
            return false;
        }

        job.status = job.skipped > 0 ? "Complete (" + job.skipped + " skipped)" : "Complete";
        player.sendMessage(Text.literal("Auto Schematic Pro: build complete."
                + (job.skipped > 0 ? " " + job.skipped + " positions were skipped (protected or already occupied)." : "")), false);
        return true;
    }

    private static void advance(Job job, boolean skipped) {
        job.nextPlacement++;
        job.completed++;
        if (skipped) {
            job.skipped++;
        }
    }

    /** Server-side protection checks so the builder can't be used to bypass spawn protection, claims or the world border. */
    private static boolean canModify(MinecraftServer server, ServerWorld world, ServerPlayerEntity player, BlockPos target, BlockState existing) {
        if (!existing.isAir() && !existing.isReplaceable()) {
            if (!config.overwriteExistingBlocks) {
                return false;
            }
        }
        if (world.getBlockEntity(target) != null) {
            return false; // never destroy containers/signs/etc. along with their contents
        }
        if (!world.getWorldBorder().contains(target) || server.isSpawnProtected(world, target, player)) {
            return false;
        }
        ActionResult probe = UseBlockCallback.EVENT.invoker().interact(
                player, world, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false));
        return probe != ActionResult.FAIL;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean permitted(ServerPlayerEntity player) {
        if (config.isAllowed(player.getUuid(), player.getName().getString())) {
            return true;
        }
        reject(player, "You are not allowed to use Auto Schematic Pro on this server. Ask an admin to add you to allowedPlayers in config/auto_schematic_pro.json.");
        return false;
    }

    private static void reject(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal("Auto Schematic Pro: " + message), false);
        ServerPlayNetworking.send(player, new ModPackets.Status(truncate(message), 0, 0));
    }

    private static void sendStatus(ServerPlayerEntity player, Job job) {
        job.lastSentStatus = job.status;
        ServerPlayNetworking.send(player, new ModPackets.Status(truncate(job.status), job.completed, job.plan.placements().size()));
    }

    private static String truncate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static long horizontalDistanceSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean isDoubleChest(ServerWorld world, BlockPos first, BlockPos second) {
        BlockState firstState = world.getBlockState(first);
        BlockState secondState = world.getBlockState(second);
        if (!(firstState.getBlock() instanceof ChestBlock) || firstState.getBlock() != secondState.getBlock()) {
            return false;
        }
        int dx = Math.abs(first.getX() - second.getX());
        int dz = Math.abs(first.getZ() - second.getZ());
        if (first.getY() != second.getY() || dx + dz != 1) {
            return false;
        }
        Direction facing = firstState.get(ChestBlock.FACING);
        if (facing != secondState.get(ChestBlock.FACING)) {
            return false;
        }
        // Halves of a double chest sit side by side, perpendicular to the facing direction.
        Direction.Axis offsetAxis = dx == 1 ? Direction.Axis.X : Direction.Axis.Z;
        return facing.getAxis() != offsetAxis;
    }

    private static int countMain(PlayerInventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < MAIN_SLOTS && slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int findStackSlot(Inventory inventory, Item item) {
        for (int slot = 0; slot < MAIN_SLOTS && slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int transferFromChest(ServerWorld world, List<BlockPos> chestPositions, Inventory playerInventory, Item item, int requested) {
        int remaining = requested;
        for (BlockPos chestPos : chestPositions) {
            BlockEntity blockEntity = world.getBlockEntity(chestPos);
            if (!(blockEntity instanceof Inventory chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.size() && remaining > 0; slot++) {
                ItemStack source = chest.getStack(slot);
                if (source.isEmpty() || !source.isOf(item)) {
                    continue;
                }
                int desired = Math.min(remaining, source.getCount());
                int notInserted = insert(playerInventory, item, desired);
                int moved = desired - notInserted;
                if (moved > 0) {
                    source.decrement(moved);
                    if (source.isEmpty()) {
                        chest.setStack(slot, ItemStack.EMPTY);
                    }
                    chest.markDirty();
                    remaining -= moved;
                }
            }
        }
        return requested - remaining;
    }

    private static int insert(Inventory inventory, Item item, int amount) {
        int remaining = amount;
        int slots = Math.min(MAIN_SLOTS, inventory.size());
        for (int slot = 0; slot < slots && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            int moved = Math.min(remaining, stack.getMaxCount() - stack.getCount());
            if (moved > 0) {
                stack.increment(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < slots && remaining > 0; slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            int moved = Math.min(remaining, stack.getMaxCount());
            stack.setCount(moved);
            inventory.setStack(slot, stack);
            remaining -= moved;
        }
        if (remaining < amount) {
            inventory.markDirty();
        }
        return remaining;
    }

    private static final class Upload {
        private final int id;
        private final byte[][] chunks;
        private int received;

        private Upload(int id, int total) {
            this.id = id;
            this.chunks = new byte[total][];
        }
    }

    private static final class Job {
        private final UUID playerId;
        private final RegistryKey<World> dimension;
        private final BlockPos origin;
        private final List<BlockPos> chestPositions;
        private final SchematicPlan plan;
        private int nextPlacement;
        private int completed;
        private int skipped;
        private int delayTicks;
        private boolean waitingForMaterial;
        private String status = "Idle";
        private String lastSentStatus = "";

        private Job(UUID playerId, RegistryKey<World> dimension, BlockPos origin, List<BlockPos> chestPositions, SchematicPlan plan) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.origin = origin;
            this.chestPositions = chestPositions;
            this.plan = plan;
        }
    }
}
