package dev.autoschematicpro;

import dev.autoschematicpro.client.AutoSchematicProScreen;
import dev.autoschematicpro.client.ClientNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class AutoSchematicProClient implements ClientModInitializer {
    private static KeyBinding openPanel;

    @Override
    public void onInitializeClient() {
        ClientNetworking.init();
        openPanel = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.auto_schematic_pro.open",
                GLFW.GLFW_KEY_G,
                KeyBinding.Category.create(Identifier.of("auto_schematic_pro", "controls"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPanel.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    client.setScreen(new AutoSchematicProScreen());
                }
            }
        });
    }
}
