package de.walahi.novosmp.clan;

import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import de.walahi.smpcore.economy.EconomyRepository;
import de.walahi.smpcore.economy.EconomyTransactionRepository;
import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.*;

/** SQL boundary for clan mutations. Coin-changing operations are atomic with economy audit rows. */
final class ClanRepository {
    enum Status { SUCCESS, EXISTS, NOT_FOUND, INSUFFICIENT, CONFLICT, INVALID, STORAGE_ERROR }
    record Result(Status status, Clan clan, long before, long after) { }
    record Home(String name,String display,Location location) { }
    record Snitch(UUID id,String name,String marker,long markedAt) { }
    private final StorageManager storage;
    private final EconomyRepository economy;
    private final EconomyTransactionRepository audit;
    ClanRepository(StorageManager storage){this.storage=storage;economy=new EconomyRepository(storage);audit=new EconomyTransactionRepository(storage);}

    Map<UUID,Clan> loadAll() throws SQLException {
        Map<UUID,Clan> out=new LinkedHashMap<>();
        try(Connection c=storage.connection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM "+storage.table("clans"))){
            while(r.next()){UUID id=UUID.fromString(r.getString("clan_uuid")); out.put(id,new Clan(id,r.getString("name"),r.getString("tag"),r.getString("tag_color"),r.getInt("level"),r.getLong("bank"),UUID.fromString(r.getString("leader_uuid")),r.getBoolean("friendly_fire"),r.getBoolean("bonus_home"),r.getLong("renamed_at"),r.getLong("tagged_at")));}
        }
        try(Connection c=storage.connection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM "+storage.table("clan_members")+" ORDER BY joined_at")){
            while(r.next()){Clan clan=out.get(UUID.fromString(r.getString("clan_uuid"))); if(clan!=null){UUID id=UUID.fromString(r.getString("player_uuid"));clan.members().put(id,new Clan.Member(id,r.getString("player_name"),ClanRole.valueOf(r.getString("role")),r.getLong("joined_at")));}}
        }
        return out;
    }

    Result create(UUID player,String playerName,String name,String tag,long cost){
        return tx(c->{
            if(exists(c,"name_norm",name)||exists(c,"tag_norm",tag)) return rollback(c,Status.EXISTS);
            long before=economy.balanceForUpdate(c,player); if(before<cost)return rollback(c,Status.INSUFFICIENT);
            UUID id=UUID.randomUUID(); long now=System.currentTimeMillis();
            try(PreparedStatement p=c.prepareStatement("INSERT INTO "+storage.table("clans")+" (clan_uuid,name,name_norm,tag,tag_norm,tag_color,level,bank,leader_uuid,friendly_fire,bonus_home,renamed_at,tagged_at,created_at) VALUES (?,?,?,?,?,?,1,0,?,0,0,0,0,?)")){
                p.setString(1,id.toString());p.setString(2,name);p.setString(3,norm(name));p.setString(4,tag);p.setString(5,norm(tag));p.setString(6,"#AAAAAA");p.setString(7,player.toString());p.setLong(8,now);p.executeUpdate();
            }
            insertMember(c,id,player,playerName,ClanRole.LEADER,now);
            long after=Math.subtractExact(before,cost);economy.setBalance(c,player,after);
            audit.insert(c,UUID.randomUUID(),player,EconomyTransactionEvent.Type.WITHDRAW,cost,before,after,"CLAN_CREATE",ActionContext.player(ActionSource.COMMAND,player));
            c.commit(); Clan clan=new Clan(id,name,tag,"#AAAAAA",1,0,player,false,false,0,0);clan.members().put(player,new Clan.Member(player,playerName,ClanRole.LEADER,now));return new Result(Status.SUCCESS,clan,before,after);
        });
    }

