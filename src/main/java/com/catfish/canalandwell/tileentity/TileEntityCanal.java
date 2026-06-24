package com.catfish.canalandwell.tileentity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.catfish.canalandwell.CanalAndWell;
import com.catfish.canalandwell.Config;
import com.catfish.canalandwell.block.BlockCanal;

/**
 * 水渠 TileEntity —— 存储湿润状态，处理水传播与连锁干燥。
 *
 * ============ 水流生成 ============
 * spawnFlowingWater() 仅在当前水渠的连接方向（通过 BlockCanal.getConnectionDirections）
 * 生成流水，而非盲目四向。STRAIGHT 只灌两端，T 只灌三端，CROSS 灌四端，CLOSED 灌轴端。
 *
 * ============ 延迟机制 ============
 * 湿润传播与连锁干燥均使用定时延迟而非即时递归：
 * - 变湿后通过 scheduleBlockUpdate 安排邻居在 waterSpreadDelay tick 后检查
 * - 变干后通过 scheduleBlockUpdate 安排邻居在 chainDryDelay tick 后检查
 * 每跳延迟由 Config 控制，避免瞬间整条水渠同步闪烁。
 *
 * ============ 客户端同步 ============
 * isWet 通过 S35PacketUpdateTileEntity 同步客户端，贴图实时切换。
 */
public class TileEntityCanal extends TileEntity {

    private boolean isWet;
    private int tickCounter;

    @Override
    public boolean canUpdate() {
        return true;
    }

    public boolean isWet() { return isWet; }

    /** 设置湿润状态并同步客户端。 */
    public void setWet(boolean wet) {
        if (this.isWet != wet) {
            this.isWet = wet;
            markDirty();
            if (worldObj != null) {
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                if (!worldObj.isRemote) {
                    sendTileEntityUpdate();
                }
            }
        }
    }

    private void sendTileEntityUpdate() {
        if (!(worldObj instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) worldObj;
        S35PacketUpdateTileEntity pkt = new S35PacketUpdateTileEntity(
                xCoord, yCoord, zCoord, 0, getUpdateTag());
        double range = 64 * 64;
        for (Object obj : ws.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) obj;
            if (player.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= range) {
                player.playerNetServerHandler.sendPacket(pkt);
            }
        }
    }

