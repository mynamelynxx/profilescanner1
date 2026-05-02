package com.profilescanner;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileScannerMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("profilescanner");
    private static KeyBinding startKey;
    private static KeyBinding stopKey;
    private boolean scanning = false;
    private List<String> playerQueue = new ArrayList<>();
    private int currentIndex = 0;
    private enum Phase { IDLE, SEND_COMMAND, WAIT_FOR_SCREEN, READ_TOKENS, CLOSE_SCREEN, SWITCH_ANARCHY, RETURNING }
    private Phase phase = Phase.IDLE;
    private long phaseStartTime = 0;
    private volatile boolean chatErrorReceived = false;
    private boolean switchingAnarchy = false;
    private int currentAnarchy = -1;
    private static final long WAIT_FOR_SCREEN_MS = 3000;
    private static final long READ_TOKENS_DELAY_MS = 300;
    private static final long SWITCH_WAIT_MS = 3000;
    private static final long RETURN_WAIT_MS = 1500;
    private static final int HEAD_SLOT = 4;
    private long tokenThreshold = 120000;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("Токенов:\\s*([\\d,. ]+)");
    private static final Pattern ANARCHY_PATTERN = Pattern.compile("(?i)[Аа]нархи[яЯ][\\s\\-_](\\d+)");

    @Override
    public void onInitializeClient() {
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.profilescanner.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.profilescanner"));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.profilescanner.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, "category.profilescanner"));

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> onServerMessage(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) onServerMessage(message.getString()); });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("pstoken")
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "amount");
                            try {
                                tokenThreshold = Long.parseLong(raw.replace(",", "").replace(".", ""));
                                ctx.getSource().sendFeedback(Text.literal("§a[ProfileScanner] Порог: §f" + String.format("%,d", tokenThreshold)));
                            } catch (NumberFormatException e) {
                                ctx.getSource().sendFeedback(Text.literal("§c[ProfileScanner] Неверное число: " + raw));
                            }
                            return 1;
                        }))
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Text.literal("§e[ProfileScanner] Порог: §f" + String.format("%,d", tokenThreshold) + " §7| /pstoken <число>"));
                        return 1;
                    }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        LOGGER.info("ProfileScanner loaded.");
    }

    private void onServerMessage(String text) {
        if (scanning && phase == Phase.WAIT_FOR_SCREEN && !switchingAnarchy) chatErrorReceived = true;
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        if (stopKey.wasPressed() && scanning) { stopScan(client, "Stopped by player."); return; }

        if (startKey.wasPressed()) {
            if (scanning) {
                client.player.sendMessage(Text.literal("§e[ProfileScanner] Уже запущен! L = стоп."), true);
            } else {
                currentAnarchy = readAnarchyFromScoreboard(client);
                if (currentAnarchy < 0) { client.player.sendMessage(Text.literal("§c[ProfileScanner] Не удалось определить анархию!"), false); return; }
                startScan(client);
            }
        }

        if (!scanning) return;
        long now = System.currentTimeMillis();

        // Проверяем не выкинуло ли в хаб (скорборд не показывает анархию)
        if (phase != Phase.RETURNING && phase != Phase.SWITCH_ANARCHY && !switchingAnarchy) {
            int sbAnarchy = readAnarchyFromScoreboard(client);
            if (sbAnarchy < 0) {
                LOGGER.info("[ProfileScanner] Kicked to hub! Will return to Анархия-{}", currentAnarchy);
                if (client.currentScreen != null) client.setScreen(null);
                client.player.sendMessage(Text.literal("§e[ProfileScanner] Выкинуло в хаб! Возвращаюсь на Анархия-" + currentAnarchy + "..."), false);
                // НЕ сбрасываем currentIndex — продолжим с того же игрока
                phase = Phase.RETURNING;
                phaseStartTime = now;
                return;
            }
        }

        // Фаза возврата
        if (phase == Phase.RETURNING) {
            if (now - phaseStartTime >= RETURN_WAIT_MS) {
                sendCommand(client, "an" + currentAnarchy);
                // Ждём загрузки анархии перед продолжением
                switchingAnarchy = true;
                phase = Phase.WAIT_FOR_SCREEN;
                phaseStartTime = now;
            }
            return;
        }

        if (switchingAnarchy && phase == Phase.WAIT_FOR_SCREEN) {
            if (now - phaseStartTime >= SWITCH_WAIT_MS) {
                switchingAnarchy = false;
                chatErrorReceived = false;
                // При возврате после кика не пересобираем очередь и не сбрасываем индекс
                // При переходе на новую анархию — пересобираем
                if (currentIndex >= playerQueue.size()) {
                    buildPlayerQueue(client);
                    currentIndex = 0;
                }
                if (playerQueue.isEmpty()) { stopScan(client, "Нет игроков на Анархия-" + currentAnarchy); return; }
                phase =
