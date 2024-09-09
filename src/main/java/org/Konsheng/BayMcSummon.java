package org.Konsheng;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class BayMcSummon extends JavaPlugin implements TabCompleter {

    private BukkitAudiences adventure;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // 保存每个玩家的上次执行命令时间
    private final HashMap<UUID, Long> lastCommandTime = new HashMap<>();
    // 保存每个玩家的当前任务
    private final HashMap<UUID, BukkitRunnable> activeTasks = new HashMap<>();

    // 定义默认最大生成数量为 100
    private static final int MAX_ENTITIES_DEFAULT = 100;
    // 每批次生成的最大数量
    private static final int BATCH_SIZE = 100;

    @Override
    public void onEnable() {
        adventure = BukkitAudiences.create(this);
        getCommand("summon").setTabCompleter(this); // 注册 Tab 补全器
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
        if (sender instanceof Player player) {
            UUID playerUUID = player.getUniqueId();
            if (label.equalsIgnoreCase("summon")) {
                if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
                    // 停止当前玩家的生成任务
                    if (activeTasks.containsKey(playerUUID)) {
                        activeTasks.get(playerUUID).cancel(); // 取消任务
                        activeTasks.remove(playerUUID); // 移除记录
                        sendFormattedMessage(player, "<white>召唤操作已中断");
                    } else {
                        sendFormattedMessage(player, "<white>当前没有正在执行的召唤操作");
                    }
                    return true;
                }

                // 确保玩家拥有权限
                if (!player.hasPermission("baymc.summon")) {
                    sendFormattedMessage(player, "<white>您当前没有执行此命令的权限");
                    return true;
                }

                // 检查命令频率，限制每秒只能执行一次
                long currentTime = System.currentTimeMillis();
                if (lastCommandTime.containsKey(playerUUID)) {
                    long lastTime = lastCommandTime.get(playerUUID);
                    if (currentTime - lastTime < 1000) {
                        sendFormattedMessage(player, "<white>您每秒只能执行一次此命令");
                        return true;
                    }
                }
                lastCommandTime.put(playerUUID, currentTime);

                if (args.length < 1) {
                    sendFormattedMessage(player, "<white>用法: /summon <实体名称> [<x> <y> <z>] [<玩家名称>] [<数量>] [-forcescalar | -forcespeed] [-health=<value>]");
                    return true;
                }

                String entityName = args[0].toUpperCase();
                EntityType entityType;
                try {
                    entityType = EntityType.valueOf(entityName);
                } catch (IllegalArgumentException e) {
                    sendFormattedMessage(player, "<white>无效的实体名称: <green>" + entityName);
                    return true;
                }

                // 解析位置：使用坐标或者其他玩家位置
                Location location = player.getLocation(); // 默认使用当前玩家位置
                boolean hasCoords = args.length >= 4 && isNumeric(args[1]) && isNumeric(args[2]) && isNumeric(args[3]);

                if (hasCoords) {
                    // 如果参数包含 x, y, z 坐标，使用这些坐标
                    try {
                        double x = Double.parseDouble(args[1]);
                        double y = Double.parseDouble(args[2]);
                        double z = Double.parseDouble(args[3]);
                        location = new Location(player.getWorld(), x, y, z);
                    } catch (NumberFormatException e) {
                        sendFormattedMessage(player, "<white>您提供的坐标格式无效");
                        return true;
                    }
                } else if (args.length >= 2 && !hasCoords) {
                    // 如果没有坐标但提供了玩家名称，使用该玩家的当前位置
                    Player targetPlayer = getServer().getPlayer(args[1]);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        location = targetPlayer.getLocation();
                    } else {
                        sendFormattedMessage(player, "<white>无法找到玩家: <green>" + args[1]);
                        return true;
                    }
                }

                int quantity = 1; // 默认数量为 1
                double health = -1; // 默认不设置血量
                boolean forceScalar = false; // 是否使用了 -forcescalar 参数
                boolean forceSpeed = false; // 是否使用了 -forcespeed 参数

                // 遍历命令参数来解析数量、标志和自定义参数
                for (String arg : args) {
                    if (arg.equalsIgnoreCase("-forcescalar")) {
                        forceScalar = true;
                    } else if (arg.equalsIgnoreCase("-forcespeed")) {
                        forceSpeed = true;
                    } else if (arg.startsWith("-health=")) {
                        // 检查是否输入了血量值
                        String[] healthArg = arg.split("=");
                        if (healthArg.length < 2 || healthArg[1].isEmpty()) {
                            sendFormattedMessage(player, "<white>请指定血量，例如: <green>-health=100");
                            return true;
                        }
                        try {
                            health = Double.parseDouble(healthArg[1]);
                            // 检查血量范围是否在 2 到 1024 之间
                            if (health < 2 || health > 1024) {
                                sendFormattedMessage(player, "<white>血量超出范围: 请输入 <green>2-1024<white> 之间的数值");
                                return true;
                            }
                        } catch (NumberFormatException e) {
                            sendFormattedMessage(player, "<white>无效的血量值: <green>" + healthArg[1]);
                            return true;
                        }
                    } else if (isNumeric(arg)) {
                        // 如果参数是数字，则将其视为数量
                        quantity = Integer.parseInt(arg);
                    }
                }

                if (quantity <= 0) {
                    sendFormattedMessage(player, "<white>生成实体数量必须大于 <green>0");
                    return true;
                }

                if (quantity > MAX_ENTITIES_DEFAULT && !forceScalar && !forceSpeed) {
                    sendFormattedMessage(player, "<white>单次最多生成 " + MAX_ENTITIES_DEFAULT + " 个实体, 请使用 -forcescalar 或 -forcespeed 参数以强制生成更多");
                    return true;
                }

                if (forceSpeed) {
                    // 如果使用了 -forcespeed，立即生成所有生物
                    summonEntitiesInstantly(player, entityType, location, quantity, health);
                } else {
                    // 分批生成生物，并显示进度
                    summonEntitiesInBatches(player, entityType, location, quantity, health);
                }
            }
        } else {
            sendFormattedMessage(sender, "<white>此命令只能由玩家执行");
        }
        return true;
    }

    // 分批生成生物
    private void summonEntitiesInBatches(Player player, EntityType entityType, Location location, int totalQuantity, double health) {
        int fullBatches = totalQuantity / BATCH_SIZE;
        int remaining = totalQuantity % BATCH_SIZE;
        UUID playerUUID = player.getUniqueId();

        BukkitRunnable task = new BukkitRunnable() {
            int batchCount = 0;
            int totalSpawned = 0;

            @Override
            public void run() {
                if (batchCount < fullBatches) {
                    summonBatch(player, entityType, location, BATCH_SIZE, health);
                    totalSpawned += BATCH_SIZE;
                    sendFormattedMessage(player, "<white>已生成 " + totalSpawned + "/" + totalQuantity + " 个实体");
                    batchCount++;
                } else if (batchCount == fullBatches && remaining > 0) {
                    summonBatch(player, entityType, location, remaining, health);
                    totalSpawned += remaining;
                    sendFormattedMessage(player, "<white>已生成 " + totalSpawned + "/" + totalQuantity + " 个实体");
                    batchCount++;
                } else {
                    sendFormattedMessage(player, "<white>实体生成完毕，共生成 <green>" + totalSpawned + "/" + totalQuantity + " 个实体");
                    activeTasks.remove(playerUUID); // 移除任务
                    this.cancel();
                }
            }
        };

        // 启动任务并保存到 activeTasks 中
        task.runTaskTimer(this, 0L, 40L);
        activeTasks.put(playerUUID, task);
    }

    // 立即生成所有实体
    private void summonEntitiesInstantly(Player player, EntityType entityType, Location location, int totalQuantity, double health) {
        summonBatch(player, entityType, location, totalQuantity, health);
        sendFormattedMessage(player, "<white>实体生成完毕，共生成 <green>" + totalQuantity + "/" + totalQuantity + " 个实体");
    }

    // 每批生成生物，并设置血量
    private void summonBatch(Player player, EntityType entityType, Location location, int quantity, double health) {
        for (int i = 0; i < quantity; i++) {
            LivingEntity entity = (LivingEntity) player.getWorld().spawnEntity(location, entityType);
            if (health > 0) {
                entity.setMaxHealth(health);
                entity.setHealth(health);
            }
        }

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        sendFormattedMessage(player, "<white>成功生成 " + quantity + " 个 " + entityType.name().toLowerCase());
    }

    private void sendFormattedMessage(CommandSender sender, String message) {
        Audience audience = adventure.sender(sender);
        String prefix = "<gradient:#495aff:#0acffe><b>BayMc</b></gradient> <gray>» ";
        Component component = miniMessage.deserialize(prefix + message);
        audience.sendMessage(component);
    }

    // 辅助方法：检查字符串是否为数字
    private boolean isNumeric(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Tab 补全逻辑
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("summon")) {
            if (args.length == 1) {
                // 提供实体类型自动补全
                for (EntityType type : EntityType.values()) {
                    completions.add(type.name().toLowerCase());
                }
            } else if (args.length == 2 || args.length == 3 || args.length == 4) {
                // 补全坐标或玩家名
                if (args.length == 2 || args.length == 3 || args.length == 4) {
                    // 提供玩家名补全
                    if (args.length == 2 && !isNumeric(args[1])) {
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            completions.add(onlinePlayer.getName());
                        }
                    } else if (sender instanceof Player player) {
                        // 如果已经开始输入坐标，则补全坐标
                        Location loc = player.getLocation();
                        if (args.length == 2 && isNumeric(args[1])) {
                            completions.add(String.valueOf((int) loc.getX()));
                        } else if (args.length == 3 && isNumeric(args[1])) {
                            completions.add(String.valueOf((int) loc.getY()));
                        } else if (args.length == 4 && isNumeric(args[1])) {
                            completions.add(String.valueOf((int) loc.getZ()));
                        }
                    }
                }
            } else {
                // 提供 -forcescalar、-forcespeed 和 -health 参数的补全
                if (!containsFlag(args, "-forcescalar")) {
                    completions.add("-forcescalar");
                }
                if (!containsFlag(args, "-forcespeed")) {
                    completions.add("-forcespeed");
                }
                if (!containsFlag(args, "-health=")) {
                    completions.add("-health=100"); // 默认提供一个数值进行补全
                }
            }
        }

        return completions;
    }

    // 检查参数中是否已经包含某个标志
    private boolean containsFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.startsWith(flag)) {
                return true;
            }
        }
        return false;
    }
}
