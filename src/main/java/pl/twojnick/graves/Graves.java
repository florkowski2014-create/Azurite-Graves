package pl.twojnick.graves;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public class Graves extends JavaPlugin implements Listener {

    private static final String FW_TAG = "grave_fw";
    private static final Color[] COLORS = {
            Color.AQUA, Color.FUCHSIA, Color.YELLOW, Color.LIME, Color.ORANGE, Color.RED
    };

    static class Grave {
        Inventory inv;
        ItemDisplay display;
        TextDisplay label;
        Interaction hitbox;
        BukkitTask task;
        Location loc;
        int step = 0;
    }

    // klucz = UUID niewidzialnego hitboxa (Interaction)
    private final Map<UUID, Grave> graves = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Grave g : new ArrayList<>(graves.values())) {
            for (ItemStack it : g.inv.getContents()) {
                if (it != null && !it.getType().isAir()) {
                    g.loc.getWorld().dropItemNaturally(g.loc, it);
                }
            }
            removeGrave(g);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (e.getKeepInventory() || e.getDrops().isEmpty()) return;

        World w = p.getWorld();
        Location l = p.getLocation();
        double y = Math.max(l.getY(), w.getMinHeight() + 1);
        Location loc = new Location(w, l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5);

        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Grób: " + p.getName()));
        for (ItemStack it : e.getDrops()) {
            inv.addItem(it).values().forEach(left -> w.dropItemNaturally(l, left));
        }
        e.getDrops().clear();

        Grave g = new Grave();
        g.inv = inv;
        g.loc = loc;

        g.hitbox = w.spawn(loc, Interaction.class, h -> {
            h.setInteractionWidth(1f);
            h.setInteractionHeight(1f);
            h.setResponsive(true);
            h.setPersistent(false);
        });

        g.display = w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.CHEST));
            d.setPersistent(false);
            d.setInterpolationDuration(10);
        });

        g.label = w.spawn(loc.clone().add(0, 1.6, 0), TextDisplay.class, t -> {
            t.text(Component.text("☠ Grób gracza ", NamedTextColor.GRAY)
                    .append(Component.text(p.getName(), NamedTextColor.RED))
                    .append(Component.newline())
                    .append(Component.text("Kliknij PPM, aby otworzyć", NamedTextColor.YELLOW)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setPersistent(false);
        });

        graves.put(g.hitbox.getUniqueId(), g);

        g.task = Bukkit.getScheduler().runTaskTimer(this, () -> tick(g), 0L, 10L);

        // jeden fajerwerk w chwili pojawienia się grobu
        burst(loc.clone().add(0, 1, 0));

        p.sendMessage("§cZginąłeś! §7Twój grób: §e" + loc.getBlockX() + " "
                + loc.getBlockY() + " " + loc.getBlockZ() + " §7(" + w.getName() + ")");
    }

    private void tick(Grave g) {
        if (!g.display.isValid()) {
            removeGrave(g);
            return;
        }
        g.step++;
        float angle = g.step * (float) (Math.PI / 2);               // 90° co 0,5 s
        float bob = 0.5f + (float) Math.sin(g.step * 0.4) * 0.15f;  // lewitowanie
        g.display.setInterpolationDelay(0);
        g.display.setInterpolationDuration(10);
        g.display.setTransformation(new Transformation(
                new Vector3f(0f, bob, 0f),
                new AxisAngle4f(angle, 0f, 1f, 0f),
                new Vector3f(1f, 1f, 1f),
                new AxisAngle4f(0f, 0f, 0f, 1f)));
    }

    private void burst(Location l) {
        Firework fw = l.getWorld().spawn(l, Firework.class);
        fw.addScoreboardTag(FW_TAG);
        FireworkMeta m = fw.getFireworkMeta();
        m.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(COLORS[random.nextInt(COLORS.length)], Color.WHITE)
                .withFade(COLORS[random.nextInt(COLORS.length)])
                .flicker(true)
                .trail(true)
                .build());
        fw.setFireworkMeta(m);
        fw.detonate();
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Grave g = graves.get(e.getRightClicked().getUniqueId());
        if (g == null) return;
        e.setCancelled(true);
        e.getPlayer().openInventory(g.inv);
    }

    // wybuch fajerwerka z grobu nikogo nie rani
    @EventHandler
    public void onFireworkDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework fw && fw.getScoreboardTags().contains(FW_TAG)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        for (Grave g : new ArrayList<>(graves.values())) {
            if (!g.inv.equals(inv)) continue;
            boolean empty = Arrays.stream(inv.getContents())
                    .allMatch(i -> i == null || i.getType().isAir());
            if (empty) removeGrave(g);
        }
    }

    private void removeGrave(Grave g) {
        if (g.task != null) g.task.cancel();
        if (g.display != null) g.display.remove();
        if (g.label != null) g.label.remove();
        if (g.hitbox != null) {
            graves.remove(g.hitbox.getUniqueId());
            g.hitbox.remove();
        }
    }
}