    private NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("isWet", isWet);
        return tag;
    }

    // ══════════════════════════════════════════════════════
    //  Packet 同步
    // ══════════════════════════════════════════════════════

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    // ══════════════════════════════════════════════════════
    //  每 tick
    // ══════════════════════════════════════════════════════

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        if (++tickCounter < Config.tickRate) return;
        tickCounter = 0;

        Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
        if (!(block instanceof BlockCanal)) return;

        if (!isWet && detectWaterSource()) {
            setWet(true);
            if (Config.debugLogging) {
                CanalAndWell.LOG.info("[Canal] Wet at {}, {}, {}", xCoord, yCoord, zCoord);
            }
            // 延迟通知邻居传播湿润状态
            scheduleNeighbors(Config.waterSpreadDelay);
        }

        if (isWet) {
            if (!shouldStayWet()) {
                setWet(false);
                if (Config.debugLogging) {
                    CanalAndWell.LOG.info("[Canal] Dry at {}, {}, {} (source lost)", xCoord, yCoord, zCoord);
                }
                cleanupFlowingWater();
                // 延迟通知邻居连锁干燥
                scheduleNeighbors(Config.chainDryDelay);
            } else {
                propagateWetness();
                spawnFlowingWater();
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  水源检测
    // ══════════════════════════════════════════════════════

    public boolean detectWaterSource() {
        Block configuredFluid = getFluidBlock();

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            int neighborMeta = worldObj.getBlockMetadata(nx, ny, nz);

            if (neighbor == configuredFluid && neighborMeta == 0) return true;
            if (configuredFluid != Blocks.water && neighbor == Blocks.water && neighborMeta == 0) return true;

            if (neighbor instanceof BlockCanal) {
                TileEntity te = worldObj.getTileEntity(nx, ny, nz);
                if (te instanceof TileEntityCanal && ((TileEntityCanal) te).isWet()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDirectWaterSource() {
        Block configuredFluid = getFluidBlock();

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            int neighborMeta = worldObj.getBlockMetadata(nx, ny, nz);

            if (neighbor == configuredFluid && neighborMeta == 0) return true;
            if (configuredFluid != Blocks.water && neighbor == Blocks.water && neighborMeta == 0) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════
    //  干燥判定（BFS 溯源）
    // ══════════════════════════════════════════════════════

    /**
     * BFS 沿湿润水渠邻居回溯，在 waterFlowRange 深度内查找直接水源。
     */
    private boolean shouldStayWet() {
        if (hasDirectWaterSource()) return true;

        Set<Long> visited = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        visited.add(pack(xCoord, yCoord, zCoord));
        queue.add(new int[]{xCoord, yCoord, zCoord});

        int maxDepth = Config.waterFlowRange;
        for (int depth = 0; depth < maxDepth && !queue.isEmpty(); depth++) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                int[] p = queue.poll();
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

                    int nx = p[0] + dir.offsetX;
                    int ny = p[1] + dir.offsetY;
                    int nz = p[2] + dir.offsetZ;
                    long key = pack(nx, ny, nz);
                    if (visited.contains(key)) continue;

                    if (worldObj.getBlock(nx, ny, nz) instanceof BlockCanal) {
                        TileEntity te = worldObj.getTileEntity(nx, ny, nz);
                        if (te instanceof TileEntityCanal && ((TileEntityCanal) te).isWet()) {
                            if (((TileEntityCanal) te).hasDirectWaterSource()) {
                                return true;
                            }
                            visited.add(key);
                            queue.add(new int[]{nx, ny, nz});
                        }
                    }
                }
            }
        }
        return false;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) | (((long) y & 0xFF) << 26) | (((long) z & 0x3FFFFFF) << 34);
    }

    // ══════════════════════════════════════════════════════
    //  连锁干燥
    // ══════════════════════════════════════════════════════

    /** 外部入口 — 由 BlockCanal 在邻居变化或封闭时调用 */
    public void checkShouldDry() {
        if (!isWet) return;
        if (!shouldStayWet()) {
            setWet(false);
            if (Config.debugLogging) {
                CanalAndWell.LOG.info("[Canal] Dry at {}, {}, {} (chain)", xCoord, yCoord, zCoord);
            }
            cleanupFlowingWater();
            scheduleNeighbors(Config.chainDryDelay);
        }
    }

    // ══════════════════════════════════════════════════════
    //  传播（湿润）
    // ══════════════════════════════════════════════════════

    private void propagateWetness() {
        for (ForgeDirection dir : new ForgeDirection[]{
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            if (!(neighbor instanceof BlockCanal)) continue;

            TileEntity te = worldObj.getTileEntity(nx, ny, nz);
            if (!(te instanceof TileEntityCanal)) continue;

            TileEntityCanal ct = (TileEntityCanal) te;
            if (!ct.isWet()) {
                ct.setWet(true);
            }
        }
    }

    /** 实时 1-hop 传播 + 生成流水（新方块加入已有系统时） */
    public void propagateImmediate() {
        if (!isWet) return;
        propagateWetness();
        spawnFlowingWater();
    }

    // ══════════════════════════════════════════════════════
    //  流水生成 / 清除
    // ══════════════════════════════════════════════════════

    /**
     * 在湿润水渠的**连接方向**空气方块中生成流水。
     * 仅在水渠朝向有效的方向生成，而非盲目四向。
     *
     * 端口输出：连接方向上的空气方块直接放置流态水，下方不必是水渠。
     * 这样水渠的开放端口（无相邻水渠的连通方向）能正常输出水流，
     * 水流自然下落，不需要下方有特殊支撑。
     */
    private void spawnFlowingWater() {
        int meta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
        ForgeDirection[] dirs = BlockCanal.getConnectionDirections(meta);
        Block fluidBlock = getFluidBlock();
        int fluidMeta = Config.flowingFluidMeta;

        for (ForgeDirection dir : dirs) {
            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            if (!worldObj.isAirBlock(nx, ny, nz)) continue;

            // 仅当空气下方有任意支撑方块时才放置水（避免水悬空掉落导致意外泛滥）
            Block below = worldObj.getBlock(nx, ny - 1, nz);
            if (!worldObj.isAirBlock(nx, ny - 1, nz)) {
                worldObj.setBlock(nx, ny, nz, fluidBlock, fluidMeta, 3);
            }
        }
    }

    /**
     * 水渠变干时，清除其连接方向已放置的流水方块。
     * 与 spawnFlowingWater 的条件对称：方向上有流态/水源水方块，
     * 且下方非空气时清除。
     */
    private void cleanupFlowingWater() {
        if (!Config.cleanupFlowingWater) return;

        int meta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
        ForgeDirection[] dirs = BlockCanal.getConnectionDirections(meta);
        Block fluidBlock = getFluidBlock();

        for (ForgeDirection dir : dirs) {
            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            // 清除流态水或水源水，不论 meta
            if (neighbor == fluidBlock || neighbor == Blocks.water) {
                // 仅在支撑面上清除（与生成条件对称，避免误删自然水体）
                if (!worldObj.isAirBlock(nx, ny - 1, nz)) {
                    worldObj.setBlockToAir(nx, ny, nz);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  延迟调度
    // ══════════════════════════════════════════════════════

    /**
     * 在 delay 刻后调度相邻水渠的 updateTick。
     * 用于湿润传播和连锁干燥的逐跳延迟。
     */
    private void scheduleNeighbors(int delay) {
        for (ForgeDirection dir : new ForgeDirection[]{
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            if (neighbor instanceof BlockCanal) {
                // scheduleBlockUpdate 会使邻居的 updateTick 在 delay 刻后触发
                worldObj.scheduleBlockUpdate(nx, ny, nz, neighbor, delay);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  流体方块解析
    // ══════════════════════════════════════════════════════

    private static Block getFluidBlock() {
        Block configured = Block.getBlockFromName(Config.flowingFluidBlockName);
        if (configured != null) return configured;
        CanalAndWell.LOG.warn("[Canal] Unknown fluid '{}', falling back to water",
                Config.flowingFluidBlockName);
        return Blocks.water;
    }

    // ══════════════════════════════════════════════════════
    //  NBT
    // ══════════════════════════════════════════════════════

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        isWet = nbt.getBoolean("isWet");
        tickCounter = nbt.getInteger("tickCounter");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("isWet", isWet);
        nbt.setInteger("tickCounter", tickCounter);
    }
}
