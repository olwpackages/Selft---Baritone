package baritone.client;

import baritone.BaritoneMod;
import baritone.mining.MiningController;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.List;

public final class BaritoneClient implements ClientModInitializer {
    private static final MiningController MINING = new MiningController();
    private static final KeyMapping OPEN_GUI_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.baritone.open_gui", InputConstants.Type.KEYSYM, 344, KeyMapping.Category.MISC));
    private static boolean guiRequested;

    @Override
    public void onInitializeClient() {
        registerDollarCommands();
        ClientTickEvents.START_CLIENT_TICK.register(MINING::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (OPEN_GUI_KEY.consumeClick()) {
                guiRequested = true;
            }
            if (guiRequested) {
                guiRequested = false;
                client.setScreenAndShow(new BaritoneScreen(client, BaritoneConfig.get()));
            }
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> MINING.renderDebug());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (MINING.isActive()) {
                client.execute(() -> MINING.stop(client));
            }
        });
        BaritoneMod.LOGGER.info("Client commands ready: $info, $gui and $mine");
    }

    private static void registerDollarCommands() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (trimmed.equalsIgnoreCase("$info")) {
                sendInfo();
                return false;
            }
            if (trimmed.equalsIgnoreCase("$gui")) {
                guiRequested = true;
                return false;
            }
            if (trimmed.equalsIgnoreCase("$mine stop")) {
                MINING.stop(Minecraft.getInstance());
                return false;
            }
            if (!trimmed.regionMatches(true, 0, "$mine", 0, 5)
                    || (trimmed.length() > 5 && !Character.isWhitespace(trimmed.charAt(5)))) return true;

            handleMine(trimmed.substring(5).trim());
            return false;
        });
    }

    private static void sendInfo() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "[Baritone Reborn] Fabric 26.2. Команды: $info, $gui, $mine <block>, $mine stop"));
        }
    }

    private static void handleMine(String arguments) {
        Minecraft client = Minecraft.getInstance();
        if (arguments.isBlank()) {
            client.player.sendSystemMessage(Component.literal(
                    "[Baritone] Использование: $mine minecraft:diamond_ore,minecraft:iron_ore"));
            return;
        }

        if (arguments.contains(",") || arguments.indexOf(' ') >= 0 || arguments.indexOf('\t') >= 0) {
            client.player.sendSystemMessage(Component.literal(
                    "[Baritone] Можно выбрать только одну цель: $mine minecraft:diamond_ore"));
            return;
        }

        Identifier id = Identifier.tryParse(arguments);
        if (id == null || BuiltInRegistries.BLOCK.getOptional(id).isEmpty()) {
            client.player.sendSystemMessage(Component.literal(
                    "[Baritone] Неизвестный блок: " + arguments));
            return;
        }
        MINING.start(client, List.of(BuiltInRegistries.BLOCK.getOptional(id).orElseThrow()));
    }
}
