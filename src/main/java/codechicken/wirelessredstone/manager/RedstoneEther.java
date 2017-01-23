package codechicken.wirelessredstone.manager;

import java.util.*;
import java.util.Map.Entry;

import codechicken.lib.util.CommonUtils;
import codechicken.lib.util.ServerUtils;
import codechicken.wirelessredstone.api.ITileWireless;
import codechicken.wirelessredstone.api.WirelessReceivingDevice;
import codechicken.wirelessredstone.api.WirelessTransmittingDevice;
import codechicken.wirelessredstone.network.WRServerPH;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import codechicken.lib.math.MathHelper;
import codechicken.lib.vec.Vector3;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public abstract class RedstoneEther
{
    public static class TXNodeInfo
    {
        public TXNodeInfo(int freq2, boolean b) {
            freq = freq2;
            on = b;
        }

        public int freq;
        public boolean on;
    }

    static class DimensionalEtherHash
    {
        TreeMap<BlockPos, TXNodeInfo> transmittingblocks = new TreeMap<BlockPos, RedstoneEther.TXNodeInfo>();
        TreeMap<BlockPos, Integer> recievingblocks = new TreeMap<BlockPos, Integer>();
        HashSet<WirelessTransmittingDevice> transmittingdevices = new HashSet<WirelessTransmittingDevice>();
        ArrayList<RedstoneEtherFrequency> freqsToSave = new ArrayList<RedstoneEtherFrequency>();

        TreeSet<BlockPos> jammerset = new TreeSet<BlockPos>();
        TreeMap<BlockPos, Integer> jammednodes = new TreeMap<BlockPos, Integer>();
    }

    public final boolean remote;
    protected RedstoneEtherFrequency[] freqarray;
    protected HashMap<Integer, DimensionalEtherHash> ethers = new HashMap<Integer, DimensionalEtherHash>();

    protected int publicfrequencyend;
    protected int sharedfrequencyend;
    protected int numprivatefreqs;

    protected HashSet<WirelessReceivingDevice> receivingdevices = new HashSet<WirelessReceivingDevice>();
    protected HashMap<String, boolean[]> playerJammedMap;
    protected HashMap<Integer, String> privateFreqs;

    protected int processingAddittions = Integer.MAX_VALUE;
    protected HashMap<BlockPos, Integer> backupmap;
    protected HashMap<EntityLivingBase, Integer> jammedentities;

    public static final int numfreqs = 5000;

    public static final int jammerrange = SaveManager.config().getTag("core.jammer.range").setComment("Range In Blocks").getIntValue(10);
    public static final int jammerrangePow2 = jammerrange * jammerrange;
    public static final int jammertimeout = SaveManager.config().getTag("core.jammer.timeout").setComment("Timeout In Seconds:Applies to both blocks and players").getIntValue(60) * 20;
    public static final int jammerrandom = jammertimeout / 3;
    public static final int jammerentitywait = SaveManager.config().getTag("core.jammer.entitydelay").getIntValue(5) * 20;
    public static final int jammerentityretry = SaveManager.config().getTag("core.jammer.entityretry").setComment("Jam an entity again after x seconds").getIntValue(10) * 20;
    public static final int jammerblockwait = SaveManager.config().getTag("core.jammer.blockdelay").setComment("Delay in seconds before jamming the first time").getIntValue(10) * 20;

    public static ItemStack[] coloursetters;

    public static final int numcolours = 14;
    public static final float minrps = 0.02F;
    public static final float maxrps = 2.98F;
    public static final double gradrps = maxrps / 5000D;

    public static final String localdyenames[] = {
            "red", "green", "brown", "blue", "purple", "cyan", "silver", "gray",
            "pink", "lime", "yellow", "lightBlue", "magenta", "orange"};

    public static final String fulldyenames[] = {
            "Red", "Green", "Brown", "Blue", "Purple", "Cyan", "Light Gray", "Gray",
            "Pink", "Lime", "Yellow", "Light Blue", "Magenta", "Orange"};

    public static final int colours[] = {
            0xFFB3312C, 0xFF336600, 0xFF51301A, 0xFF253192, 0xFF7B2FBE, 0xFF287697, 0xFF848484, 0xFF434343, 0xFFD88198,
            0xFF41CD34, 0xFFDECF2A, 0xFF6689D3, 0xFFC354CD, 0xFFEB8844};

    static {
        SaveManager.config().getTag("core.jammer").useBraces();
    }

    private static RedstoneEtherServer serverEther;
    private static RedstoneEtherClient clientEther;

    public static int pythagorasPow2(BlockPos node1, BlockPos node2) {
        return (node1.getX() - node2.getX()) * (node1.getX() - node2.getX()) +
                (node1.getY() - node2.getY()) * (node1.getY() - node2.getY()) +
                (node1.getZ() - node2.getZ()) * (node1.getZ() - node2.getZ());
    }

    public static double pythagorasPow2(BlockPos node, Vector3 point) {
        return (node.getX() - point.x) * (node.getX() - point.x) +
                (node.getY() - point.y) * (node.getY() - point.y) +
                (node.getZ() - point.z) * (node.getZ() - point.z);
    }

    public static void loadServerWorld(World world) {
        int dimension = CommonUtils.getDimension(world);
        if (serverEther == null)
            new RedstoneEtherServer().init(world);

        serverEther.addEther(world, dimension);
    }

    public static void unloadServerWorld(World world) {
        int dimension = CommonUtils.getDimension(world);
        if (serverEther != null)
            serverEther.remEther(world, dimension);
    }

    public static void loadClientEther(World world) {
        new RedstoneEtherClient().init(world);
        clientEther.addEther(world, CommonUtils.getDimension(world));
    }

    public static void unloadServer() {
        if (serverEther != null && serverEther.ethers.isEmpty()) {
            serverEther.unload();
            serverEther = null;
        }
    }

    public static RedstoneEther get(boolean remote) {
        return remote ? clientEther : serverEther;
    }

    @SideOnly(Side.CLIENT)
    public static RedstoneEtherClient client() {
        return clientEther;
    }

    public static RedstoneEtherServer server() {
        return serverEther;
    }

    public static void registerColourSetters(ItemStack[] colours) {
        if (coloursetters != null)
            throw new IllegalStateException("Colour Setters Already Set");

        if (colours.length != numcolours + 1)
            throw new IllegalStateException("Not " + (numcolours + 1) + " colours in setter!");

        coloursetters = colours;
    }

    public static ItemStack[] getColourSetters() {
        if (coloursetters == null) {
            coloursetters = new ItemStack[]{
                    new ItemStack(Items.DYE, 1, 1),
                    new ItemStack(Items.DYE, 1, 2),
                    new ItemStack(Items.DYE, 1, 3),
                    new ItemStack(Items.DYE, 1, 4),
                    new ItemStack(Items.DYE, 1, 5),
                    new ItemStack(Items.DYE, 1, 6),
                    new ItemStack(Items.DYE, 1, 7),
                    new ItemStack(Items.DYE, 1, 8),
                    new ItemStack(Items.DYE, 1, 9),
                    new ItemStack(Items.DYE, 1, 10),
                    new ItemStack(Items.DYE, 1, 11),
                    new ItemStack(Items.DYE, 1, 12),
                    new ItemStack(Items.DYE, 1, 13),
                    new ItemStack(Items.DYE, 1, 14),
                    new ItemStack(Items.REDSTONE, 1)};
        }
        return coloursetters;
    }

    public static TileEntity getTile(World world, BlockPos node) {
        return world.getTileEntity(node);
    }

    public static int[] parseFrequencyRange(String freqstring) {
        String splitstring[] = freqstring.split("-");
        if (splitstring.length == 1) {
            try {
                return (new int[]{Integer.parseInt(splitstring[0]), Integer.parseInt(splitstring[0])});
            } catch (NumberFormatException numberformatexception) {
                return (new int[]{-1, -1});
            }
        }
        if (splitstring.length == 2) {
            try {
                return (new int[]{Integer.parseInt(splitstring[0]), Integer.parseInt(splitstring[1])});
            } catch (NumberFormatException numberformatexception1) {
                return (new int[]{-1, -1});
            }
        }
        return (new int[]{-1, -1});
    }

    public static int getRandomTimeout(Random rand) {
        return jammertimeout + rand.nextInt(jammerrandom * 2) - jammerrandom;
    }

    public static float getRotation(double renderTime, int freq) {
        if (freq == 0) {
            return 0;
        }

        float spinangle = (float) ((renderTime / 20F) * (gradrps * freq + minrps));
        return spinangle * 6.2832F;
    }

    public static double getSineWave(double renderTime, int speed) {
        return MathHelper.sin(renderTime * 0.017453 * speed);
    }

    protected RedstoneEther(boolean client) {
        remote = client;
    }

    public void init(World world) {
        if (world.isRemote)
            clientEther = (RedstoneEtherClient) this;
        else
            serverEther = (RedstoneEtherServer) this;

        freqarray = new RedstoneEtherFrequency[numfreqs + 1];
        for (int freq = 1; freq <= numfreqs; freq++) {
            freqarray[freq] = new RedstoneEtherFrequency(this, freq);
        }
        jammedentities = new HashMap<EntityLivingBase, Integer>();
        playerJammedMap = new HashMap<String, boolean[]>();
        privateFreqs = new HashMap<Integer, String>();
    }

    protected void addEther(World world, int dimension) {
        DimensionalEtherHash dimensionalEther = new DimensionalEtherHash();
        ethers.put(dimension, dimensionalEther);

        for (int freq = 1; freq <= numfreqs; freq++) {
            freqarray[freq].addEther(world, dimension);
        }
    }

    public void remEther(World world, int dimension) {
        ethers.remove(dimension);
        for (int freq = 1; freq <= numfreqs; freq++)
            freqarray[freq].remEther(dimension);
    }

    public void loadTransmitter(int dimension, int x, int y, int z, int freq) {
        BlockPos node = new BlockPos(x, y, z);
        ethers.get(dimension).transmittingblocks.put(node, new TXNodeInfo(freq, true));
        freqarray[freq].loadTransmitter(node, dimension);
    }

    public boolean isFreqOn(int freq) {
        return freqarray[freq].isOn();
    }

    public boolean isPlayerJammed(EntityPlayer player) {
        Integer timeout = jammedentities.get(player);
        return timeout != null && timeout > 0;
    }

    public abstract void jamEntity(EntityLivingBase entity, boolean jam);

    private boolean[] getJammedFreqs(String username) {
        username = username.toLowerCase();
        boolean[] jammedFreqs = playerJammedMap.get(username);
        if (jammedFreqs == null) {
            playerJammedMap.put(username, new boolean[numfreqs + 1]);
            loadJammedFrequencies(username);
            jammedFreqs = playerJammedMap.get(username);
        }
        return jammedFreqs;
    }

    protected void loadJammedFrequencies(String username) {
    }

    public boolean canBroadcastOnFrequency(EntityPlayer player, int freq) {
        return canBroadcastOnFrequency(player.getName(), freq);
    }

    public boolean canBroadcastOnFrequency(String username, int freq) {
        if (freq == 0)//dummy :)
            return true;

        if (freq > numfreqs || freq <= 0) {
            return false;
        }

        if (freq <= publicfrequencyend) {
            return true;
        } else if (username != null && getJammedFreqs(username)[freq - 1]) {
            return false;
        } else if (privateFreqs.containsKey(freq)) {
            return privateFreqs.get(freq).equalsIgnoreCase(username);
        } else {
            return true;
        }
    }

    public String getJammedFrequencies(String username) {
        int endfreq = publicfrequencyend;
        StringBuilder jammedfreqs = new StringBuilder();
        do {
            int jammedrange[] = getNextFrequencyRange(username, endfreq + 1, true);
            int startfreq = jammedrange[0];
            endfreq = jammedrange[1];
            if (startfreq == -1)
                break;
            if (jammedfreqs.length() != 0)
                jammedfreqs.append(',');
            if (startfreq == endfreq)
                jammedfreqs.append(startfreq);
            else {
                jammedfreqs.append(startfreq);
                jammedfreqs.append('-');
                jammedfreqs.append(endfreq);
            }
        }
        while (endfreq <= 5000);

        return jammedfreqs.toString();
    }

    public int[] getNextFrequencyRange(String username, int beginfreq, boolean jammed) {
        boolean jammedFreqs[] = getJammedFreqs(username);
        int currentfreq = beginfreq;
        int startfreq = -1;
        do {
            if (currentfreq > numfreqs) {
                if (startfreq != -1)
                    return (new int[]{startfreq, numfreqs});
                return (new int[]{-1, -1});//-1, -1 is none
            }
            if (jammedFreqs[currentfreq - 1] == jammed)//jammed
            {
                if (startfreq == -1)//last freq was open
                    startfreq = currentfreq;
            } else//open
            {
                if (startfreq != -1)//last freq was jammed
                    return (new int[]{startfreq, currentfreq - 1});
            }
            currentfreq++;
        }
        while (true);
    }

    public void setLastPublicFrequency(int freq) {
        publicfrequencyend = freq;
        if (!remote) {
            SaveManager.generalProp.setProperty("PublicFrequencies", freq);
            WRServerPH.sendPublicFrequencyTo(null, publicfrequencyend);
        }

        verifyPrivateFreqs();

        if (freq > sharedfrequencyend) {
            setLastSharedFrequency(freq);
        }
    }

    private void verifyPrivateFreqs() {
        for (Iterator<Integer> iterator = privateFreqs.keySet().iterator(); iterator.hasNext(); ) {
            int freq = iterator.next();

            if (freq <= publicfrequencyend || freq > sharedfrequencyend) {
                iterator.remove();

                if (!remote)
                    WRServerPH.sendSetFreqOwner(freq, "");
            }
        }
    }

    public void setLastSharedFrequency(int freq) {
        int prevEnd = Math.max(sharedfrequencyend, publicfrequencyend);
        sharedfrequencyend = freq;
        if (!remote) {
            SaveManager.generalProp.setProperty("SharedFrequencies", freq);
            WRServerPH.sendSharedFrequencyTo(null, publicfrequencyend);
        }
        verifyPrivateFreqs();

        for (String username : playerJammedMap.keySet()) {
            if (getJammedFrequencies(username).equals("" + (prevEnd + 1) + "-" + numfreqs)) {
                setFrequencyRange(username, 1, numfreqs, false);
                setFrequencyRange(username, sharedfrequencyend + 1, numfreqs, true);
            }
        }
    }

    public void setNumPrivateFreqs(int num) {
        numprivatefreqs = num;
        if (!remote) {
            SaveManager.generalProp.setProperty("PrivateFrequencies", num);
        }
    }

    public int getLastPublicFrequency() {
        return publicfrequencyend;
    }

    public int getLastSharedFrequency() {
        return sharedfrequencyend;
    }

    public int getNumPrivateFreqs() {
        return numprivatefreqs;
    }

    public void setFrequencyRange(String username, int firstfreq, int lastfreq, boolean jam) {
        if (!remote) {
            EntityPlayer player = ServerUtils.getPlayer(username);
            if (player != null)
                WRServerPH.sendSetFrequencyRangeTo(player, firstfreq, lastfreq, jam);
        }

        if (lastfreq > numfreqs)
            lastfreq = numfreqs;

        boolean[] jammedFreqs = getJammedFreqs(username);
        for (int settingfreq = firstfreq; settingfreq <= lastfreq; settingfreq++)
            jammedFreqs[settingfreq - 1] = jam;
    }

    public int getFreqColour(int freq) {
        int id = getFreqColourId(freq);
        if (id == -1)
            return 0xFFFFFFFF;

        return colours[id];
    }

    public int getFreqColourId(int freq) {
        if (freq == 0 || freqarray == null || freqarray[freq] == null)//sometimes render gets in before init on servers
            return -1;

        return freqarray[freq].getColourId();
    }

    public String getFreqColourName(int freq, boolean local) {
        int id = getFreqColourId(freq);

        return local ? localdyenames[id] : fulldyenames[id];
    }

    public void setFreqColour(int freq, int colourid) {
        freqarray[freq].setColour(colourid);
    }

    public String getFreqName(int freq) {
        if (freq == 0) {
            return null;
        }
        return freqarray[freq].getName();
    }

    public void setFreqName(int freq, String name) {
        freqarray[freq].setName(name);
    }

    public ArrayList<String> getMatchingAllowedNames(EntityPlayer player, String match) {
        ArrayList<String> allnames = new ArrayList<String>();

        for (int freq = 1; freq <= numfreqs; freq++) {
            String name = freqarray[freq].getName();
            if (name == null || name.equals("") || !canBroadcastOnFrequency(player, freq) ||
                    name.length() < match.length() || !name.substring(0, match.length()).equalsIgnoreCase(match)) {
                continue;
            }
            allnames.add(name);
        }

        return allnames;
    }

    public ArrayList<String> getAllowedNames(EntityPlayer player) {
        ArrayList<String> allnames = new ArrayList<String>();

        for (int freq = 1; freq <= numfreqs; freq++) {
            String name = freqarray[freq].getName();
            if (name == null || name.equals("") || !canBroadcastOnFrequency(player, freq)) {
                continue;
            }
            allnames.add(name);
        }

        return allnames;
    }

    public ArrayList<String> getAllNames() {
        ArrayList<String> allnames = new ArrayList<String>();

        for (int freq = 1; freq <= numfreqs; freq++) {
            String name = freqarray[freq].getName();
            if (name == null || name.equals("")) {
                continue;
            }
            allnames.add(name);
        }

        return allnames;
    }

    public int getFreqByName(String slotname) {
        for (int freq = 1; freq <= numfreqs; freq++) {
            String name = freqarray[freq].getName();
            if (name != null && name.equals(slotname)) {
                return freq;
            }
        }
        return -1;
    }

    public ArrayList<Integer> getPrivateFrequencies(String username) {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (Entry<Integer, String> entry : privateFreqs.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(username))
                list.add(entry.getKey());
        }
        return list;
    }

    public boolean isFreqPrivate(int freq) {
        return privateFreqs.containsKey(freq);
    }

    public String getFreqOwner(int freq) {
        return privateFreqs.get(freq);
    }

    public void removeFreqOwner(int freq) {
        privateFreqs.remove(freq);

        if (!remote) {
            SaveManager.freqProp.removeProperty(freq + ".owner");
            WRServerPH.sendSetFreqOwner(freq, "");
        }
    }

    public void setFreqOwner(int freq, String username) {
        if (username == null || username.equals("")) {
            removeFreqOwner(freq);
        } else {
            privateFreqs.put(freq, username);
            if (!remote && getPrivateFrequencies(username).size() < numprivatefreqs) {
                privateFreqs.put(freq, username);

                if (!SaveManager.isLoading())
                    SaveManager.freqProp.setProperty(freq + ".owner", username);

                WRServerPH.sendSetFreqOwner(freq, username);
            }
        }
    }

    public abstract void setFreq(ITileWireless tile, int freq);
}