package de.walahi.novosmp.economy.worth;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class WorthSignInput implements Listener {
    private final de.walahi.smpcore.SMPCorePlugin plugin; private final WorthMenu menu; private final Map<UUID,Location> sessions=new ConcurrentHashMap<>(); private final PlainTextComponentSerializer plain=PlainTextComponentSerializer.plainText();
    WorthSignInput(de.walahi.smpcore.SMPCorePlugin plugin,WorthMenu menu){this.plugin=plugin;this.menu=menu;}
    void open(Player player){Location loc=player.getLocation().getBlock().getLocation().add(0,4,0);sessions.put(player.getUniqueId(),loc);player.closeInventory();Bukkit.getScheduler().runTaskLater(plugin,()->{if(!sessions.containsKey(player.getUniqueId())||!player.isOnline())return;player.sendBlockChange(loc,Bukkit.createBlockData(Material.OAK_SIGN));player.sendSignChange(loc,new String[]{"","Item suchen","leer = alles",""});Bukkit.getScheduler().runTaskLater(plugin,()->{if(player.isOnline()&&sessions.containsKey(player.getUniqueId()))player.openVirtualSign(Position.block(loc),Side.FRONT);},1L);},1L);}
    @EventHandler public void onChange(UncheckedSignChangeEvent event){Player p=event.getPlayer();Location loc=sessions.remove(p.getUniqueId());if(loc==null)return;event.setCancelled(true);String raw=event.lines().isEmpty()?"":plain.serialize(event.lines().get(0)).trim();Bukkit.getScheduler().runTask(plugin,()->{restore(p,loc);menu.setSearch(p,raw);});}
    @EventHandler public void onQuit(PlayerQuitEvent e){Location loc=sessions.remove(e.getPlayer().getUniqueId());if(loc!=null)restore(e.getPlayer(),loc);}
    private void restore(Player p,Location l){Block b=l.getBlock();p.sendBlockChange(l,b.getBlockData());}
}
