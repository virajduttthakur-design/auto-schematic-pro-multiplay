package dev.autoschematicpro.client;

import dev.autoschematicpro.build.SchematicLoader;
import dev.autoschematicpro.build.SchematicPlan;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AutoSchematicProScreen extends Screen {
    private static final int LIST_ROWS = 7;
    private List<SchematicPlan> plans = List.of();
    private final List<GlassButton> schematicButtons = new ArrayList<>();
    private GlassButton previousPageButton;
    private GlassButton nextPageButton;
    private SchematicPlan selectedPlan;
    private int selectedIndex = -1;
    private int page;
    private BlockPos origin;
    private List<BlockPos> chestPositions = List.of();
    private String message = "Choose a .schem file, then set an origin and a double chest.";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listY;
    private int rowWidth;

    public AutoSchematicProScreen() {
        super(Text.literal("Auto Schematic Pro"));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(860, width - 24);
        panelHeight = Math.min(500, height - 24);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        listX = panelX + 22;
        listY = panelY + 112;
        rowWidth = Math.min(306, (panelWidth - 360) / 2);

        loadSchematics();
        schematicButtons.clear();
        for (int row = 0; row < LIST_ROWS; row++) {
            final int rowIndex = row;
            int buttonY = listY + row * 31;
            GlassButton button = new GlassButton(listX, buttonY, rowWidth, 26, Text.literal(""),
                    () -> selectPageEntry(rowIndex));
            schematicButtons.add(addDrawableChild(button));
        }

        int rightX = listX + rowWidth + 28;
        int rightWidth = panelX + panelWidth - 24 - rightX;
        int controlWidth = Math.min(240, rightWidth);
        int controlX = rightX + Math.max(0, (rightWidth - controlWidth) / 2);
        int controlY = panelY + panelHeight - 130;
        addDrawableChild(new GlassButton(controlX, controlY, controlWidth, 22, Text.literal("Set origin from crosshair"), this::captureOrigin));
        addDrawableChild(new GlassButton(controlX, controlY + 28, controlWidth, 22, Text.literal("Use double chest in crosshair"), this::captureChest));
        addDrawableChild(new GlassButton(controlX, controlY + 58, controlWidth, 24, Text.literal("Start build"), this::startBuild));
        int halfButtonWidth = Math.max(86, (controlWidth - 8) / 2);
        addDrawableChild(new GlassButton(controlX, controlY + 89, halfButtonWidth, 22, Text.literal("Stop build"), this::stopBuild));
        addDrawableChild(new GlassButton(controlX + halfButtonWidth + 8, controlY + 89, halfButtonWidth, 22, Text.literal("Close"), this::close));

        int pagerY = listY + LIST_ROWS * 31 + 8;
        previousPageButton = addDrawableChild(new GlassButton(listX, pagerY, 46, 20, Text.literal("‹"), () -> {
            page = Math.max(0, page - 1);
            refreshListLabels();
        }));
        nextPageButton = addDrawableChild(new GlassButton(listX + rowWidth - 46, pagerY, 46, 20, Text.literal("›"), () -> {
            page = Math.min(Math.max(0, (plans.size() - 1) / LIST_ROWS), page + 1);
            refreshListLabels();
        }));
        refreshListLabels();
    }

    private void loadSchematics() {
        try {
            plans = SchematicLoader.loadAll();
            if (selectedPlan != null) {
                for (int i = 0; i < plans.size(); i++) {
                    if (plans.get(i).name().equals(selectedPlan.name())) {
                        selectedIndex = i;
                        selectedPlan = plans.get(i);
                        break;
                    }
                }
            }
            if (plans.isEmpty()) {
                selectedPlan = null;
                selectedIndex = -1;
                message = "No readable .schem files found in " + SchematicLoader.schematicDirectory();
            }
        } catch (IOException exception) {
            plans = List.of();
            selectedPlan = null;
            selectedIndex = -1;
            message = "Could not read the schematics folder.";
        }
    }

    private void selectPageEntry(int row) {
        int index = page * LIST_ROWS + row;
        if (index >= 0 && index < plans.size()) {
            selectedIndex = index;
            selectedPlan = plans.get(index);
            message = "Selected " + selectedPlan.name();
            refreshListLabels();
        }
    }

    private void refreshListLabels() {
        for (int row = 0; row < schematicButtons.size(); row++) {
            int index = page * LIST_ROWS + row;
            if (index < plans.size()) {
                String prefix = index == selectedIndex ? "◆  " : "◇  ";
                schematicButtons.get(row).setMessage(Text.literal(prefix + plans.get(index).name()));
                schematicButtons.get(row).visible = true;
                schematicButtons.get(row).active = true;
            } else {
                schematicButtons.get(row).setMessage(Text.literal("—"));
                schematicButtons.get(row).visible = false;
                schematicButtons.get(row).active = false;
            }
        }
        if (previousPageButton != null) {
            previousPageButton.visible = page > 0;
            previousPageButton.active = page > 0;
        }
        if (nextPageButton != null) {
            boolean hasNextPage = (page + 1) * LIST_ROWS < plans.size();
            nextPageButton.visible = hasNextPage;
            nextPageButton.active = hasNextPage;
        }
    }

    private void captureOrigin() {
        if (client == null || client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            message = "Aim at the block that should be the schematic's lower corner.";
            return;
        }
        origin = copy(((BlockHitResult) client.crosshairTarget).getBlockPos());
        message = "Origin set to " + origin.toShortString();
    }

    private void captureChest() {
        if (client == null || client.world == null || client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            message = "Aim at one half of a double chest.";
            return;
        }
        BlockPos selected = ((BlockHitResult) client.crosshairTarget).getBlockPos();
        BlockState state = client.world.getBlockState(selected);
        if (!(state.getBlock() instanceof ChestBlock)) {
            message = "The crosshair is not on a chest.";
            return;
        }
        Direction facing = state.get(ChestBlock.FACING);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (direction.getAxis() == facing.getAxis()) {
                continue;
            }
            BlockPos neighbor = selected.offset(direction);
            BlockState neighborState = client.world.getBlockState(neighbor);
            if (neighborState.getBlock() == state.getBlock()
                    && neighborState.getBlock() instanceof ChestBlock
                    && neighborState.get(ChestBlock.FACING) == facing) {
                chestPositions = List.of(copy(selected), copy(neighbor));
                message = "Double chest linked at " + selected.toShortString() + " and " + neighbor.toShortString();
                return;
            }
        }
        chestPositions = List.of();
        message = "Select a connected double chest (two matching chest blocks).";
    }

    private void startBuild() {
        if (client == null || client.player == null || client.world == null || selectedPlan == null) {
            message = "Select a schematic in a loaded world first.";
            return;
        }
        if (!ClientNetworking.serverSupportsMod()) {
            message = "This server doesn't have Auto Schematic Pro installed.";
            return;
        }
        if (origin == null) {
            message = "Set the schematic origin first.";
            return;
        }
        if (chestPositions.size() != 2) {
            message = "Select a connected double chest first.";
            return;
        }
        String error = ClientNetworking.startBuild(selectedPlan, copy(origin), List.copyOf(chestPositions));
        message = error != null ? error : "Schematic sent. The server will build it at a steady pace.";
    }

    private void stopBuild() {
        if (ClientNetworking.serverSupportsMod()) {
            ClientNetworking.stopBuild();
            message = "Stopping the active build.";
        }
    }

    private static BlockPos copy(BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        drawPanel(context);
    }

    private void drawPanel(DrawContext context) {
        int left = panelX;
        int top = panelY;
        int right = panelX + panelWidth;
        int bottom = panelY + panelHeight;
        context.fill(left - 2, top - 2, right + 2, bottom + 2, 0xFF7DE8FF);
        context.fill(left, top, right, bottom, 0xE9101D2C);
        context.fill(left + 1, top + 1, right - 1, top + 3, 0xA8D9FBFF);
        context.fill(left + 10, top + 10, right - 10, top + 58, 0x702B5366);
        context.fill(left + 18, top + 76, left + 18 + rowWidth + 8, bottom - 54, 0x813B6373);
        int rightX = listX + rowWidth + 28;
        context.fill(rightX - 10, top + 76, right - 18, bottom - 54, 0x75456A7B);

        draw(context, "AUTO SCHEMATIC PRO", left + 24, top + 18, 0xFFF1FCFF);
        draw(context, "SCHEMATIC BUILDER  /  1.21.11", left + 24, top + 38, 0xFF9BC9D8);
        draw(context, "SCHEMATICS", listX + 8, top + 87, 0xFFE0F8FF);
        draw(context, "BUILD SETUP", rightX + 4, top + 87, 0xFFE0F8FF);

        if (plans.isEmpty()) {
            draw(context, "Drop .schem files into:", listX + 12, listY + 6, 0xFFC5DFE8);
            draw(context, "Minecraft folder / schematics", listX + 12, listY + 22, 0xFF8EB6C3);
        }

        int textX = rightX + 6;
        if (selectedPlan == null) {
            draw(context, "Select a schematic to see its size and materials.", textX, top + 116, 0xFFC6DCE4);
        } else {
            draw(context, selectedPlan.name(), textX, top + 116, 0xFFFFFFFF);
            draw(context, selectedPlan.width() + " × " + selectedPlan.height() + " × " + selectedPlan.length()
                    + " blocks    •    " + selectedPlan.placements().size() + " placements", textX, top + 134, 0xFF9FC8D5);
            draw(context, "MATERIALS", textX, top + 164, 0xFFE0F8FF);
            int line = 0;
            for (Map.Entry<Item, Integer> material : selectedPlan.materialCounts().entrySet()) {
                if (line >= 6) {
                    draw(context, "and " + (selectedPlan.materialCounts().size() - line) + " more…", textX + 8, top + 184 + line * 15, 0xFF91B9C6);
                    break;
                }
                draw(context, "•  " + material.getKey().getName().getString(), textX + 8, top + 184 + line * 15, 0xFFC8E2EA);
                String count = "× " + material.getValue();
                int countX = right - 28 - textRenderer.getWidth(count);
                draw(context, count, countX, top + 184 + line * 15, 0xFF8DC7D8);
                line++;
            }
        }

        draw(context, "Origin  " + (origin == null ? "not set" : origin.toShortString()), textX, bottom - 160, 0xFFB8D7E1);
        draw(context, "Double chest  " + (chestPositions.size() == 2 ? "linked" : "not set"), textX, bottom - 145, 0xFFB8D7E1);
        String progress = ClientBuildState.total() == 0
                ? ClientBuildState.status()
                : ClientBuildState.status() + "  ·  " + ClientBuildState.completed() + "/" + ClientBuildState.total();
        int progressX = right - 24 - textRenderer.getWidth(progress);
        draw(context, progress, progressX, top + 18, 0xFF7FE1F3);
        String modeLabel = (client != null && client.isInSingleplayer() ? "SINGLE-PLAYER" : "MULTIPLAYER") + "  /  SERVER BUILD";
        draw(context, modeLabel, right - 24 - textRenderer.getWidth(modeLabel), top + 38, 0xFF8FC1CF);
        draw(context, message, left + 22, bottom - 32, 0xFFA8CED9);
    }

    private void draw(DrawContext context, String text, int x, int y, int color) {
        context.drawTextWithShadow(textRenderer, Text.literal(text), x, y, color);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private final class GlassButton extends ClickableWidget {
        private final Runnable action;

        private GlassButton(int x, int y, int width, int height, Text label, Runnable action) {
            super(x, y, width, height, label);
            this.action = action;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            int x = getX();
            int y = getY();
            int right = x + width;
            int bottom = y + height;
            int face = !active ? 0x72465C66 : isHovered() ? 0xC63E8296 : 0xA8205062;
            int edge = isFocused() ? 0xFFE7FCFF : isHovered() ? 0xFF9BEAFF : 0xA76FC6D8;
            context.fill(x, y, right, bottom, face);
            context.fill(x, y, right, y + 1, edge);
            context.fill(x, bottom - 1, right, bottom, edge);
            context.fill(x, y, x + 1, bottom, edge);
            context.fill(right - 1, y, right, bottom, edge);
            int textColor = active ? 0xFFF0FCFF : 0xFF9AB1B9;
            Text label = getMessage();
            int textX = x + Math.max(4, (width - textRenderer.getWidth(label)) / 2);
            int textY = y + (height - textRenderer.fontHeight) / 2;
            context.drawTextWithShadow(textRenderer, label, textX, textY, textColor);
        }

        @Override
        public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
            if (active) {
                action.run();
            }
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
