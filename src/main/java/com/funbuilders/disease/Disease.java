package com.funbuilders.disease;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Disease extends JavaPlugin implements Listener, TabCompleter {
    private static final String DISEASE_KEY = "diseases";
    private static final String COUGH_COLD = "cough_cold";
    private static final long ENVIRONMENT_CHECK_INTERVAL = 20L * 5L; // 5 seconds

    private final Random random = new Random();
    private final Map<UUID, DiseaseData> diseaseData = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> coughTasks = new ConcurrentHashMap<>();
    private final Map<String, DiseaseConfig> diseaseConfigs = new HashMap<>();
    private final Map<String, ItemStack> customItems = new HashMap<>();

    private String prefix;
    private ConfigValues configValues;
    private NamespacedKey diseaseNamespacedKey;

    @Override
    public void onEnable() {
        diseaseNamespacedKey = new NamespacedKey(this, DISEASE_KEY);
        saveDefaultConfig();
        loadConfigurations();
        registerCustomItems();
        registerCraftingRecipes();
        startEnvironmentalTask();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("diseases")).setExecutor(this);
        Objects.requireNonNull(getCommand("diseases")).setTabCompleter(this);
        getLogger().info("Loaded " + diseaseConfigs.size() + " diseases");
    }

    @Override
    public void onDisable() {
        coughTasks.values().forEach(BukkitRunnable::cancel);
        coughTasks.clear();
        getLogger().info("Diseases Plugin disabled");
    }

    private void loadConfigurations() {
        reloadConfig();
        prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("prefix", "&7[&cБолезни&7] &r"));
        configValues = new ConfigValues(getConfig());
        loadDiseaseConfigs();
    }

    private void loadDiseaseConfigs() {
        diseaseConfigs.clear();
        ConfigurationSection diseasesSection = getConfig().getConfigurationSection("diseases");
        if (diseasesSection != null) {
            diseasesSection.getKeys(false).forEach(diseaseName -> {
                ConfigurationSection section = diseasesSection.getConfigurationSection(diseaseName);
                if (section != null) {
                    diseaseConfigs.put(diseaseName, new DiseaseConfig(diseaseName, section));
                }
            });
        }
    }

    private void registerCustomItems() {
        customItems.put("activated_charcoal", createCustomItem(Material.CHARCOAL, ChatColor.GRAY + "Активированный уголь",
                ChatColor.GRAY + "Помогает выводить токсины."));
        customItems.put("antibiotics", createCustomItem(Material.PAPER, ChatColor.BLUE + "Антибиотики",
                ChatColor.BLUE + "Борется с бактериальными инфекциями."));
        customItems.put("herbal_tea", createCustomItem(Material.POTION, ChatColor.GREEN + "Травяной чай",
                ChatColor.GREEN + "Успокаивает кашель и простуду."));
    }

    private ItemStack createCustomItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Collections.singletonList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void registerCraftingRecipes() {
        registerRecipe(new ShapedRecipe(NamespacedKey.minecraft("activated_charcoal"), customItems.get("activated_charcoal"))
                .shape(" C ", "CCC", " C ")
                .setIngredient('C', Material.CHARCOAL));

        registerRecipe(new ShapelessRecipe(NamespacedKey.minecraft("antibiotics"), customItems.get("antibiotics"))
                .addIngredient(Material.FERMENTED_SPIDER_EYE)
                .addIngredient(Material.SUGAR)
                .addIngredient(Material.GUNPOWDER));

        registerRecipe(new ShapelessRecipe(NamespacedKey.minecraft("herbal_tea"), customItems.get("herbal_tea"))
                .addIngredient(Material.WHEAT_SEEDS)
                .addIngredient(Material.POTION));
    }

    private void registerRecipe(Recipe recipe) {
        getServer().addRecipe(recipe);
    }

    private void startEnvironmentalTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                getServer().getOnlinePlayers().forEach(player -> {
                    if (hasDisease(player, COUGH_COLD)) {
                        checkEnvironmentalTriggers(player);
                    }
                });
            }
        }.runTaskTimer(this, 0L, ENVIRONMENT_CHECK_INTERVAL);
    }

    private void checkEnvironmentalTriggers(Player player) {
        if (player.isInWater() && random.nextDouble() < configValues.wetIncrease) {
            triggerCough(player);
        }
        if (isPlayerInColdBiome(player) && random.nextDouble() < configValues.coldBiomeIncrease) {
            triggerCough(player);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "give" -> handleGive(sender, args);
            case "infect" -> handleInfect(sender, args);
            case "cure" -> handleCure(sender, args);
            default -> sendHelpMessage(sender);
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("diseases.reload")) {
            sender.sendMessage(prefix + ChatColor.RED + "Нет прав");
            return;
        }
        loadConfigurations();
        sender.sendMessage(prefix + ChatColor.GREEN + "Плагин перезагружен");
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("diseases.give") || args.length != 3) {
            sender.sendMessage(prefix + ChatColor.RED + "Использование: /diseases give <игрок> <предмет>");
            return;
        }
        processPlayerItemCommand(sender, args[1], args[2], customItems,
                item -> prefix + ChatColor.GREEN + "Выдали " + args[1] + " " + args[2]);
    }

    private void handleInfect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("diseases.infect") || args.length != 3) {
            sender.sendMessage(prefix + ChatColor.RED + "Использование: /diseases infect <игрок> <болезнь>");
            return;
        }
        processPlayerDiseaseCommand(sender, args[1], args[2], this::applyDisease,
                disease -> prefix + ChatColor.GREEN + "Заразили " + args[1] + " болезнью " + args[2]);
    }

    private void handleCure(CommandSender sender, String[] args) {
        if (!sender.hasPermission("diseases.cure") || args.length != 3) {
            sender.sendMessage(prefix + ChatColor.RED + "Использование: /diseases cure <игрок> <болезнь>");
            return;
        }
        processPlayerDiseaseCommand(sender, args[1], args[2], this::cureDisease,
                disease -> prefix + ChatColor.GREEN + "Вылечили " + args[1] + " от болезни " + args[2]);
    }

    private void processPlayerItemCommand(CommandSender sender, String playerName, String itemName,
                                          Map<String, ItemStack> items, Function<String, String> successMessage) {
        Player target = getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(prefix + ChatColor.RED + "Игрок не найден");
            return;
        }
        ItemStack item = items.get(itemName.toLowerCase());
        if (item == null) {
            sender.sendMessage(prefix + ChatColor.RED + "Неверное название предмета");
            return;
        }
        target.getInventory().addItem(item.clone());
        sender.sendMessage(successMessage.apply(itemName));
    }

    private void processPlayerDiseaseCommand(CommandSender sender, String playerName, String diseaseName,
                                             BiConsumer<Player, String> action, Function<String, String> successMessage) {
        Player target = getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(prefix + ChatColor.RED + "Игрок не найден");
            return;
        }
        if (!diseaseConfigs.containsKey(diseaseName.toLowerCase())) {
            sender.sendMessage(prefix + ChatColor.RED + "Неверное название болезни");
            return;
        }
        action.accept(target, diseaseName);
        sender.sendMessage(successMessage.apply(diseaseName));
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(prefix + ChatColor.YELLOW + "Использование: /diseases <reload|give|infect|cure>");
        sender.sendMessage(prefix + ChatColor.YELLOW + "Подсказка: /diseases give <игрок> <activated_charcoal|antibiotics|herbal_tea>");
        sender.sendMessage(prefix + ChatColor.YELLOW + "Подсказка: /diseases infect <игрок> <болезнь>");
        sender.sendMessage(prefix + ChatColor.YELLOW + "Подсказка: /diseases cure <игрок> <болезнь>");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filterCompletions(Arrays.asList("reload", "give", "infect", "cure"), args[0]);
        }
        if (args.length == 2 && !"reload".equalsIgnoreCase(args[0])) {
            return filterCompletions(getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3) {
            if ("give".equalsIgnoreCase(args[0])) {
                return filterCompletions(customItems.keySet(), args[2]);
            }
            if ("infect".equalsIgnoreCase(args[0]) || "cure".equalsIgnoreCase(args[0])) {
                return filterCompletions(diseaseConfigs.keySet(), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterCompletions(Collection<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    @EventHandler
    public void onItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item.getType() == Material.CHARCOAL) {
            event.setCancelled(true);
            return;
        }

        handleTreatment(player, item, event.getHand());
        checkFoodPoisoning(player, item);
    }

    private void handleTreatment(Player player, ItemStack item, EquipmentSlot hand) {
        diseaseConfigs.values().forEach(config -> {
            String treatment = config.getTreatmentItem();
            if (treatment != null && item.isSimilar(customItems.get(treatment))) {
                if (hasDisease(player, config.getName())) {
                    cureDisease(player, config.getName());
                    player.sendMessage(prefix + ChatColor.GREEN + getTreatmentMessage(treatment));
                    adjustItemQuantity(player, item, hand);
                }
            }
        });
    }

    private String getTreatmentMessage(String treatment) {
        return switch (treatment) {
            case "activated_charcoal" -> "Активированный уголь помог вывести токсины.";
            case "antibiotics" -> "Антибиотики борются с инфекцией.";
            case "herbal_tea" -> "Травяной чай успокоил ваш кашель и простуду.";
            default -> "";
        };
    }

    private void adjustItemQuantity(Player player, ItemStack item, EquipmentSlot hand) {
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItem(hand, new ItemStack(Material.AIR));
        }
    }

    private void checkFoodPoisoning(Player player, ItemStack item) {
        List<String> rawFoods = configValues.rawFoodMaterials;
        if (rawFoods.contains(item.getType().name()) || item.getType() == Material.ROTTEN_FLESH) {
            applyDisease(player, "poisoning");
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player && event.getDamager().getType() == EntityType.ZOMBIE) {
            applyDisease(player, "bacterial_contamination");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        diseaseConfigs.values().forEach(config -> {
            String treatment = config.getTreatmentItem();
            if (treatment != null && item.isSimilar(customItems.get(treatment)) &&
                    !"herbal_tea".equals(treatment) && hasDisease(player, config.getName())) {
                cureDisease(player, config.getName());
                player.sendMessage(prefix + ChatColor.GREEN + getTreatmentMessage(treatment));
                item.setAmount(item.getAmount() - 1);
            }
        });
    }

    public void applyDisease(Player player, String diseaseName) {
        DiseaseConfig config = diseaseConfigs.get(diseaseName);
        if (config == null || hasDisease(player, diseaseName)) return;

        DiseaseData data = diseaseData.computeIfAbsent(player.getUniqueId(), k -> new DiseaseData());
        data.addDisease(diseaseName);

        applyEffects(player, config.getEffects());
        if (config.getStartMessage() != null) {
            player.sendMessage(prefix + ChatColor.YELLOW + config.getStartMessage());
        }
        if (COUGH_COLD.equals(diseaseName)) {
            scheduleCoughTask(player);
        }
    }

    private void applyEffects(Player player, List<String> effects) {
        effects.forEach(effect -> {
            String[] parts = effect.split(":");
            if (parts.length == 3) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase());
                    int duration = Integer.parseInt(parts[1]) * 20;
                    int amplifier = Integer.parseInt(parts[2]);
                    player.addPotionEffect(new PotionEffect(type, duration, amplifier), true);
                } catch (Exception e) {
                    getLogger().warning("Invalid potion effect " + parts[0] + " for disease: " + effect);
                }
            }
        });
    }

    public void cureDisease(Player player, String diseaseName) {
        DiseaseData data = diseaseData.get(player.getUniqueId());
        if (data == null || !data.hasDisease(diseaseName)) return;

        DiseaseConfig config = diseaseConfigs.get(diseaseName);
        if (config != null) {
            removeEffects(player, config.getEffects());
            data.removeDisease(diseaseName);
            if (config.getCureMessage() != null) {
                player.sendMessage(prefix + ChatColor.GREEN + config.getCureMessage());
            }
            if (COUGH_COLD.equals(diseaseName)) {
                cancelCoughTask(player);
            }
        }
    }

    private void removeEffects(Player player, List<String> effects) {
        effects.forEach(effect -> {
            String[] parts = effect.split(":");
            try {
                PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase());
                player.removePotionEffect(type);
            } catch (Exception e) {
                getLogger().warning("Invalid potion effect " + parts[0] + " for disease");
            }
        });
    }

    public boolean hasDisease(Player player, String diseaseName) {
        DiseaseData data = diseaseData.get(player.getUniqueId());
        return data != null && data.hasDisease(diseaseName);
    }

    private void scheduleCoughTask(Player player) {
        if (coughTasks.containsKey(player.getUniqueId())) return;

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!hasDisease(player, COUGH_COLD)) {
                    cancel();
                    coughTasks.remove(player.getUniqueId());
                } else {
                    triggerCough(player);
                }
            }
        };
        int delay = configValues.getCoughInterval(random);
        coughTasks.put(player.getUniqueId(), task);
        task.runTaskTimer(this, delay * 20L, delay * 20L);
    }

    private void cancelCoughTask(Player player) {
        Optional.ofNullable(coughTasks.remove(player.getUniqueId())).ifPresent(BukkitRunnable::cancel);
    }

    private void triggerCough(Player player) {
        try {
            player.playSound(player.getLocation(), configValues.coughSound, 1.0f, 1.0f);
            player.getWorld().spawnParticle(Particle.valueOf(configValues.coughParticles.toUpperCase()),
                    player.getLocation().add(0, 1.7, 0.2), configValues.particleCount,
                    0.2, 0.2, 0.2, 0.01);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid sound/particle configuration: " + configValues.coughSound + "/" + configValues.coughParticles);
        }
    }

    private boolean isPlayerInColdBiome(Player player) {
        World.Environment env = player.getWorld().getEnvironment();
        return env != World.Environment.NETHER && env != World.Environment.THE_END &&
                configValues.coldBiomes.contains(player.getWorld().getBiome(player.getLocation()).name());
    }

    private static final class DiseaseData {
        private final Set<String> activeDiseases = ConcurrentHashMap.newKeySet();

        void addDisease(String diseaseName) {
            activeDiseases.add(diseaseName);
        }

        void removeDisease(String diseaseName) {
            activeDiseases.remove(diseaseName);
        }

        boolean hasDisease(String diseaseName) {
            return activeDiseases.contains(diseaseName);
        }
    }

    private static final class DiseaseConfig {
        private final String name;
        private final List<String> effects;
        private final String startMessage;
        private final String cureMessage;
        private final String treatmentItem;

        DiseaseConfig(String name, ConfigurationSection config) {
            this.name = name;
            this.effects = config.getStringList("effects");
            this.startMessage = translateColors(config.getString("start_message", ""));
            this.cureMessage = translateColors(config.getString("cure_message", ""));
            this.treatmentItem = config.getString("treatment_item");
        }

        private String translateColors(String text) {
            return ChatColor.translateAlternateColorCodes('&', text);
        }

        String getName() { return name; }
        List<String> getEffects() { return effects; }
        String getStartMessage() { return startMessage; }
        String getCureMessage() { return cureMessage; }
        String getTreatmentItem() { return treatmentItem; }
    }

    private static final class ConfigValues {
        final double wetIncrease;
        final double coldBiomeIncrease;
        final String coughSound;
        final String coughParticles;
        final int particleCount;
        final int coughIntervalMin;
        final int coughIntervalMax;
        final boolean randomCoughInterval;
        final Set<String> coldBiomes;
        final List<String> rawFoodMaterials;

        ConfigValues(org.bukkit.configuration.file.FileConfiguration config) {
            wetIncrease = config.getDouble("cough_cold.wet_increase", 10) / 100.0;
            coldBiomeIncrease = config.getDouble("cough_cold.cold_biome_increase", 10) / 100.0;
            coughSound = config.getString("cough_cold.sound", "minecraft:block.cherry_wood_button.click_off");
            coughParticles = config.getString("cough_cold.particles", "SMOKE_NORMAL");
            particleCount = config.getInt("cough_cold.particle_count", 5);
            coughIntervalMin = config.getInt("cough_cold.cough_interval_min", 3);
            coughIntervalMax = config.getInt("cough_cold.cough_interval_max", 5);
            randomCoughInterval = config.getBoolean("cough_cold.random_cough_interval", true);
            coldBiomes = new HashSet<>(config.getStringList("cough_cold.cold_biomes"));
            rawFoodMaterials = config.getStringList("poisoning.raw_food");
        }

        int getCoughInterval(Random random) {
            return randomCoughInterval ?
                    random.nextInt(coughIntervalMax - coughIntervalMin + 1) + coughIntervalMin :
                    coughIntervalMin;
        }
    }
}