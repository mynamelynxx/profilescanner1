package com.profilescanner;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileScannerMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("profilescanner");

    private static KeyBinding startKey;
    private static KeyBinding stopKey;

    // ── Scanner state ────────────────────────────────────────────────────────
    private boolean scanning = false;
    private List<String> playerQueue = new ArrayList<>();
    private int currentIndex = 0;

    private enum Phase {
        IDLE,
        SEND_COMMAND,       // send /profile <name>
        WAIT_FOR_SCREEN,    // wait until GUI opens (or chat error arrives)
        READ_TOKENS,        // GUI is open — read slot 4 tooltip
        CLOSE_SCREEN,       // close GUI and go next
        SWITCH_ANARCHY      // send /an<N+1> then rebuild queue
    }
    private Phase phase = Phase.IDLE;
    private long phaseStartTime = 0;

    // Set to true when server sends a chat message while we wait for the screen
    // (means /profile returned an error instead of opening GUI)
    private volatile boolean chatErrorReceived = false;

    // When we found a rich player
    private String foundPlayer = null;
    private long foundTokens = 0;

    // Anarchy switching
    private int currentAnarchy = -1; // will be read from scoreboard

    // ── Timeouts ─────────────────────────────────────────────────────────────
    private static final long WAIT_FOR_SCREEN_MS  = 3000;
    private static final long READ_TOKENS_DELAY_MS = 300; // wait a bit after screen opens before reading
    private static final long SWITCH_WAIT_MS       = 3000; // wait after /an command before scanning

    // Token threshold — players with >= this many tokens are "found"
    private static final long TOKEN_THRESHOLD = 120_000;

    // HEAD_SLOT: top row, middle = slot index 4 (0-indexed in a 9-wide grid)
    private static final int HEAD_SLOT = 4;

    // Regex to extract token count from tooltip lines like "Токенов: 316,376" or "Токенов: 316376"
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)токен[^:]*:\\s*([\\d\\s,.']+)"
    );

    @Override
    public void onInitializeClient() {
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.profilescanner.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.profilescanner"
        ));

        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.profilescanner.stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                "category.profilescanner"
        ));

        // Chat/system message listener
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                onServerMessage(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onServerMessage(message.getString());
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        LOGGER.info("ProfileScanner loaded. K = start, L = stop.");
    }

    // ── Chat listener ─────────────────────────────────────────────────────────

    private void onServerMessage(String text) {
        if (scanning && phase == Phase.WAIT_FOR_SCREEN) {
            LOGGER.info("[ProfileScanner] Chat message while waiting (error?): {}", text);
            chatErrorReceived = true;
        }
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        if (stopKey.wasPressed() && scanning) {
            stopScan(client, "Stopped by player.");
            return;
        }

        if (startKey.wasPressed()) {
            if (scanning) {
                client.player.sendMessage(Text.literal("§e[ProfileScanner] Already running! Press L to stop."), true);
            } else {
                currentAnarchy = readAnarchyFromScoreboard(client);
                if (currentAnarchy < 0) {
                    client.player.sendMessage(Text.literal("§c[ProfileScanner] Could not detect anarchy number from scoreboard!"), false);
                    return;
                }
                startScan(client);
            }
        }

        if (!scanning) return;

        long now = System.currentTimeMillis();

        switch (phase) {

            // ── Send /profile ────────────────────────────────────────────────
            case SEND_COMMAND: {
                if (client.currentScreen != null) client.setScreen(null);

                if (currentIndex >= playerQueue.size()) {
                    // All players in this anarchy scanned — switch to next
                    client.player.sendMessage(
                            Text.literal("§e[ProfileScanner] No player with §f" + TOKEN_THRESHOLD + "§e+ tokens on §fАнархия-" + currentAnarchy + "§e. Switching..."),
                            false
                    );
                    phase = Phase.SWITCH_ANARCHY;
                    phaseStartTime = now;
                    break;
                }

                String playerName = playerQueue.get(currentIndex);
                chatErrorReceived = false;
                sendCommand(client, "profile " + playerName);
                client.player.sendMessage(
                        Text.literal("§b[ProfileScanner] §f(" + (currentIndex + 1) + "/" + playerQueue.size() + ") §7/profile " + playerName + " §8[An-" + currentAnarchy + "]"),
                        true
                );
                phase = Phase.WAIT_FOR_SCREEN;
                phaseStartTime = now;
                break;
            }

            // ── Wait for GUI to open ─────────────────────────────────────────
            case WAIT_FOR_SCREEN: {
                if (chatErrorReceived) {
                    // Server responded with error in chat → profile unavailable
                    chatErrorReceived = false;
                    currentIndex++;
                    phase = Phase.SEND_COMMAND;
                    break;
                }

                if (client.currentScreen instanceof HandledScreen<?>) {
                    // GUI opened — wait a tiny bit for items to load
                    phase = Phase.READ_TOKENS;
                    phaseStartTime = now;
                    break;
                }

                if (now - phaseStartTime > WAIT_FOR_SCREEN_MS) {
                    LOGGER.warn("[ProfileScanner] Timeout waiting for screen: {}", playerQueue.get(currentIndex));
                    currentIndex++;
                    phase = Phase.SEND_COMMAND;
                }
                break;
            }

            // ── Read tokens from slot 4 tooltip ─────────────────────────────
            case READ_TOKENS: {
                // Make sure screen is still open
                if (!(client.currentScreen instanceof HandledScreen<?>)) {
                    // Screen closed before we could read
                    currentIndex++;
                    phase = Phase.SEND_COMMAND;
                    break;
                }

                // Give items a moment to arrive from server
                if (now - phaseStartTime < READ_TOKENS_DELAY_MS) break;

                long tokens = readTokensFromSlot(client);

                if (tokens < 0) {
                    // Items not loaded yet — keep waiting (up to 1.5s total)
                    if (now - phaseStartTime > 1500) {
                        LOGGER.warn("[ProfileScanner] Could not read tokens for {}", playerQueue.get(currentIndex));
                        phase = Phase.CLOSE_SCREEN;
                    }
                    break;
                }

                String playerName = playerQueue.get(currentIndex);
                LOGGER.info("[ProfileScanner] {} — tokens: {}", playerName, tokens);

                if (tokens >= TOKEN_THRESHOLD) {
                    // Found target!
                    foundPlayer = playerName;
                    foundTokens = tokens;
                    stopScan(client, "§aFOUND: §f" + foundPlayer + " §ahas §f" + foundTokens + " §aтокенов on §fАнархия-" + currentAnarchy + "!");
                    return;
                }

                // Not enough tokens — close and move on
                phase = Phase.CLOSE_SCREEN;
                break;
            }

            // ── Close GUI ────────────────────────────────────────────────────
            case CLOSE_SCREEN: {
                if (client.currentScreen != null) client.setScreen(null);
                currentIndex++;
                phase = Phase.SEND_COMMAND;
                break;
            }

            // ── Switch to next anarchy ───────────────────────────────────────
            case SWITCH_ANARCHY: {
                if (now - phaseStartTime < 500) break; // small delay before sending

                currentAnarchy++;
                sendCommand(client, "an" + currentAnarchy);
                client.player.sendMessage(
                        Text.literal("§e[ProfileScanner] Switching to §fАнархия-" + currentAnarchy + "§e..."),
                        false
                );

                // Wait for the world/tab to update, then rebuild queue
                phaseStartTime = now;
                phase = Phase.WAIT_FOR_SCREEN; // reuse wait phase as "wait for teleport"

                // Schedule queue rebuild after SWITCH_WAIT_MS
                // We'll handle this by checking time in a special way below:
                // Actually let's use a dedicated small state via a flag
                switchingAnarchy = true;
                break;
            }

            default:
                break;
        }

        // Handle anarchy switch wait separately
        if (switchingAnarchy && phase == Phase.WAIT_FOR_SCREEN) {
            if (now - phaseStartTime >= SWITCH_WAIT_MS) {
                switchingAnarchy = false;
                chatErrorReceived = false;
                // Rebuild player queue for new anarchy
                buildPlayerQueue(client);
                if (playerQueue.isEmpty()) {
                    stopScan(client, "No players found on Анархия-" + currentAnarchy);
                    return;
                }
                currentIndex = 0;
                phase = Phase.SEND_COMMAND;
            }
        }
    }

    private boolean switchingAnarchy = false;

    // ── Token reading ─────────────────────────────────────────────────────────

    /**
     * Reads slot HEAD_SLOT (index 4) from the open HandledScreen
     * and parses the token count from its tooltip.
     * Returns -1 if the slot is empty or tooltip not found.
     */
    private long readTokensFromSlot(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return -1;

        var handler = screen.getScreenHandler();
        var slots = handler.slots;

        if (HEAD_SLOT >= slots.size()) return -1;

        Slot slot = slots.get(HEAD_SLOT);
        ItemStack stack = slot.getStack();

        if (stack.isEmpty()) return -1;

        // Get all tooltip lines
        List<Text> tooltip = stack.getTooltip(
                net.minecraft.item.Item.TooltipContext.create(client.world),
                client.player,
                net.minecraft.item.tooltip.TooltipType.Default.BASIC
        );

        for (Text line : tooltip) {
            String plain = line.getString();
            Matcher m = TOKEN_PATTERN.matcher(plain);
            if (m.find()) {
                String raw = m.group(1).replaceAll("[\\s,.']+", "");
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    LOGGER.warn("[ProfileScanner] Failed to parse token value: '{}'", raw);
                }
            }
        }

        return -1; // token line not found yet
    }

    // ── Scoreboard reading ────────────────────────────────────────────────────

    /**
     * Reads the current anarchy number from the sidebar scoreboard.
     * Looks for a line matching "Анархия-NNN" or "Анархия NNN".
     */
    private int readAnarchyFromScoreboard(MinecraftClient client) {
        if (client.world == null) return -1;

        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(
                net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR
        );
        if (sidebar == null) return -1;

        Pattern anarchyPattern = Pattern.compile("(?i)[Аа]нархи[яЯ][\\s\\-_](\\d+)");

        // Check objective display name
        String objName = sidebar.getDisplayName().getString();
        Matcher m = anarchyPattern.matcher(objName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // Check all score entries
        for (var entry : scoreboard.getScoreboardEntries(sidebar)) {
            String entryName = entry.owner();
            Matcher em = anarchyPattern.matcher(entryName);
            if (em.find()) return Integer.parseInt(em.group(1));

            // Also check display name if available
            var team = scoreboard.getTeam(entryName);
            if (team != null) {
                String display = team.getPrefix().getString() + entryName + team.getSuffix().getString();
                Matcher dm = anarchyPattern.matcher(display);
                if (dm.find()) return Integer.parseInt(dm.group(1));
            }
        }

        return -1;
    }

    // ── Scan management ───────────────────────────────────────────────────────

    private void startScan(MinecraftClient client) {
        buildPlayerQueue(client);

        if (playerQueue.isEmpty()) {
            client.player.sendMessage(Text.literal("§c[ProfileScanner] No players found in tab list!"), false);
            return;
        }

        currentIndex = 0;
        scanning = true;
        foundPlayer = null;
        chatErrorReceived = false;
        switchingAnarchy = false;
        phase = Phase.SEND_COMMAND;
        phaseStartTime = System.currentTimeMillis();

        client.player.sendMessage(
                Text.literal("§a[ProfileScanner] Starting on §fАнархия-" + currentAnarchy +
                        " §a— §f" + playerQueue.size() + " §aplayers. L to stop."),
                false
        );
    }

    private void buildPlayerQueue(MinecraftClient client) {
        Collection<PlayerListEntry> entries = client.getNetworkHandler().getPlayerList();

        List<PlayerListEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            String na = a.getDisplayName() != null ? a.getDisplayName().getString() : a.getProfile().getName();
            String nb = b.getDisplayName() != null ? b.getDisplayName().getString() : b.getProfile().getName();
            return na.compareToIgnoreCase(nb);
        });

        int limit = Math.min(sorted.size(), 40); // 2 columns × 20 rows
        playerQueue.clear();
        for (int i = 0; i < limit; i++) {
            String name = sorted.get(i).getProfile().getName();
            if (name != null && !name.isBlank()) playerQueue.add(name);
        }
    }

    private void stopScan(MinecraftClient client, String reason) {
        scanning = false;
        phase = Phase.IDLE;
        playerQueue.clear();
        currentIndex = 0;
        chatErrorReceived = false;
        switchingAnarchy = false;
        if (client.currentScreen != null) client.setScreen(null);
        client.player.sendMessage(Text.literal("§c[ProfileScanner] " + reason), false);
    }

    private void sendCommand(MinecraftClient client, String command) {
        client.getNetworkHandler().sendChatCommand(command);
    }
}
