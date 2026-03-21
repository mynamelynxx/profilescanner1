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
import net.minecraft.command.argument.LongArgumentType;
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

    // Порог токенов — меняется командой /pstoken
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

        // Команда /pstoken <число>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("pstoken")
                    .then(ClientCommandManager.argument("amount", LongArgumentType.longArg(0))
                        .executes(ctx -> {
                            long newThreshold = LongArgumentType.getLong(ctx, "amount");
                            tokenThreshold = newThreshold;
                            ctx.getSource().sendFeedback(
                                Text.literal("§a[ProfileScanner] Порог токенов установлен: §f" + String.format("%,d", tokenThreshold))
                            );
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
                String name = playerQueue.get(currentIndex);
                chatErrorReceived = false;
                sendCommand(client, "profile " + name);
                client.player.sendMessage(Text.literal("§b[ProfileScanner] §f(" + (currentIndex+1) + "/" + playerQueue.size() + ") §7/profile " + name + " §8[An-" + currentAnarchy + "]"), true);
                phase = Phase.WAIT_FOR_SCREEN;
                phaseStartTime = now;
                break;
            }
            case WAIT_FOR_SCREEN: {
                if (chatErrorReceived) { chatErrorReceived = false; currentIndex++; phase = Phase.SEND_COMMAND; break; }
                if (client.currentScreen instanceof HandledScreen<?>) { phase = Phase.READ_TOKENS; phaseStartTime = now; break; }
                if (now - phaseStartTime > WAIT_FOR_SCREEN_MS) { currentIndex++; phase = Phase.SEND_COMMAND; }
                break;
            }
            case READ_TOKENS: {
                if (!(client.currentScreen instanceof HandledScreen<?>)) { currentIndex++; phase = Phase.SEND_COMMAND; break; }
                if (now - phaseStartTime < READ_TOKENS_DELAY_MS) break;
                long tokens = readTokensFromSlot(client);
                if (tokens < 0) {
                    if (now - phaseStartTime > 1500) { phase = Phase.CLOSE_SCREEN; }
                    break;
                }
                String name = playerQueue.get(currentIndex);
                if (tokens >= tokenThreshold) {
                    foundPlayer = name; foundTokens = tokens;
                    stopScan(client, "§aFOUND: §f" + name + " §a— §f" + String.format("%,d", foundTokens) + " §aтокенов on §fАнархия-" + currentAnarchy + "!");
                    return;
                }
                phase = Phase.CLOSE_SCREEN;
                break;
            }
            case CLOSE_SCREEN: {
                if (client.currentScreen != null) client.setScreen(null);
                currentIndex++;
                phase = Phase.SEND_COMMAND;
                break;
            }
            case SWITCH_ANARCHY: {
                if (now - phaseStartTime < 500) break;
                currentAnarchy++;
                sendCommand(client, "an" + currentAnarchy);
                client.player.sendMessage(Text.literal("§e[ProfileScanner] → §fАнархия-" + currentAnarchy), false);
                switchingAnarchy = true;
                phase = Phase.WAIT_FOR_SCREEN;
                phaseStartTime = now;
                break;
            }
            default: break;
        }
    }

    private long readTokensFromSlot(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return -1;
        var slots = screen.getScreenHandler().slots;
        if (HEAD_SLOT >= slots.size()) return -1;
        ItemStack stack = slots.get(HEAD_SLOT).getStack();
        if (stack.isEmpty()) return -1;
        List<Text> tooltip = stack.getTooltip(
                net.minecraft.item.Item.TooltipContext.create(client.world),
                client.player,
                net.minecraft.item.tooltip.TooltipType.Default.BASIC
        );
        for (Text line : tooltip) {
            Matcher m = TOKEN_PATTERN.matcher(line.getString());
            if (m.find()) {
                try { return Long.parseLong(m.group(1).replaceAll("[\\s,.']+", "")); }
                catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private int readAnarchyFromScoreboard(MinecraftClient client) {
        if (client.world == null) return -1;
        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective sidebar = sb.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (sidebar == null) return -1;
        Pattern p = Pattern.compile("(?i)[Аа]нархи[яЯ][\\s\\-_](\\d+)");
        Matcher m = p.matcher(sidebar.getDisplayName().getString());
        if (m.find()) return Integer.parseInt(m.group(1));
        for (var entry : sb.getScoreboardEntries(sidebar)) {
            Matcher em = p.matcher(entry.owner());
            if (em.find()) return Integer.parseInt(em.group(1));
            var team = sb.getTeam(entry.owner());
            if (team != null) {
                Matcher dm = p.matcher(team.getPrefix().getString() + entry.owner() + team.getSuffix().getString());
                if (dm.find()) return Integer.parseInt(dm.group(1));
            }
        }
        return -1;
    }

    private void startScan(MinecraftClient client) {
        buildPlayerQueue(client);
        if (playerQueue.isEmpty()) { client.player.sendMessage(Text.literal("§c[ProfileScanner] No players in tab!"), false); return; }
        currentIndex = 0; scanning = true; foundPlayer = null;
        chatErrorReceived = false; switchingAnarchy = false;
        phase = Phase.SEND_COMMAND; phaseStartTime = System.currentTimeMillis();
        client.player.sendMessage(Text.literal("§a[ProfileScanner] Порог: §f" + String.format("%,d", tokenThreshold) + " §aтокенов | Анархия-" + currentAnarchy + " | §f" + playerQueue.size() + " §aигроков. L = стоп."), false);
    }

    private void buildPlayerQueue(MinecraftClient client) {
        List<PlayerListEntry> entries = new ArrayList<>(client.getNetworkHandler().getPlayerList());
        entries.sort((a, b) -> {
            Team teamA = a.getScoreboardTeam();
            Team teamB = b.getScoreboardTeam();
            String nameA = teamA != null ? teamA.getName() + a.getProfile().getName() : a.getProfile().getName();
            String nameB = teamB != null ? teamB.getName() + b.getProfile().getName() : b.getProfile().getName();
            return nameA.compareToIgnoreCase(nameB);
        });
        playerQueue.clear();
        for (int i = 0; i < Math.min(entries.size(), 30); i++) {
            String name = entries.get(i).getProfile().getName();
            if (name != null && !name.isBlank()) playerQueue.add(name);
        }
    }

    private void stopScan(MinecraftClient client, String reason) {
        scanning = false; phase = Phase.IDLE; playerQueue.clear();
        currentIndex = 0; chatErrorReceived = false; switchingAnarchy = false;
        if (client.currentScreen != null) client.setScreen(null);
        client.player.sendMessage(Text.literal("§c[ProfileScanner] " + reason), false);
    }

    private void sendCommand(MinecraftClient client, String command) {
        client.getNetworkHandler().sendChatCommand(command);
    }
}
