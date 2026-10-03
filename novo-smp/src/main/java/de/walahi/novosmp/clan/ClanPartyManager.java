package de.walahi.novosmp.clan;

import de.walahi.novosmp.professions.BoosterCategory;
import de.walahi.novosmp.professions.BoosterService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;

/** Runtime-only clan parties. Timestamp invites need no individual scheduler. */
final class ClanPartyManager {
    private final ClanManager clans;
    private final ClanConfig config;
    private final BoosterService boosters;
    private final Map<UUID,Party> byMember=new HashMap<>();
    private final Map<UUID,Invite> invites=new HashMap<>();
    ClanPartyManager(ClanManager clans,ClanConfig config,BoosterService boosters){this.clans=clans;this.config=config;this.boosters=boosters;}

    synchronized String invite(Player sender,Player target){
        Clan clan=clans.clan(sender.getUniqueId()); if(clan==null||clan.level()<config.partyUnlock())return "Clan-Partys sind noch nicht freigeschaltet.";
        if(!clan.members().containsKey(target.getUniqueId()))return "Der Spieler ist nicht in deinem Clan.";
        Party party=byMember.get(sender.getUniqueId()); if(party!=null&&!party.leader.equals(sender.getUniqueId()))return "Nur der Party-Leader darf einladen.";
        if(party!=null&&party.members.size()>=config.partyMax())return "Die Party ist voll.";
        if(byMember.containsKey(target.getUniqueId()))return "Der Spieler ist bereits in einer Party.";
        invites.put(target.getUniqueId(),new Invite(sender.getUniqueId(),clan.id(),System.currentTimeMillis()+config.inviteMillis()));
        target.sendRichMessage("<gold>"+sender.getName()+"</gold> lädt dich in seine Clan-Party ein. <green><click:run_command:'/cp accept'>[Annehmen]</click></green> <red><click:run_command:'/cp deny'>[Ablehnen]</click></red>");
        return "Einladung gesendet.";
    }
    synchronized String accept(Player player){Invite i=invites.remove(player.getUniqueId());if(i==null||i.until<System.currentTimeMillis())return "Keine gültige Einladung vorhanden.";Clan clan=clans.clan(player.getUniqueId()),senderClan=clans.clan(i.sender);if(clan==null||senderClan!=clan||!clan.id().equals(i.clan))return "Die Einladung ist nicht mehr gültig.";Party p=byMember.get(i.sender);if(p==null){p=new Party(i.sender);p.members.add(i.sender);byMember.put(i.sender,p);}if(p.members.size()>=config.partyMax())return "Die Party ist voll.";p.members.add(player.getUniqueId());byMember.put(player.getUniqueId(),p);return "Du bist der Clan-Party beigetreten.";}
    synchronized String deny(UUID player){return invites.remove(player)!=null?"Einladung abgelehnt.":"Keine Einladung vorhanden.";}
    synchronized String leave(UUID player){Party p=byMember.get(player);if(p==null)return "Du bist in keiner Party.";remove(p,player);return "Du hast die Party verlassen.";}
    synchronized String kick(UUID actor,UUID target){Party p=byMember.get(actor);if(p==null||!p.leader.equals(actor))return "Nur der Party-Leader darf kicken.";if(!p.members.contains(target)||actor.equals(target))return "Spieler nicht in deiner Party.";remove(p,target);return "Spieler aus der Party entfernt.";}
    synchronized void removePlayer(UUID player){invites.remove(player);Party p=byMember.get(player);if(p!=null)remove(p,player);invites.values().removeIf(i->i.sender.equals(player));}
    synchronized void disbandClan(UUID clan){new HashSet<>(byMember.keySet()).stream().filter(id->{Clan c=clans.clan(id);return c==null||c.id().equals(clan);}).forEach(this::removePlayer);invites.entrySet().removeIf(e->e.getValue().clan.equals(clan));}
    synchronized String info(UUID player){Party p=byMember.get(player);if(p==null)return "Du bist in keiner Clan-Party.";return "Party: "+p.members.stream().map(id->{Player x=Bukkit.getPlayer(id);return x==null?id.toString():x.getName();}).toList();}
    synchronized double professionBonus(UUID recipient){Party p=byMember.get(recipient);if(p==null)return 0;double sum=0;for(UUID id:p.members){if(id.equals(recipient))continue;double m=boosters.multiplier(id,BoosterCategory.PROFESSION);if(m>1)sum+=config.professionShared(m);}return Math.min(config.professionCap(),sum);}
    synchronized double sharedLumiTier(UUID recipient){Party p=byMember.get(recipient);if(p==null)return 1;double max=1;for(UUID id:p.members)if(!id.equals(recipient))max=Math.max(max,boosters.multiplier(id,BoosterCategory.LUMI));return max;}
    private void remove(Party p,UUID id){p.members.remove(id);byMember.remove(id);if(p.members.size()<2){for(UUID x:p.members)byMember.remove(x);return;}if(p.leader.equals(id))p.leader=p.members.iterator().next();}
    private static final class Party{UUID leader;final LinkedHashSet<UUID> members=new LinkedHashSet<>();Party(UUID l){leader=l;}}
    private record Invite(UUID sender,UUID clan,long until){}
}
