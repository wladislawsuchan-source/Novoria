package de.walahi.novosmp.clan;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.*;

public final class ClanCommand extends BaseCommand {
    private final ClanManager clans;
    public ClanCommand(NovoSMPPlugin plugin,ClanManager clans){super(plugin);this.clans=clans;}
    @Override protected String permission(){return "smpcore.clan.use";}
    @Override protected boolean execute(CommandSender sender,String label,String[] a){if(!(sender instanceof Player p)){sender.sendMessage("Nur für Spieler.");return true;}if(a.length==0){clans.open(p);return true;}String sub=a[0].toLowerCase(Locale.ROOT);switch(sub){
        case "help"->showHelp(p);
        case "create"->{if(!p.hasPermission("smpcore.clan.create")){p.sendRichMessage("<red>Keine Berechtigung.</red>");break;}clans.reply(p,a.length==3?clans.create(p,a[1],a[2]):"/clan create <Name> <Tag>");}
        case "list"->clans.openList(p); case "members"->clans.openMembers(p); case "homes"->clans.openHomes(p); case "chest"->clans.openChest(p);
        case "invite"->{Player t=a.length>1?Bukkit.getPlayerExact(a[1]):null;clans.reply(p,t==null?"Spieler muss online sein.":clans.invite(p,t));}
        case "accept"->clans.reply(p,clans.accept(p));case "deny"->clans.reply(p,clans.deny(p.getUniqueId()));
        case "leave"->clans.confirm(p,"Clan verlassen","Zugriff endet sofort",()->clans.reply(p,clans.leave(p)));
        case "kick","promote","demote","transfer"->{if(a.length<2){clans.reply(p,"/clan "+sub+" <Spieler>");break;}clans.confirm(p,sub,a[1],()->clans.reply(p,switch(sub){case "kick"->clans.kick(p,a[1]);case "promote"->clans.promoteDemote(p,a[1],true);case "demote"->clans.promoteDemote(p,a[1],false);default->clans.transfer(p,a[1]);}));}
        case "deposit"->{Long n=a.length>1?number(a[1]):null;clans.reply(p,n==null?"/clan deposit <Betrag>":clans.deposit(p,n));}
        case "bank"->{Clan c=clans.clan(p.getUniqueId());clans.reply(p,c==null?"Du bist in keinem Clan.":"Clan-Kasse: "+de.walahi.smpcore.gui.MenuFormat.integer(c.bank())+" Coins.");}
        case "rename","tag"->clans.reply(p,a.length==2?clans.rename(p,a[1],sub.equals("tag")):"/clan "+sub+" <Wert>");
        case "tagcolor"->clans.reply(p,a.length==2?clans.tagColor(p,a[1]):"/clan tagcolor #RRGGBB");
        case "ff"->clans.reply(p,a.length==2&&(a[1].equalsIgnoreCase("on")||a[1].equalsIgnoreCase("off"))?clans.friendlyFire(p,a[1].equalsIgnoreCase("on")):"/clan ff on|off");
        case "snitch"->{if(a.length==1)clans.openSnitches(p);else clans.confirm(p,"Snitch markieren",a[1],()->clans.reply(p,clans.snitch(p,a[1],false)));}
        case "unsnitch"->{if(a.length<2)clans.reply(p,"/clan unsnitch <Spieler>");else clans.confirm(p,"Snitch entfernen",a[1],()->clans.reply(p,clans.snitch(p,a[1],true)));}
        case "upgrade"->clans.confirm(p,"Clan-Level kaufen","Kosten werden aus der Clan-Kasse bezahlt",()->clans.reply(p,clans.upgrade(p)));
        case "disband"->clans.confirm(p,"Clan auflösen","Clan-Kasse verfällt; Clan-EC muss leer sein",()->clans.reply(p,clans.disband(p)));
        default->clans.reply(p,"Nutze /clan help.");}return true;}
    private Long number(String s){try{long n=Long.parseLong(s);return n>0?n:null;}catch(Exception e){return null;}}
    private void showHelp(Player player){
        player.sendRichMessage("<dark_gray>[<gold>Clan</gold>]</dark_gray> <yellow><bold>Befehle</bold></yellow>");
        Clan clan=clans.clan(player.getUniqueId());
        if(clan==null){
            help(player,"/clan create <Name> <Tag>","Clan erstellen");
            help(player,"/clan list","Alle Clans ansehen");
            help(player,"/clan accept | deny","Einladung beantworten");
            return;
        }
        Clan.Member self=clan.members().get(player.getUniqueId());
        help(player,"/clan","Clan-Menü öffnen");
        help(player,"/clan list","Alle Clans ansehen");
        help(player,"/clan deposit <Betrag>","Coins einzahlen");
        help(player,"/cc <Nachricht>","Clan-Chat");
        help(player,"/cp info","Clan-Party anzeigen");
        if(self==null)return;
        if(self.role().staff()){
            help(player,"/clan invite <Spieler>","Spieler einladen");
            help(player,"/clan kick <Spieler>","Member entfernen");
            help(player,"/csethome <Name>","Clan-Home setzen");
            help(player,"/clan upgrade","Clan-Level erhöhen");
        }
        if(self.role()==ClanRole.LEADER){
            help(player,"/clan promote | demote <Spieler>","Rolle ändern");
            help(player,"/clan transfer <Spieler>","Leadership übertragen");
            help(player,"/clan rename | tag <Wert>","Name oder Tag ändern");
            help(player,"/clan tagcolor #RRGGBB","Tag-Farbe ändern");
            help(player,"/clan disband","Clan auflösen");
        }else help(player,"/clan leave","Clan verlassen");
    }
    private void help(Player player,String command,String description){
        player.sendRichMessage(" <yellow>"+command.replace("<","&lt;").replace(">","&gt;")+"</yellow> <dark_gray>–</dark_gray> <gray>"+description+"</gray>");
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(a.length==1)return match(a[0],List.of("help","create","list","members","homes","invite","accept","deny","leave","kick","promote","demote","transfer","deposit","bank","rename","tag","tagcolor","ff","snitch","unsnitch","chest","upgrade","disband"));if(a.length==2&&List.of("invite","kick","promote","demote","transfer").contains(a[0].toLowerCase()))return match(a[1],Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());return List.of();}
    private List<String> match(String p,List<String> v){String q=p.toLowerCase();return v.stream().filter(x->x.toLowerCase().startsWith(q)).toList();}
}
