package com.profilescanner;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import com.mojang.brigadier.arguments.StringArgumentType;

public class ProfileScannerMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("profilescanner");

    private static KeyBinding startKey;
    private static KeyBinding stopKey;

    private boolean scanning = false;
    private List<String> playerQueue = new ArrayList<>();
    private int currentIndex = 0;

    private enum Phase {
        IDLE, SEND_COMMAND, WAIT_FOR_SCREEN, READ_TOKENS, CLOSE_SCREEN, SWITCH_ANARCHY
    }
    private Phase phase = Phase.IDLE;
    private long phaseStartTime = 0;
    private volatile boolean chatErrorReceived = false;
    private boolean switchingAnarchy = false;

    private String foundPlayer = null;
    private long foundTokens = 0;
    private int currentAnarchy = -1;

    private static final long WAIT_FOR_SCREEN_MS   = 3000;
    private static final long READ_TOKENS_DELAY_MS = 300;
    private static final long SWITCH_WAIT_MS       = 3000;
    private static final int  HEAD_SLOT            = 4;

    private long tokenThreshold = 120_000;

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)токен[^:]*:\\s*([\\d\\s,.']+)"
    );

    @Override
    public void onInitializeClient() {
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.profilescanner.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.profilescanner"
        ));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.profilescanner.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, "category.profilescanner"
        ));

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                onServerMessage(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onServerMessage(message.getString());
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                literal("pstoken")
                    .then(argument("amount", StringArgumentType.word())
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "amount");
                            try {
                                long newThreshold = Long.parseLong(raw.replace(",", "").replace(".", ""));
                                tokenThreshold = newThreshold;
                                ctx.getSource().sendFeedback(
                                    Text.literal("§a[ProfileScanner] Порог токенов установлен: §f" + String.format("%,d", tokenThreshold))
                                );
                            } catch (NumberFormatException e) {
                                ctx.getSource().sendFeedback(
                                    Text.literal("§c[ProfileScanner] Неверное число: " + raw)
                                );
                            }
                            return 1;
                        })
                    )
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(
                            Text.literal("§e[ProfileScanner] Текущий порог: §f" + String.format("%,d", tokenThreshold) + " §7| Использование: /pstoken <число>")
                        );
                        return 1;
                    })
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        LOGGER.info("ProfileScanner loaded. K = start, L = stop. /pstoken to set threshold.");
    }

    private void onServerMessage(String text) {
        if (scanning && phase == Phase.WAIT_FOR_SCREEN && !switchingAnarchy) {
            chatErrorReceived = true;
        }
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        if (stopKey.wasPressed() && scanning) { stopScan(client, "Stopped by player."); return; }

        if (startKey.wasPressed()) {
            if (scanning) {
                client.player.sendMessage(Text.literal("§e[ProfileScanner] Already running! Press L to stop."), true);
            } else {
                currentAnarchy = readAnarchyFromScoreboard(client);
                if (currentAnarchy < 0) {
                    client.player.sendMessage(Text.literal("§c[ProfileScanner] Could not detect anarchy from scoreboard!"), false);
                    return;
                }
                startScan(client);
            }
        }

        if (!scanning) return;
        long now = System.currentTimeMillis();

        if (switchingAnarchy && phase == Phase.WAIT_FOR_SCREEN) {
            if (now - phaseStartTime >= SWITCH_WAIT_MS) {
                switchingAnarchy = false;
                chatErrorReceived = false;
                buildPlayerQueue(client);
                if (playerQueue.isEmpty()) { stopScan(client, "No players on Анархия-" + currentAnarchy); return; }
                currentIndex = 0;
                phase = Phase.SEND_COMMAND;
            }
            return;
        }

        switch (phase) {
            case SEND_COMMAND: {
                if (client.currentScreen != null) client.setScreen(null);
                if (currentIndex >= playerQueue.size()) {
                    client.player.sendMessage(Text.literal("§e[ProfileScanner] No one with " + String.format("%,d", tokenThreshold) + "+ tokens on Анархия-" + currentAnarchy + ". Switching..."), false);
                    phase = Phase.SWITCH_ANARCHY;
                    phaseStartTime = now;
                    break;
                }
