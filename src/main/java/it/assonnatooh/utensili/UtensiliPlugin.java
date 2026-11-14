package it.assonnatooh.utensili;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.block.Action;

public final class UtensiliPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String RAM = "ram";
    private static final String CROWBAR = "crowbar";
    private static final String AXE = "axe";
    private static final long BREAK_TICKS = 8L * 20L;
    private NamespacedKey toolKey;
    private final Map<UUID, BreakSession> sessions = new HashMap<>();
    private final Set<String> occupiedBlocks = new HashSet<>();

    @Override
    public void onEnable() {
        toolKey = new NamespacedKey(this, "utensile");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("utensili") != null) {
            getCommand("utensili").setExecutor(this);
        }
        getLogger().info("Utensili abilitato.");
    }

    @Override
    public void onDisable() {
        for (BreakSession session : new ArrayList<>(sessions.values())) {
            session.cancel(false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Solo i giocatori possono aprire questa GUI.");
            return true;
        }
        if (!player.isOp() && !player.hasPermission("utensili.staff")) {
            player.sendMessage(ChatColor.RED + "Non hai il permesso per usare questo comando.");
            return true;
        }

        MenuHolder holder = new MenuHolder();
        Inventory inventory = holder.getInventory();
        inventory.setItem(11, createTool(RAM));
        inventory.setItem(13, createTool(CROWBAR));
        inventory.setItem(15, createTool(AXE));
        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ItemStack selected = event.getView().getTopInventory().getItem(slot);
        String tool = getTool(selected);
        if (tool != null) {
            giveTool(player, createTool(tool));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        }
    }

    @EventHandler
    public void onDoorBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!AXE.equals(getTool(player.getInventory().getItemInMainHand()))) {
            return;
        }
        Block clicked = event.getBlock();
        if (!isWoodenDoor(clicked)) {
            return;
        }

        Block lower = lowerDoorHalf(clicked);
        Block upper = lower.getRelative(0, 1, 0);
        BlockState lowerState = lower.getState();
        BlockState upperState = upper.getState();
        Material doorItem = lower.getType();
        event.setCancelled(true);

        lower.setType(Material.AIR, false);
        upper.setType(Material.AIR, false);
        lower.getWorld().dropItemNaturally(lower.getLocation(), new ItemStack(doorItem));
        player.playSound(lower.getLocation(), Sound.BLOCK_WOODEN_DOOR_BREAK, 1.0f, 0.9f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            // Non sovrascrivere blocchi collocati nell'area mentre la porta era rimossa.
            if (lower.getType() != Material.AIR || upper.getType() != Material.AIR) {
                return;
            }
            restoreBlock(lower, lowerState.getBlockData());
            restoreBlock(upper, upperState.getBlockData());
            lower.getWorld().playSound(lower.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 0.8f);
        }, 20L * 60L * 3L);
    }

    @EventHandler
    public void onToolUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        String tool = getTool(event.getItem());
        if (!RAM.equals(tool) && !CROWBAR.equals(tool)) {
            return;
        }
        Block target = event.getClickedBlock();
        if (target == null) {
            return;
        }
        event.setCancelled(true);

        boolean validTarget = RAM.equals(tool)
                ? isDoor(target)
                : isIronDoor(target) || target.getType() == Material.IRON_BARS;
        if (!validTarget) {
            return;
        }
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(ChatColor.RED + "Stai già sfondando un blocco.");
            return;
        }

        List<Block> targets = new ArrayList<>();
        if (isDoor(target)) {
            Block lower = lowerDoorHalf(target);
            targets.add(lower);
            targets.add(lower.getRelative(0, 1, 0));
        } else {
            targets.add(target);
        }
        for (Block block : targets) {
            if (occupiedBlocks.contains(blockKey(block))) {
                event.getPlayer().sendMessage(ChatColor.RED + "Qualcuno sta già sfondando questo blocco.");
                return;
            }
        }

        BreakSession session = new BreakSession(event.getPlayer(), tool, target, targets);
        sessions.put(event.getPlayer().getUniqueId(), session);
        for (Block block : targets) {
            occupiedBlocks.add(blockKey(block));
        }
        session.start();
    }

    private boolean isWoodenDoor(Block block) {
        return block.getBlockData() instanceof Door && Tag.WOODEN_DOORS.isTagged(block.getType());
    }

    private boolean isIronDoor(Block block) {
        return block.getBlockData() instanceof Door && block.getType() == Material.IRON_DOOR;
    }

    private boolean isDoor(Block block) {
        return isWoodenDoor(block) || isIronDoor(block);
    }

    private Block lowerDoorHalf(Block block) {
        if (block.getBlockData() instanceof Door door && door.getHalf() == Door.Half.TOP) {
            return block.getRelative(0, -1, 0);
        }
        return block;
    }

    private void restoreBlock(Block block, BlockData data) {
        block.setType(data.getMaterial(), false);
        block.setBlockData(data.clone(), false);
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private ItemStack createTool(String kind) {
        Material material;
        int model;
        String name;
        switch (kind) {
            case RAM -> {
                material = Material.STICK;
                model = 66;
                name = "Ariete";
            }
            case CROWBAR -> {
                material = Material.STICK;
                model = 65;
                name = "Piede di porco";
            }
            default -> {
                material = Material.IRON_AXE;
                model = 1;
                name = "Ascia VDF";
            }
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setCustomModelData(model);
        meta.setLore(List.of(ChatColor.GRAY + "Utensile speciale", ChatColor.DARK_GRAY + "Usa tasto destro sul blocco"));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, kind);
        item.setItemMeta(meta);
        return item;
    }

    private String getTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private void giveTool(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private final class MenuHolder implements InventoryHolder {
        private final Inventory inventory;

        private MenuHolder() {
            inventory = Bukkit.createInventory(this, 27, ChatColor.DARK_GRAY + "Utensili");
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final class BreakSession {
        private final Player player;
        private final String tool;
        private final Block initialTarget;
        private final List<Block> blocks;
        private final Map<Block, BlockData> originalData = new HashMap<>();
        private final BossBar bar;
        private BukkitTask task;
        private long elapsed;

        private BreakSession(Player player, String tool, Block initialTarget, List<Block> blocks) {
            this.player = player;
            this.tool = tool;
            this.initialTarget = initialTarget;
            this.blocks = blocks;
            for (Block block : blocks) {
                originalData.put(block, block.getBlockData().clone());
            }
            bar = Bukkit.createBossBar(ChatColor.GRAY + "Sfondamento in corso...", BarColor.GREEN, BarStyle.SOLID);
            bar.setProgress(0.0);
            bar.addPlayer(player);
        }

        private void start() {
            player.sendMessage(ChatColor.YELLOW + "Sfondamento avviato: attendi 8 secondi senza cambiare utensile.");
            task = Bukkit.getScheduler().runTaskTimer(UtensiliPlugin.this, () -> {
                if (!player.isOnline() || !tool.equals(getTool(player.getInventory().getItemInMainHand()))
                        || player.getWorld() != initialTarget.getWorld()
                        || player.getLocation().distanceSquared(initialTarget.getLocation().add(0.5, 0.5, 0.5)) > 25
                        || !isStillValid()) {
                    cancel(true);
                    return;
                }
                elapsed++;
                bar.setProgress(Math.min(1.0, (double) elapsed / BREAK_TICKS));
                if (elapsed >= BREAK_TICKS) {
                    finish();
                }
            }, 1L, 1L);
        }

        private boolean isStillValid() {
            if (isDoor(initialTarget)) {
                return blocks.size() == 2 && originalData.get(blocks.get(0)).getMaterial() == blocks.get(0).getType()
                        && originalData.get(blocks.get(1)).getMaterial() == blocks.get(1).getType();
            }
            return initialTarget.getType() == Material.IRON_BARS;
        }

        private void finish() {
            for (Block block : blocks) {
                block.setType(Material.AIR, false);
            }
            initialTarget.getWorld().playSound(initialTarget.getLocation(), Sound.BLOCK_IRON_DOOR_BREAK, 1.0f, 0.8f);
            player.sendMessage(ChatColor.GREEN + "Blocco sfondato!");
            cancel(false);
        }

        private void cancel(boolean notify) {
            if (task != null) {
                task.cancel();
            }
            bar.removeAll();
            sessions.remove(player.getUniqueId());
            for (Block block : blocks) {
                occupiedBlocks.remove(blockKey(block));
            }
            if (notify && player.isOnline()) {
                player.sendMessage(ChatColor.RED + "Sfondamento interrotto.");
            }
        }
    }
}
