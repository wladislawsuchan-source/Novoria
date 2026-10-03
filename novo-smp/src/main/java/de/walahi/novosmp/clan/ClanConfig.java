package de.walahi.novosmp.clan;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.configuration.file.FileConfiguration;

final class ClanConfig {
    private final NovoSMPPlugin plugin;
    ClanConfig(NovoSMPPlugin plugin){this.plugin=plugin;}
    FileConfiguration c(){return plugin.configs().server();}
    boolean enabled(){return c().getBoolean("clan.enabled",true);}
    long createCost(){return Math.max(0,c().getLong("clan.creation-cost",75000));}
    String createRank(){return c().getString("clan.required-create-rank","premium");}
    long renameCost(){return Math.max(0,c().getLong("clan.rename-cost",100000));}
    long tagCost(){return Math.max(0,c().getLong("clan.tag-cost",25000));}
    long renameCooldown(){return Math.max(0,c().getLong("clan.rename-cooldown-days",14))*86400000L;}
    long tagCooldown(){return Math.max(0,c().getLong("clan.tag-cooldown-days",14))*86400000L;}
    long inviteMillis(){return Math.max(1,c().getLong("clan.invite-timeout-seconds",60))*1000L;}
    int nameMin(){return Math.max(1,c().getInt("clan.names.min-length",3));}
    int nameMax(){return Math.max(nameMin(),c().getInt("clan.names.max-length",16));}
    int tagMax(){return Math.max(1,c().getInt("clan.tags.max-length",5));}
    int homeNameMax(){return Math.max(1,c().getInt("clan.homes.max-name-length",16));}
    int ffUnlock(){return bounded(c().getInt("clan.friendly-fire.unlock-level",4),1,10);}
    int partyUnlock(){return bounded(c().getInt("clan.party.unlock-level",5),1,10);}
    int partyMax(){return Math.max(2,c().getInt("clan.party.max-size",4));}
    int memberLimit(int level){return Math.max(1,c().getInt("clan.levels."+level+".members",defaultsMembers(level)));}
    long upgradeCost(int level){return Math.max(0,c().getLong("clan.levels."+level+".cost",defaultsCost(level)));}
    long prestige(int level){return Math.max(0,c().getLong("clan.levels."+level+".prestige",defaultsPrestige(level)));}
    long playtimeHours(int level){return Math.max(0,c().getLong("clan.levels."+level+".playtime-hours",defaultsHours(level)));}
    int officers(int level){return Math.max(0,c().getInt("clan.levels."+level+".officers",level<2?0:level<6?1:level<9?2:3));}
    int homes(int level,boolean bonus){int base=Math.max(0,c().getInt("clan.levels."+level+".homes",level<3?0:level<7?1:2));return Math.min(3,base+(bonus?Math.max(0,c().getInt("clan.homes.premium-plus-bonus",1)):0));}
    int chestRows(int level){return bounded(c().getInt("clan.levels."+level+".chest-rows",level<4?3:level<7?4:level<10?5:6),1,6);}
    boolean globalLevelTen(){return c().getBoolean("clan.announcements.level-10-global",true);}
    double professionShared(double multiplier){return multiplier>=1.99?c().getDouble("clan.party.profession.shared-2-0",0.10):c().getDouble("clan.party.profession.shared-1-5",0.05);}
    double professionCap(){return Math.max(0,c().getDouble("clan.party.profession.max-shared",0.30));}
    int lumiInterval(double tier){return Math.max(1,c().getInt(tier>=1.99?"clan.party.lumi.interval-2-0-seconds":"clan.party.lumi.interval-1-5-seconds",tier>=1.99?300:600));}
    int lumiMin(){return Math.max(1,c().getInt("clan.party.lumi.reward-min",1));}
    int lumiMax(){return Math.max(lumiMin(),c().getInt("clan.party.lumi.reward-max",5));}
    boolean validName(String s){return s!=null&&s.length()>=nameMin()&&s.length()<=nameMax()&&s.matches("[A-Za-z0-9_-]+");}
    boolean validTag(String s){return s!=null&&s.length()>=1&&s.length()<=tagMax()&&s.matches("[A-Za-z0-9_-]+");}
    private int bounded(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    private int defaultsMembers(int l){return new int[]{0,5,7,9,11,13,15,18,20,23,25}[bounded(l,1,10)];}
    private long defaultsCost(int l){return new long[]{0,0,100000,250000,500000,750000,1200000,1800000,2500000,3500000,5000000}[bounded(l,1,10)];}
    private long defaultsPrestige(int l){return new long[]{0,0,1,3,5,8,12,17,23,30,40}[bounded(l,1,10)];}
    private long defaultsHours(int l){return new long[]{0,0,20,50,100,180,300,450,650,900,1200}[bounded(l,1,10)];}
}
