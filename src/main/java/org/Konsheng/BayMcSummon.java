package org.Konsheng;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.UUID;

public class BayMcSummon extends JavaPlugin {

    private BukkitAudiences adventure;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // 保存每个玩家的上次执行命令时间
    private final HashMap<UUID, Long> lastCommandTime = new HashMap<>();

    // 定义默认最大生成数量为 100
    private static final int MAX_ENTITIES_DEFAULT = 100;
    // 每批次生成的最大数量
    private static final int BATCH_SIZE = 100;

    @Override
    public void onEnable() {
        adventure = BukkitAudiences.create(this);
        getLogger().info("BayMcSummon 插件已启用");
    }

    @Override
    public void onDisable() {
        if (adventure != null) {
            adventure.close();
        }
        getLogger().info("BayMcSummon 插件已禁用");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // 确保命令发送者是玩家，并且玩家拥有权限
        if (sender instanceof Player player) {
            if (!player.hasPermission("baymc.summon")) {
                sendFormattedMessage(player, "<white>您当前没有执行此命令的权限");
                return true;
            }

            // 检查命令频率，限制每秒只能执行一次
            UUID playerUUID = player.getUniqueId();
            long currentTime = System.currentTimeMillis();
            if (lastCommandTime.containsKey(playerUUID)) {
                long lastTime = lastCommandTime.get(playerUUID);
                if (currentTime - lastTime < 1000) {
                    sendFormattedMessage(player, "<white>您每秒只能执行一次此命令");
                    return true;
                }
            }
            lastCommandTime.put(playerUUID, currentTime);

            // 确保命令格式正确
            if (args.length < 1 || args.length > 5) {
                sendFormattedMessage(player, "<white>用法: /summon <实体名称> [<x> <y> <z>] [<数量>] [-force]");
                return true;
            }

            // 获取实体类型
            String entityName = args[0].toUpperCase();
            EntityType entityType;
            try {
                entityType = EntityType.valueOf(entityName);
            } catch (IllegalArgumentException e) {
                sendFormattedMessage(player, "<white>无效的实体名称: <green>" + entityName);
                return true;
            }

            // 解析坐标
            Location location = player.getLocation(); // 默认使用玩家当前位置
            if (args.length >= 4) {
                try {
                    double x = Double.parseDouble(args[1]);
                    double y = Double.parseDouble(args[2]);
                    double z = Double.parseDouble(args[3]);
                    location = new Location(player.getWorld(), x, y, z);
                } catch (NumberFormatException e) {
                    sendFormattedMessage(player, "<white>您提供的坐标格式无效");
                    return true;
                }
            }

            // 获取数量
            int quantity = 1; // 默认数量为 1
            boolean force = false; // 是否使用了 -force 参数
            if (args.length == 2 || args.length == 5) {
                try {
                    quantity = Integer.parseInt(args[args.length - 1]);
                    if (quantity <= 0) {
                        sendFormattedMessage(player, "<white>生成实体数量必须大于 <green>0");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sendFormattedMessage(player, "<white>无效的数量: " + args[args.length - 1]);
                    return true;
                }
            }

            // 检查是否使用了 -force 参数
            if (args[args.length - 1].equalsIgnoreCase("-force")) {
                force = true;
                quantity = Integer.parseInt(args[args.length - 2]);
            }

            // 如果没有使用 -force 参数且数量超过 100，给出提示
            if (quantity > MAX_ENTITIES_DEFAULT && !force) {
                sendFormattedMessage(player, "<white>单次最多生成 " + MAX_ENTITIES_DEFAULT + " 个实体, 请使用 -force 参数可强制生成更多");
                return true;
            }

            // 分批生成生物
            summonEntitiesInBatches(player, entityType, location, quantity);
        } else {
            sendFormattedMessage(sender, "<white>此命令只能由玩家执行");
        }
        return true;
    }

    // 分批生成生物
    private void summonEntitiesInBatches(Player player, EntityType entityType, Location location, int totalQuantity) {
        int fullBatches = totalQuantity / BATCH_SIZE;
        int remaining = totalQuantity % BATCH_SIZE;

        // 使用 BukkitRunnable 定时生成生物
        new BukkitRunnable() {
            int batchCount = 0;

            @Override
            public void run() {
                if (batchCount < fullBatches) {
                    summonBatch(player, entityType, location, BATCH_SIZE);
                    batchCount++;
                } else if (batchCount == fullBatches && remaining > 0) {
                    summonBatch(player, entityType, location, remaining);
                    batchCount++;
                } else {
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 40L); // 每 40 tick (2秒) 执行一次
    }

    // 每批生成生物
    private void summonBatch(Player player, EntityType entityType, Location location, int quantity) {
        for (int i = 0; i < quantity; i++) {
            player.getWorld().spawnEntity(location, entityType);
        }

        // 播放音效
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

        sendFormattedMessage(player, "<white>以为您成功生成 <green>" + quantity + " <white>个" + "<green> " + entityType.name().toLowerCase());
    }

    // 使用 Adventure API 和 MiniMessage 发送带前缀的格式化消息
    private void sendFormattedMessage(CommandSender sender, String message) {
        Audience audience = adventure.sender(sender);
        String prefix = "<gradient:#495aff:#0acffe><b>BayMc</b></gradient> <gray>» ";
        Component component = miniMessage.deserialize(prefix + message);
        audience.sendMessage(component);
    }
}