    Result deposit(Clan clan,UUID player,long amount){return tx(c->{lockClan(c,clan.id());long before=economy.balanceForUpdate(c,player);if(before<amount)return rollback(c,Status.INSUFFICIENT);long bank=Math.addExact(readBank(c,clan.id()),amount),after=Math.subtractExact(before,amount);updateLong(c,"bank",bank,clan.id());economy.setBalance(c,player,after);audit.insert(c,UUID.randomUUID(),player,EconomyTransactionEvent.Type.WITHDRAW,amount,before,after,"CLAN_DEPOSIT",ActionContext.actorTarget(ActionSource.COMMAND,player,clan.id()));c.commit();clan.bank(bank);return new Result(Status.SUCCESS,clan,before,after);});}
    Status upgrade(Clan clan,int expected,long cost){return txStatus(c->{lockClan(c,clan.id());try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clans")+" SET level=level+1,bank=bank-? WHERE clan_uuid=? AND level=? AND bank>=?")){p.setLong(1,cost);p.setString(2,clan.id().toString());p.setInt(3,expected);p.setLong(4,cost);if(p.executeUpdate()!=1)return rollbackStatus(c,Status.CONFLICT);}c.commit();clan.level(expected+1);clan.bank(clan.bank()-cost);return Status.SUCCESS;});}
    Status updateIdentity(Clan clan,String column,String value,long cost,long now){return txStatus(c->{lockClan(c,clan.id());String normCol=column+"_norm";if(existsExcept(c,normCol,value,clan.id()))return rollbackStatus(c,Status.EXISTS);long bank=readBank(c,clan.id());if(bank<cost)return rollbackStatus(c,Status.INSUFFICIENT);String timeCol=column.equals("name")?"renamed_at":"tagged_at";try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clans")+" SET "+column+"=?,"+normCol+"=?,bank=?,"+timeCol+"=? WHERE clan_uuid=?")){p.setString(1,value);p.setString(2,norm(value));p.setLong(3,bank-cost);p.setLong(4,now);p.setString(5,clan.id().toString());p.executeUpdate();}c.commit();clan.bank(bank-cost);if(column.equals("name")){clan.name(value);clan.renamedAt(now);}else{clan.tag(value);clan.taggedAt(now);}return Status.SUCCESS;});}
    Status simpleClanUpdate(Clan clan,String column,Object value){return txStatus(c->{try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clans")+" SET "+column+"=? WHERE clan_uuid=?")){p.setObject(1,value);p.setString(2,clan.id().toString());p.executeUpdate();}c.commit();return Status.SUCCESS;});}
    Status addMember(Clan clan,UUID id,String name){return txStatus(c->{insertMember(c,clan.id(),id,name,ClanRole.MEMBER,System.currentTimeMillis());c.commit();return Status.SUCCESS;});}
    Status setRole(Clan clan,UUID id,ClanRole role){return txStatus(c->{try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clan_members")+" SET role=? WHERE clan_uuid=? AND player_uuid=?")){p.setString(1,role.name());p.setString(2,clan.id().toString());p.setString(3,id.toString());if(p.executeUpdate()!=1)return rollbackStatus(c,Status.NOT_FOUND);}c.commit();return Status.SUCCESS;});}
    Status transfer(Clan clan,UUID oldLeader,UUID next,ClanRole oldRole){return txStatus(c->{lockClan(c,clan.id());try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clans")+" SET leader_uuid=? WHERE clan_uuid=? AND leader_uuid=?")){p.setString(1,next.toString());p.setString(2,clan.id().toString());p.setString(3,oldLeader.toString());if(p.executeUpdate()!=1)return rollbackStatus(c,Status.CONFLICT);}setRoleSql(c,clan.id(),oldLeader,oldRole);setRoleSql(c,clan.id(),next,ClanRole.LEADER);c.commit();return Status.SUCCESS;});}
    Status removeMember(Clan clan,Clan.Member m){return txStatus(c->{try(PreparedStatement p=c.prepareStatement("DELETE FROM "+storage.table("clan_members")+" WHERE clan_uuid=? AND player_uuid=?")){p.setString(1,clan.id().toString());p.setString(2,m.id().toString());if(p.executeUpdate()!=1)return rollbackStatus(c,Status.NOT_FOUND);}try(PreparedStatement p=c.prepareStatement(insertIgnore("clan_former_members","(clan_uuid,player_uuid,player_name,left_at) VALUES (?,?,?,?)"))){p.setString(1,clan.id().toString());p.setString(2,m.id().toString());p.setString(3,m.name());p.setLong(4,System.currentTimeMillis());p.executeUpdate();}c.commit();return Status.SUCCESS;});}

    long prestige(Clan clan)throws SQLException{return aggregate(clan,"profession_profiles","prestige");}
    long playtime(Clan clan)throws SQLException{return aggregate(clan,"player_stats","playtime_seconds");}
    private long aggregate(Clan clan,String table,String col)throws SQLException{if(clan.members().isEmpty())return 0;String qs=String.join(",",Collections.nCopies(clan.members().size(),"?"));String sql="SELECT COALESCE(SUM("+col+"),0) FROM "+storage.table(table)+" WHERE player_uuid IN ("+qs+")";try(Connection c=storage.connection();PreparedStatement p=c.prepareStatement(sql)){int i=1;for(UUID id:clan.members().keySet())p.setString(i++,id.toString());try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}}

    List<Home> homes(UUID clan)throws SQLException{List<Home> out=new ArrayList<>();try(Connection c=storage.connection();PreparedStatement p=c.prepareStatement("SELECT * FROM "+storage.table("clan_homes")+" WHERE clan_uuid=? ORDER BY home_name")){p.setString(1,clan.toString());try(ResultSet r=p.executeQuery()){while(r.next()){World w=Bukkit.getWorld(r.getString("world"));if(w!=null)out.add(new Home(r.getString("home_name"),r.getString("display_name"),new Location(w,r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"))));}}}return out;}
    Status setHome(UUID clan,String norm,String display,Location l){return txStatus(c->{String sql=storage.dialect()==StorageDialect.SQLITE?"INSERT OR REPLACE INTO ":"REPLACE INTO ";try(PreparedStatement p=c.prepareStatement(sql+storage.table("clan_homes")+" (clan_uuid,home_name,display_name,world,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?)")){p.setString(1,clan.toString());p.setString(2,norm);p.setString(3,display);p.setString(4,l.getWorld().getName());p.setDouble(5,l.getX());p.setDouble(6,l.getY());p.setDouble(7,l.getZ());p.setFloat(8,l.getYaw());p.setFloat(9,l.getPitch());p.executeUpdate();}c.commit();return Status.SUCCESS;});}
    Status deleteHome(UUID clan,String name){return deleteWhere("clan_homes",clan,"home_name",name);}
    boolean former(UUID clan,UUID player)throws SQLException{return rowExists("clan_former_members",clan,player);}
    List<Snitch> snitches(UUID clan)throws SQLException{List<Snitch> out=new ArrayList<>();try(Connection c=storage.connection();PreparedStatement p=c.prepareStatement("SELECT * FROM "+storage.table("clan_snitches")+" WHERE clan_uuid=? ORDER BY marked_at DESC")){p.setString(1,clan.toString());try(ResultSet r=p.executeQuery()){while(r.next())out.add(new Snitch(UUID.fromString(r.getString("player_uuid")),r.getString("player_name"),r.getString("marked_by_name"),r.getLong("marked_at")));}}return out;}
    Status snitch(UUID clan,UUID player,String name,UUID marker,String markerName){return txStatus(c->{try(PreparedStatement p=c.prepareStatement(insertIgnore("clan_snitches","(clan_uuid,player_uuid,player_name,marked_by_uuid,marked_by_name,marked_at) VALUES (?,?,?,?,?,?)"))){p.setString(1,clan.toString());p.setString(2,player.toString());p.setString(3,name);p.setString(4,marker.toString());p.setString(5,markerName);p.setLong(6,System.currentTimeMillis());p.executeUpdate();}c.commit();return Status.SUCCESS;});}
    Status unsnitch(UUID clan,UUID player){return deleteWhere("clan_snitches",clan,"player_uuid",player.toString());}

    ItemStack[] loadChest(UUID clan,int size)throws Exception{ItemStack[] out=new ItemStack[size];try(Connection c=storage.connection();PreparedStatement p=c.prepareStatement("SELECT slot_index,item_data FROM "+storage.table("clan_chest")+" WHERE clan_uuid=?")){p.setString(1,clan.toString());try(ResultSet r=p.executeQuery()){while(r.next()){int slot=r.getInt(1);if(slot>=0&&slot<size)out[slot]=decode(r.getBytes(2));}}}return out;}
    synchronized void saveChest(UUID clan,ItemStack[] items)throws Exception{try(Connection c=storage.connection()){c.setAutoCommit(false);try(PreparedStatement d=c.prepareStatement("DELETE FROM "+storage.table("clan_chest")+" WHERE clan_uuid=?")){d.setString(1,clan.toString());d.executeUpdate();}try(PreparedStatement p=c.prepareStatement("INSERT INTO "+storage.table("clan_chest")+" (clan_uuid,slot_index,item_data) VALUES (?,?,?)")){for(int i=0;i<items.length;i++){if(items[i]==null||items[i].getType().isAir())continue;p.setString(1,clan.toString());p.setInt(2,i);p.setBytes(3,encode(items[i]));p.addBatch();}p.executeBatch();}c.commit();}}
    Status disband(Clan clan){return txStatus(c->{for(String t:List.of("clan_snitches","clan_former_members","clan_homes","clan_chest","clan_members")){try(PreparedStatement p=c.prepareStatement("DELETE FROM "+storage.table(t)+" WHERE clan_uuid=?")){p.setString(1,clan.id().toString());p.executeUpdate();}}try(PreparedStatement p=c.prepareStatement("DELETE FROM "+storage.table("clans")+" WHERE clan_uuid=?")){p.setString(1,clan.id().toString());p.executeUpdate();}c.commit();return Status.SUCCESS;});}

    private byte[] encode(ItemStack item)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(BukkitObjectOutputStream o=new BukkitObjectOutputStream(b)){o.writeObject(item);}return b.toByteArray();}
    private ItemStack decode(byte[] data)throws Exception{try(BukkitObjectInputStream i=new BukkitObjectInputStream(new ByteArrayInputStream(data))){return (ItemStack)i.readObject();}}
    private boolean exists(Connection c,String column,String value)throws SQLException{return existsExcept(c,column,value,null);}
    private boolean existsExcept(Connection c,String column,String value,UUID except)throws SQLException{String sql="SELECT 1 FROM "+storage.table("clans")+" WHERE "+column+"=?"+(except==null?"":" AND clan_uuid<>?");try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,norm(value));if(except!=null)p.setString(2,except.toString());try(ResultSet r=p.executeQuery()){return r.next();}}}
    private void lockClan(Connection c,UUID id)throws SQLException{String q="SELECT clan_uuid FROM "+storage.table("clans")+" WHERE clan_uuid=?"+(storage.dialect()==StorageDialect.MYSQL?" FOR UPDATE":"");try(PreparedStatement p=c.prepareStatement(q)){p.setString(1,id.toString());p.executeQuery().close();}}
    private long readBank(Connection c,UUID id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT bank FROM "+storage.table("clans")+" WHERE clan_uuid=?")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private void updateLong(Connection c,String col,long v,UUID id)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clans")+" SET "+col+"=? WHERE clan_uuid=?")){p.setLong(1,v);p.setString(2,id.toString());p.executeUpdate();}}
    private void insertMember(Connection c,UUID clan,UUID id,String name,ClanRole role,long at)throws SQLException{try(PreparedStatement p=c.prepareStatement("INSERT INTO "+storage.table("clan_members")+" (clan_uuid,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)")){p.setString(1,clan.toString());p.setString(2,id.toString());p.setString(3,name);p.setString(4,role.name());p.setLong(5,at);p.executeUpdate();}}
    private void setRoleSql(Connection c,UUID clan,UUID id,ClanRole role)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE "+storage.table("clan_members")+" SET role=? WHERE clan_uuid=? AND player_uuid=?")){p.setString(1,role.name());p.setString(2,clan.toString());p.setString(3,id.toString());p.executeUpdate();}}
    private boolean rowExists(String table,UUID clan,UUID player)throws SQLException{try(Connection c=storage.connection();PreparedStatement p=c.prepareStatement("SELECT 1 FROM "+storage.table(table)+" WHERE clan_uuid=? AND player_uuid=?")){p.setString(1,clan.toString());p.setString(2,player.toString());try(ResultSet r=p.executeQuery()){return r.next();}}}
    private Status deleteWhere(String table,UUID clan,String col,String value){return txStatus(c->{try(PreparedStatement p=c.prepareStatement("DELETE FROM "+storage.table(table)+" WHERE clan_uuid=? AND "+col+"=?")){p.setString(1,clan.toString());p.setString(2,value);if(p.executeUpdate()==0)return rollbackStatus(c,Status.NOT_FOUND);}c.commit();return Status.SUCCESS;});}
    private String insertIgnore(String table,String rest){return (storage.dialect()==StorageDialect.SQLITE?"INSERT OR IGNORE INTO ":"INSERT IGNORE INTO ")+storage.table(table)+" "+rest;}
    private static String norm(String s){return s.toLowerCase(Locale.ROOT);}
    private Result tx(Work w){try(Connection c=storage.connection()){c.setAutoCommit(false);try{return w.run(c);}catch(ArithmeticException e){c.rollback();return new Result(Status.INVALID,null,0,0);}catch(SQLException e){c.rollback();throw e;}}catch(SQLException e){return new Result(Status.STORAGE_ERROR,null,0,0);}}
    private Status txStatus(StatusWork w){try(Connection c=storage.connection()){c.setAutoCommit(false);try{return w.run(c);}catch(ArithmeticException e){c.rollback();return Status.INVALID;}catch(SQLException e){c.rollback();throw e;}}catch(SQLException e){return Status.STORAGE_ERROR;}}
    private Result rollback(Connection c,Status s)throws SQLException{c.rollback();return new Result(s,null,0,0);} private Status rollbackStatus(Connection c,Status s)throws SQLException{c.rollback();return s;}
    @FunctionalInterface private interface Work{Result run(Connection c)throws SQLException;} @FunctionalInterface private interface StatusWork{Status run(Connection c)throws SQLException;}
}
